@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.karewinkcloud.agentweb.client.ui

import androidx.lifecycle.ViewModelStore
import com.karewinkcloud.agentweb.client.core.*
import com.karewinkcloud.agentweb.client.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File

class ComfyViewModelTest {
    @get:Rule val temporary = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private lateinit var store: ViewModelStore
    private lateinit var pending: Pending
    private lateinit var repo: FakeComfy
    private lateinit var vm: ComfyViewModel
    private val id = "12345678-1234-1234-1234-123456789012"
    private val workflow = ComfyWorkflow("image", "Image", "", "image", "cloud_gpu", listOf(
        ComfyInput("prompt", buildJsonObject { put("type", "string"); put("required", true) }),
        ComfyInput("steps", buildJsonObject { put("type", "integer"); put("default", 20); put("minimum", 1); put("maximum", 30) })
    ), null, emptyList())
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        store = ViewModelStore(); pending = Pending(); repo = FakeComfy()
        vm = create(repo, pending); vm.setForeground(true)
    }
    private fun create(repository: FakeComfy, persistence: Pending, blocked: Boolean = false) =
        ComfyViewModel(repository, persistence, temporary.newFolder(), blocked, dispatcher).also { store.put("vm-${repository.hashCode()}", it) }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }
    private fun modelTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { try { block() } finally { store.clear() } }
    @Test fun persistsBeforeSingleSubmissionAndClearsOnlyOnTerminalTruth() = modelTest {
        runCurrent(); val response = CompletableDeferred<ComfyJob>()
        repo.onSubmit = { request -> assertEquals(request.requestId, pending.id); response.await() }
        vm.prompt("山水图像"); vm.submit(); vm.submit(); runCurrent()
        assertEquals(1, repo.submissions.size); val request = repo.submissions.single()
        assertTrue(validComfyId(request.requestId)); assertEquals(20L, request.parameters.long("steps")); assertEquals("山水图像", request.parameters.string("prompt"))
        assertTrue(vm.state.value.submitting); assertFalse(vm.state.value.canSubmit)
        response.complete(ComfyJob(request.requestId, "image", "succeeded")); runCurrent()
        assertNull(pending.id); assertNull(vm.state.value.pending); assertTrue(vm.state.value.canSubmit)
    }
    @Test fun failedPersistenceNeverDispatches() = modelTest {
        runCurrent(); pending.failWrite = true; vm.prompt("mountain"); vm.submit(); runCurrent()
        assertEquals("storage", vm.state.value.error); assertTrue(repo.submissions.isEmpty())
    }
    @Test fun ambiguousSubmissionRemainsLockedAndChecksSameIdentity() = modelTest {
        runCurrent(); repo.onSubmit = { throw ComfyFailure("network") }; vm.prompt("mountain"); vm.submit(); runCurrent()
        val submittedId = repo.submissions.single().requestId
        assertEquals(submittedId, pending.id); assertEquals("unknown", vm.state.value.selected?.status)
        vm.submit(); advanceTimeBy(10_000); runCurrent()
        assertEquals(1, repo.submissions.size); assertTrue(repo.reads.isEmpty())
        repo.onJob = { throw ComfyFailure("not_found") }; vm.check(); runCurrent()
        assertEquals(listOf(submittedId), repo.reads); assertEquals(submittedId, pending.id); assertFalse(vm.state.value.canSubmit)
    }
    @Test fun processRecreationRecoversWithGetAndNeverPosts() = modelTest {
        runCurrent(); vm.setForeground(false); pending.id = id
        val next = FakeComfy().apply { onJob = { ComfyJob(it, "image", "unknown") } }
        val recreated = create(next, pending); recreated.setForeground(true); runCurrent()
        assertEquals(listOf(id), next.reads); assertTrue(next.submissions.isEmpty())
        assertEquals(id, recreated.state.value.pending); assertEquals("unknown", recreated.state.value.selected?.status)
        advanceTimeBy(20_000); runCurrent(); assertEquals(1, next.reads.size)
    }
    @Test fun answeredRejectionUnlocksButLegacyRejectionDoesNot() = modelTest {
        runCurrent(); repo.onSubmit = { throw ComfyFailure("invalid_parameters") }
        vm.prompt("mountain"); vm.submit(); runCurrent()
        assertNull(pending.id); assertEquals("failed", vm.state.value.selected?.status); assertTrue(vm.state.value.canSubmit)
        repo.onSubmit = { throw ComfyFailure("plugin_rejected") }; vm.submit(); runCurrent()
        assertNotNull(pending.id); assertEquals("unknown", vm.state.value.selected?.status); assertFalse(vm.state.value.canSubmit)
    }
    @Test fun pollingStopsInBackgroundAndUserPauseSurvivesForeground() = modelTest {
        runCurrent(); vm.prompt("mountain"); vm.submit(); runCurrent()
        assertTrue(vm.state.value.polling)
        vm.setForeground(false); advanceTimeBy(10_000); runCurrent(); assertTrue(repo.reads.isEmpty())
        vm.setForeground(true); runCurrent(); assertEquals(1, repo.reads.size)
        vm.stopPolling(); vm.setForeground(false); vm.setForeground(true); advanceTimeBy(10_000); runCurrent()
        assertEquals(1, repo.reads.size); assertTrue(vm.state.value.pollingPaused)
        repo.onJob = { ComfyJob(it, "image", "succeeded") }; vm.resumePolling(); runCurrent()
        assertEquals(2, repo.reads.size); assertNull(pending.id)
    }
    @Test fun pollingFailureIsNotGenerationFailure() = modelTest {
        runCurrent(); vm.prompt("mountain"); vm.submit(); runCurrent()
        repo.onJob = { throw ComfyFailure("unavailable") }; advanceTimeBy(3000); runCurrent()
        assertEquals("query_failed", vm.state.value.selected?.status); assertNotNull(pending.id)
        assertFalse(vm.state.value.polling); assertEquals(1, repo.submissions.size)
    }
    @Test fun recommendationsDebounceNeverApplyAndHideOnFailure() = modelTest {
        runCurrent(); vm.prompt("mount"); advanceTimeBy(500); vm.prompt("mountain"); advanceTimeBy(699); runCurrent()
        assertEquals(0, repo.recommendations)
        advanceTimeBy(1); runCurrent(); assertEquals(1, repo.recommendations)
        assertEquals("image", vm.state.value.workflowId); assertEquals("video", vm.state.value.suggestions.single().workflowId)
        vm.choose("video"); assertEquals("video", vm.state.value.kind); assertTrue(vm.state.value.suggestions.isEmpty())
        vm.prompt(""); vm.prompt("new creation"); repo.recommendFails = true; advanceTimeBy(700); runCurrent()
        assertTrue(vm.state.value.suggestions.isEmpty()); assertNull(vm.state.value.error); assertTrue(vm.state.value.canSubmit)
    }
    @Test fun composingDoesNotSendRecommendationOrReplaceManualSelection() = modelTest {
        runCurrent(); vm.prompt("中国山水", composing = true); advanceTimeBy(1000); runCurrent(); assertEquals(0, repo.recommendations)
        vm.prompt("中国山水"); vm.choose("video"); advanceTimeBy(1000); runCurrent(); assertEquals(0, repo.recommendations)
    }
    @Test fun filtersKeepBillingAndMediaSeparateAndPreservePrompt() = modelTest {
        runCurrent(); vm.prompt("mountain"); vm.filter("image", "partner_api")
        assertEquals("api", vm.state.value.workflowId); assertEquals("mountain", vm.state.value.prompt)
        vm.filter("audio", "cloud_gpu"); assertNull(vm.state.value.workflow); assertFalse(vm.state.value.canSubmit)
    }
    @Test fun validationDoesNotSubmitAndPointsToField() = modelTest {
        runCurrent(); vm.prompt("mountain"); vm.parameter("steps", "31"); vm.submit(); runCurrent()
        assertTrue(repo.submissions.isEmpty()); assertEquals("steps", vm.state.value.invalidField)
    }
    @Test fun signInGateAndAuthLossStopAllReadsAndMutations() = modelTest {
        runCurrent(); repo.authenticationRequired.value = true; runCurrent()
        assertTrue(vm.state.value.signInRequired); assertTrue(vm.state.value.jobs.isEmpty())
        val next = FakeComfy(); vm.changeConnection(next, Pending(), true); vm.setForeground(true); vm.submit(); runCurrent()
        assertEquals(0, next.catalogCalls); assertTrue(next.submissions.isEmpty())
    }
    @Test fun serverSwitchRejectsLateReceiptWithoutErasingOldPendingIdentity() = modelTest {
        runCurrent(); val receipt = CompletableDeferred<ComfyJob>()
        repo.onSubmit = { withContext(NonCancellable) { receipt.await() } }
        vm.prompt("mountain"); vm.submit(); runCurrent(); val original = pending.id!!
        val next = FakeComfy(); vm.changeConnection(next, Pending(), false); runCurrent()
        receipt.complete(ComfyJob(original, "image", "succeeded")); runCurrent()
        assertEquals(original, pending.id); assertNull(vm.state.value.selected); assertTrue(next.submissions.isEmpty())
    }
    @Test fun openingHistoryOnlyReadsAndNeverChangesDraft() = modelTest {
        runCurrent(); vm.prompt("keep me"); repo.onJob = { ComfyJob(it, "video", "succeeded") }
        vm.open(ComfyJob(id, "video", "succeeded")); runCurrent()
        assertEquals("keep me", vm.state.value.prompt); assertEquals(listOf(id), repo.reads); assertTrue(repo.submissions.isEmpty())
    }
    @Test fun resourcesAreLazyAndColdInventoryBlocksGeneration() = modelTest {
        runCurrent(); assertEquals(0, repo.resourceCalls)
        repo.catalog = listOf(workflow.copy(requiredModels = listOf("checkpoints" to "base")))
        repo.inventory = ComfyResources(emptyList(), false, true, true); vm.refresh(); runCurrent(); vm.prompt("mountain")
        assertEquals(1, repo.resourceCalls); assertFalse(vm.state.value.canSubmit)
        repo.inventory = ComfyResources(listOf(ComfyResource("checkpoints", "base", null)), true, false, false)
        advanceTimeBy(3000); runCurrent(); assertTrue(vm.state.value.canSubmit)
    }
    @Test fun catalogOutageDoesNotBlockSavedRequestRecovery() = modelTest {
        runCurrent(); vm.setForeground(false); pending.id = id
        val next = FakeComfy().apply { catalogFails = true; onJob = { ComfyJob(it, "image", "succeeded") } }
        val recreated = create(next, pending); recreated.setForeground(true); runCurrent()
        assertEquals(listOf(id), next.reads); assertTrue(next.submissions.isEmpty()); assertNull(pending.id)
        assertEquals("succeeded", recreated.state.value.selected?.status)
    }
    @Test fun lateHistoryCheckCannotClearNewCheckingState() = modelTest {
        runCurrent()
        val secondId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        val first = CompletableDeferred<ComfyJob>(); val second = CompletableDeferred<ComfyJob>()
        repo.onJob = { key -> withContext(NonCancellable) { if (key == id) first.await() else second.await() } }
        vm.open(ComfyJob(id, "image", "running")); runCurrent()
        vm.open(ComfyJob(secondId, "video", "running")); runCurrent()
        first.complete(ComfyJob(id, "image", "succeeded")); runCurrent()
        assertEquals(secondId, vm.state.value.selected?.requestId); assertTrue(vm.state.value.checking)
        second.complete(ComfyJob(secondId, "video", "succeeded")); runCurrent()
        assertFalse(vm.state.value.checking); assertEquals("succeeded", vm.state.value.selected?.status)
    }
    @Test fun unreadablePendingIdentityBlocksNewSubmissionEvenAfterPriorLoad() = modelTest {
        runCurrent(); vm.prompt("mountain"); pending.failRead = true
        vm.refresh(); runCurrent(); vm.submit(); runCurrent()
        assertEquals("storage", vm.state.value.error); assertFalse(vm.state.value.canSubmit); assertTrue(repo.submissions.isEmpty())
    }
    @Test fun outputCacheIsReusedButNeverAcrossServerEpochs() = modelTest {
        runCurrent(); val job = ComfyJob(id, "image", "succeeded"); val output = ComfyOutput(0, "image/png", 4)
        val first = vm.output(job, output); assertTrue(first.isFile)
        assertEquals(first, vm.output(job, output)); assertEquals(1, repo.downloads)
        val next = FakeComfy(); vm.changeConnection(next, Pending(), false); runCurrent()
        assertNotEquals(first, vm.output(job, output)); assertEquals(1, next.downloads)
    }
    private class Pending : ComfyPendingStore {
        var id: String? = null; var failWrite = false; var failRead = false
        override suspend fun read(): String? { if (failRead) throw ComfyFailure("storage"); return id }
        override suspend fun write(id: String?) { if (failWrite) throw ComfyFailure("storage"); this.id = id }
    }
    private inner class FakeComfy : ComfyRepository {
        override val authenticationRequired = MutableStateFlow(false)
        var catalog = listOf(workflow, workflow.copy(id = "video", kind = "video"), workflow.copy(id = "api", channel = "partner_api"))
        var catalogFails = false; var catalogCalls = 0; var recommendations = 0; var recommendFails = false; var resourceCalls = 0; var downloads = 0
        var inventory = ComfyResources(emptyList(), true, false, false)
        val submissions = mutableListOf<ComfySubmission>(); val reads = mutableListOf<String>()
        var onSubmit: suspend (ComfySubmission) -> ComfyJob = { ComfyJob(it.requestId, it.workflowId, "queued", it.parameters) }
        var onJob: suspend (String) -> ComfyJob = { ComfyJob(it, "image", "queued") }
        override suspend fun workflows(): List<ComfyWorkflow> { catalogCalls++; if (catalogFails) throw ComfyFailure("unavailable"); return catalog }
        override suspend fun recommend(prompt: String, channel: String): List<ComfySuggestion> {
            recommendations++; if (recommendFails) throw ComfyFailure("unavailable"); return listOf(ComfySuggestion("video", .9))
        }
        override suspend fun submit(request: ComfySubmission): ComfyJob { submissions += request; return onSubmit(request) }
        override suspend fun jobs() = emptyList<ComfyJob>()
        override suspend fun job(requestId: String): ComfyJob { reads += requestId; return onJob(requestId) }
        override suspend fun resources(refresh: Boolean): ComfyResources { resourceCalls++; return inventory }
        override suspend fun download(requestId: String, output: ComfyOutput, destination: File) { downloads++; destination.writeText("test") }
    }
}
