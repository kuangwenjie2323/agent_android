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
    @Test fun savedHistoryShowsBeforeTheFirstLoadButCannotSubmit() = modelTest {
        val fresh = FakeComfy(); val gate = CompletableDeferred<Unit>()
        val saved = ComfyJob(id, "image", "succeeded")
        fresh.savedJobs = listOf(saved); fresh.savedWorkflows = listOf(workflow)
        fresh.history = listOf(saved, ComfyJob("22345678-1234-1234-1234-123456789012", "image", "succeeded"))
        fresh.onHistory = { gate.await(); fresh.history }
        val cold = create(fresh, Pending()); cold.preload(); runCurrent()
        assertEquals(listOf(saved), cold.state.value.jobs)
        assertEquals(listOf(workflow), cold.state.value.workflows)
        assertFalse(cold.state.value.initialized); assertFalse(cold.state.value.canSubmit)
        gate.complete(Unit); runCurrent()
        assertEquals(2, cold.state.value.jobs.size); assertTrue(cold.state.value.initialized)
    }
    @Test fun uploadedPhotoBecomesTheImageReferenceAndFailuresStayOnTheField() = modelTest {
        val upload = "0123456789abcdef0123456789abcdef"
        val gate = CompletableDeferred<String>()
        repo.onUpload = { bytes, mime -> repo.uploads += mime to bytes.size; gate.await() }
        runCurrent(); vm.prompt("山水图像")
        vm.uploadReference("image") { "image/jpeg" to byteArrayOf(1, 2, 3) }; runCurrent()
        assertEquals("image", vm.state.value.uploading); assertFalse(vm.state.value.canSubmit)
        vm.uploadReference("image") { error("a second pick while uploading is ignored") }; runCurrent()
        gate.complete(upload); runCurrent()
        assertNull(vm.state.value.uploading); assertEquals(listOf("image/jpeg" to 3), repo.uploads)
        assertEquals(upload, wireJson.parseToJsonElement(vm.state.value.values.getValue("image")).jsonObject.string("upload_id"))
        assertArrayEquals(byteArrayOf(1, 2, 3), vm.referenceFile(upload).readBytes())
        vm.uploadReference("image") { throw java.io.IOException("unreadable") }; runCurrent()
        assertEquals("image" to "reference_unreadable", vm.state.value.uploadError)
        repo.onUpload = { _, _ -> throw ComfyFailure("network") }
        vm.uploadReference("image") { "image/jpeg" to byteArrayOf(1) }; runCurrent()
        assertEquals("image" to "upload_failed", vm.state.value.uploadError); assertNull(vm.state.value.uploading)
        vm.refresh(); runCurrent()  // an unrelated reload must not wipe the photo error
        assertEquals("image" to "upload_failed", vm.state.value.uploadError)
        repo.onUpload = { _, _ -> throw ComfyFailure("auth") }
        vm.uploadReference("image") { "image/jpeg" to byteArrayOf(1) }; runCurrent()
        assertEquals("image" to "auth", vm.state.value.uploadError)
        assertEquals(upload, wireJson.parseToJsonElement(vm.state.value.values.getValue("image")).jsonObject.string("upload_id"))
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
    @Test fun passingPollingFailuresBackOffAndRecover() = modelTest {
        runCurrent(); vm.prompt("mountain"); vm.submit(); runCurrent()
        repo.onJob = { throw ComfyFailure("network") }
        advanceTimeBy(3001); runCurrent(); assertEquals(1, repo.reads.size)
        assertEquals("queued", vm.state.value.selected?.status); assertNull(vm.state.value.error)  // one blip changes nothing
        assertTrue(vm.state.value.polling)
        advanceTimeBy(3000); runCurrent(); assertEquals(2, repo.reads.size)  // retries back off: 3 s, 6 s, 12 s ...
        advanceTimeBy(3000); runCurrent(); assertEquals(2, repo.reads.size)
        advanceTimeBy(3000); runCurrent(); assertEquals(3, repo.reads.size); assertEquals("network", vm.state.value.error)
        repo.onJob = { ComfyJob(it, "image", "succeeded") }; advanceTimeBy(12_001); runCurrent()
        assertEquals("succeeded", vm.state.value.selected?.status); assertNull(vm.state.value.error); assertNull(pending.id)
        assertEquals(1, repo.submissions.size)
    }
    @Test fun definitePollingFailureIsNotGenerationFailure() = modelTest {
        runCurrent(); vm.prompt("mountain"); vm.submit(); runCurrent()
        repo.onJob = { throw ComfyFailure("forbidden") }; advanceTimeBy(3000); runCurrent()
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
    @Test fun deleteRemovesFromHistoryOnlyAfterTheServerConfirms() = modelTest {
        val done = ComfyJob(id, "image", "succeeded")
        repo.history = listOf(done, ComfyJob("22345678-1234-1234-1234-123456789012", "image", "failed"))
        runCurrent(); repo.onJob = { done }; vm.open(done); runCurrent()
        repo.deleteFails = "unpublish_failed"
        assertEquals("unpublish_failed", runCatching { vm.delete(done) }.exceptionOrNull()?.let { (it as ComfyFailure).code })
        assertEquals(2, vm.state.value.jobs.size)
        repo.deleteFails = null; vm.delete(done)
        assertEquals(listOf(id), repo.deletions)
        assertEquals(listOf("22345678-1234-1234-1234-123456789012"), vm.state.value.jobs.map { it.requestId })
        assertNull(vm.state.value.selected)
    }
    @Test fun openingHistoryOnlyReadsAndNeverChangesDraft() = modelTest {
        runCurrent(); vm.prompt("keep me"); repo.onJob = { ComfyJob(it, "video", "succeeded") }
        vm.open(ComfyJob(id, "video", "succeeded")); runCurrent()
        assertEquals("keep me", vm.state.value.prompt); assertEquals(listOf(id), repo.reads); assertTrue(repo.submissions.isEmpty())
    }
    // Comfy Cloud's model list is incomplete, so neither a cold nor an incomplete inventory blocks generation.
    @Test fun resourcesAreLazyAndInventoryNeverBlocksGeneration() = modelTest {
        runCurrent(); assertEquals(0, repo.resourceCalls)
        repo.catalog = listOf(workflow.copy(requiredModels = listOf("checkpoints" to "base")))
        repo.inventory = ComfyResources(emptyList(), false, true, true); vm.refresh(); runCurrent(); vm.prompt("mountain")
        assertEquals(1, repo.resourceCalls); assertTrue(vm.state.value.canSubmit)
        repo.inventory = ComfyResources(listOf(ComfyResource("checkpoints", "other", null)), true, false, false)
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
    @Test fun galleryResourcesLoadEvenWhenCurrentChannelIsApi() = modelTest {
        runCurrent(); vm.choose("api")
        repo.catalog = repo.catalog + workflow.copy(id = "gpu-resource", requiredModels = listOf("vae" to "base"))
        vm.refresh(); runCurrent()
        assertEquals("api", vm.state.value.workflowId); assertEquals("partner_api", vm.state.value.channel)
        assertEquals(1, repo.resourceCalls)
    }
    @Test fun activeHistoryAndFinishingJobsPollFairlyWithoutReordering() = modelTest {
        runCurrent()
        val second = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        repo.history = listOf(ComfyJob(id, "image", "running"), ComfyJob(second, "video", "succeeded"))
        repo.onJob = { key -> repo.history.first { it.requestId == key } }
        vm.refresh(); runCurrent(); assertEquals(listOf(id), repo.reads)
        advanceTimeBy(3000); runCurrent(); assertEquals(listOf(id, second), repo.reads)
        repo.onJob = { key -> ComfyJob(key, "image", "succeeded", outputs = listOf(ComfyOutput(0, "image/png", 1))) }
        advanceTimeBy(3000); runCurrent()
        assertEquals(listOf(id, second), vm.state.value.jobs.map { it.requestId })
        assertEquals(ComfyJobPhase.COMPLETE, vm.state.value.jobs.first().phase())
        vm.setForeground(false); advanceTimeBy(9000); runCurrent(); assertEquals(3, repo.reads.size)
        vm.setForeground(true); runCurrent(); assertEquals(second, repo.reads.last())
        assertTrue(repo.submissions.isEmpty())
    }
    @Test fun hiddenLateHistoryLoadCannotRestartPollingButReturnResumes() = modelTest {
        runCurrent(); val late = CompletableDeferred<List<ComfyJob>>()
        repo.onHistory = { late.await() }; vm.refresh(); runCurrent(); vm.setForeground(false)
        late.complete(listOf(ComfyJob(id, "image", "running"))); runCurrent()
        assertTrue(repo.reads.isEmpty()); assertFalse(vm.state.value.polling)
        vm.setForeground(true); runCurrent(); assertEquals(listOf(id), repo.reads)
    }
    @Test fun hidingCancelsInFlightGetAndIgnoresNonCancellableLateResult() = modelTest {
        runCurrent(); val late = CompletableDeferred<ComfyJob>(); var canceled = false
        repo.onJob = { try { awaitCancellation() } finally { canceled = true } }
        vm.open(ComfyJob(id, "image", "running")); runCurrent(); vm.setForeground(false); runCurrent()
        assertTrue(canceled); assertFalse(vm.state.value.checking)
        repo.onJob = { withContext(NonCancellable) { late.await() } }
        vm.setForeground(true); runCurrent(); vm.setForeground(false)
        late.complete(ComfyJob(id, "image", "succeeded")); runCurrent()
        assertEquals("running", vm.state.value.selected?.status); assertFalse(vm.state.value.polling)
    }
    @Test fun openingCompletedHistoryDoesNotStrandPendingPoll() = modelTest {
        runCurrent(); vm.prompt("mountain"); vm.submit(); runCurrent()
        val pendingId = pending.id!!
        repo.onJob = { key -> if (key == id) ComfyJob(key, "image", "succeeded", outputs = listOf(ComfyOutput(0, "image/png", 1))) else ComfyJob(key, "image", "running") }
        vm.open(ComfyJob(id, "image", "succeeded")); runCurrent()
        advanceTimeBy(3000); runCurrent(); assertEquals(listOf(id, pendingId), repo.reads)
        assertEquals(id, vm.state.value.selected?.requestId)
    }
    @Test fun finishingPollKeepsLegacyPendingClearAndWaitsForPartialOutputs() = modelTest {
        runCurrent(); repo.onSubmit = { ComfyJob(it.requestId, it.workflowId, "succeeded", it.parameters, listOf(ComfyOutput(0, "image/png", 1)), "output_unavailable") }
        vm.prompt("mountain"); vm.submit(); runCurrent()
        assertNull(pending.id); assertTrue(vm.state.value.polling)
        val submitted = repo.submissions.single().requestId
        repo.onJob = { ComfyJob(it, "image", "succeeded", outputs = listOf(ComfyOutput(0, "image/png", 1))) }
        advanceTimeBy(3000); runCurrent()
        assertEquals(listOf(submitted), repo.reads); assertFalse(vm.state.value.polling)
    }
    @Test fun againUsesOriginalTypedParametersNewSeedAndSingleDurableRequest() = modelTest {
        runCurrent()
        val seed = ComfyInput("seed", buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 2) })
        val flag = ComfyInput("enabled", buildJsonObject { put("type", "boolean") })
        val asset = ComfyInput("image", buildJsonObject { put("type", "asset") })
        repo.catalog = listOf(workflow.copy(id = "api", channel = "partner_api", inputs = workflow.inputs + listOf(seed, flag, asset)))
        vm.refresh(); runCurrent(); vm.prompt("unsaved"); vm.parameter("steps", "1")
        val params = buildJsonObject { put("prompt", "original"); put("steps", 20); put("seed", 1); put("enabled", false); putJsonObject("image") { put("job_id", "upstream-job"); put("output_index", 0) } }
        val source = ComfyJob(id, "api", "succeeded", params)
        assertTrue(vm.again(source)); assertFalse(vm.again(source)); runCurrent()
        val request = repo.submissions.single()
        assertNotEquals(id, request.requestId); assertEquals(request.requestId, pending.id)
        assertEquals(params - "seed", request.parameters - "seed"); assertEquals(2L, request.parameters.long("seed"))
        assertEquals("original", vm.state.value.prompt); assertEquals("partner_api", vm.state.value.channel)
        assertEquals(1L, source.parameters.long("seed"))
    }
    @Test fun seedlessAgainDoesNotAddDefaultsAndRemovedWorkflowNeverFallsBack() = modelTest {
        runCurrent()
        val source = ComfyJob(id, "image", "succeeded", buildJsonObject { put("prompt", "original") })
        assertFalse(vm.again(source.copy(workflowId = "removed"))); assertEquals("invalid_workflow", vm.state.value.error)
        assertTrue(vm.again(source)); runCurrent(); assertEquals(source.parameters, repo.submissions.single().parameters)
    }
    @Test fun editPrefillsCrossChannelDraftAndCancelsRecommendationsWithoutSubmitting() = modelTest {
        runCurrent(); vm.prompt("mountain")
        // Typing never bumps the revision; only an external replacement (Edit) does.
        assertEquals(0, vm.state.value.promptRevision)
        val source = ComfyJob(id, "api", "succeeded", buildJsonObject { put("prompt", "old prompt"); put("steps", 7); put("unknown", "drop") })
        assertTrue(vm.edit(source)); advanceTimeBy(1000); runCurrent()
        assertEquals(1, vm.state.value.promptRevision)
        assertEquals("old prompt", vm.state.value.prompt); assertEquals("7", vm.state.value.values["steps"])
        assertEquals("partner_api", vm.state.value.channel); assertFalse(vm.state.value.values.containsKey("unknown"))
        assertEquals(0, repo.recommendations); assertTrue(repo.submissions.isEmpty())
        assertFalse(vm.edit(source.copy(workflowId = "removed")))
        assertEquals("api", vm.state.value.workflowId)
    }
    @Test fun rejectedAndUnknownSubmissionsReplaceGridPlaceholder() = modelTest {
        runCurrent(); vm.prompt("mountain"); repo.onSubmit = { throw ComfyFailure("invalid_parameters") }
        vm.submit(); runCurrent(); assertEquals("failed", vm.state.value.jobs.first().status)
        assertFalse(vm.state.value.jobs.first().needsPolling())
        repo.onSubmit = { throw ComfyFailure("network") }; vm.submit(); runCurrent()
        assertEquals("unknown", vm.state.value.jobs.first().status); assertFalse(vm.state.value.polling)
    }
    @Test fun resourceSelectionsMustExistBeforeCanSubmitAndAgain() = modelTest {
        runCurrent()
        val loader = ComfyInput("checkpoint", buildJsonObject { put("resource", "checkpoints"); put("required", true); put("default", "base") })
        repo.catalog = listOf(workflow.copy(inputs = workflow.inputs + loader)); vm.refresh(); runCurrent(); vm.prompt("mountain")
        assertFalse(vm.state.value.canSubmit)
        val source = ComfyJob(id, "image", "succeeded", buildJsonObject { put("prompt", "old"); put("steps", 20); put("checkpoint", "base") })
        assertFalse(vm.again(source)); assertTrue(repo.submissions.isEmpty())
    }
    @Test fun enabledKaggleIsPreferredInitiallyWithoutCloudResourcePrerequisite() = modelTest {
        val free = workflow.copy(id = "qwen-image-21-kaggle", channel = "kaggle_gpu", available = true, requiredModels = listOf("diffusion_models" to "qwen"))
        runCurrent(); vm.setForeground(false)
        repo = FakeComfy().apply { catalog = listOf(workflow, free) }
        vm = create(repo, pending); vm.setForeground(true); runCurrent(); vm.prompt("mountain")
        assertEquals(free.id, vm.state.value.workflowId); assertEquals("kaggle_gpu", vm.state.value.channel)
        assertTrue(vm.state.value.canSubmit); assertFalse(vm.state.value.needsResources); assertEquals(0, repo.resourceCalls)
        vm.filter("image", "cloud_gpu"); vm.refresh(); runCurrent()
        assertEquals("cloud_gpu", vm.state.value.channel); assertEquals(workflow.id, vm.state.value.workflowId)
        assertEquals("mountain", vm.state.value.prompt)
        vm.filter("image", "partner_api"); vm.refresh(); runCurrent()
        assertEquals("partner_api", vm.state.value.channel); assertNull(vm.state.value.workflowId)
    }
    @Test fun kaggleMustBeExplicitlyAvailableBeforeItReplacesInitialCloudChoice() = modelTest {
        repo.catalog = listOf(workflow, workflow.copy(id = "free", channel = "kaggle_gpu")); runCurrent()
        assertEquals("cloud_gpu", vm.state.value.channel)
        repo.catalog = listOf(workflow, workflow.copy(id = "free", channel = "kaggle_gpu", available = false, unavailable = "kaggle_unavailable"))
        vm.refresh(); runCurrent(); vm.filter("image", "kaggle_gpu"); vm.prompt("mountain")
        assertFalse(vm.state.value.canSubmit); assertEquals("kaggle_gpu", vm.state.value.channel)
    }
    @Test fun coldStartPollsSameIdentityAndFailureNeverSubmitsToCloud() = modelTest {
        runCurrent(); vm.setForeground(false)
        repo = FakeComfy().apply { catalog = listOf(workflow, workflow.copy(id = "free", channel = "kaggle_gpu", available = true)) }
        vm = create(repo, pending); vm.setForeground(true); runCurrent()
        repo.onSubmit = { ComfyJob(it.requestId, it.workflowId, "queued", it.parameters, billingChannel = "kaggle_gpu", workerState = "launching", queuePosition = 2) }
        vm.prompt("mountain"); vm.submit(); runCurrent()
        val request = repo.submissions.single(); assertEquals("free", request.workflowId)
        assertEquals(ComfyJobPhase.STARTING, vm.state.value.selected?.phase()); assertTrue(vm.state.value.polling)
        repo.onJob = { ComfyJob(it, "free", "failed", errorCode = "kaggle_wait_timeout", billingChannel = "kaggle_gpu", workerState = "failed") }
        advanceTimeBy(3000); runCurrent(); advanceTimeBy(30_000); runCurrent()
        assertEquals(listOf(request.requestId), repo.reads); assertEquals(1, repo.submissions.size)
        assertEquals("kaggle_wait_timeout", vm.state.value.error); assertNull(pending.id); assertFalse(vm.state.value.polling)
        assertEquals("kaggle_gpu", vm.state.value.channel)
        vm.filter("image", "cloud_gpu"); assertTrue(vm.state.value.canSubmit); assertEquals(1, repo.submissions.size)
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
        var history = emptyList<ComfyJob>()
        var onHistory: (suspend () -> List<ComfyJob>)? = null
        override suspend fun jobs() = onHistory?.invoke() ?: history
        var savedJobs = emptyList<ComfyJob>(); var savedWorkflows = emptyList<ComfyWorkflow>()
        override suspend fun cachedJobs() = savedJobs
        override suspend fun cachedWorkflows() = savedWorkflows
        override suspend fun job(requestId: String): ComfyJob { reads += requestId; return onJob(requestId) }
        override suspend fun resources(refresh: Boolean): ComfyResources { resourceCalls++; return inventory }
        override suspend fun download(requestId: String, output: ComfyOutput, destination: File, onProgress: (Long, Long) -> Unit) { downloads++; destination.writeText("test") }
        val deletions = mutableListOf<String>(); var deleteFails: String? = null
        override suspend fun delete(requestId: String) { deleteFails?.let { throw ComfyFailure(it) }; deletions += requestId }
        val uploads = mutableListOf<Pair<String, Int>>()
        var onUpload: suspend (ByteArray, String) -> String = { _, _ -> throw ComfyFailure("unavailable") }
        override suspend fun uploadInput(bytes: ByteArray, mime: String) = onUpload(bytes, mime)
    }
}
