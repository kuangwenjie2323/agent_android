package com.karewinkcloud.agentweb.client.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ComfyKaggleTest {
    private val id = "12345678-1234-1234-1234-123456789012"
    private fun job(extra: String = "") = ComfyJob.from(wireJson.parseToJsonElement("""{
        "request_id":"$id","workflow_id":"qwen-image-21-kaggle","status":"queued",
        "billing_channel":"kaggle_gpu","worker_state":"launching"$extra} """).jsonObject)
    private fun workflow(extra: String = "") = ComfyWorkflow.from(wireJson.parseToJsonElement("""{
        "id":"qwen-image-21-kaggle","title":"Qwen","billing_channel":"kaggle_gpu","kind":"image",
        "required_model_files":[{"category":"diffusion_models","name":"qwen.safetensors"}],
        "inputs":{"prompt":{"type":"string","required":true}}$extra} """).jsonObject)

    @Test fun parserKeepsLaunchAndPositionWithoutChangingDurableJobStatus() {
        val parsed = job(",\"queue_position\":3")
        assertEquals("queued", parsed.status); assertEquals("kaggle_gpu", parsed.billingChannel)
        assertEquals("launching", parsed.workerState); assertEquals(3, parsed.queuePosition)
        assertEquals(ComfyJobPhase.STARTING, parsed.phase()); assertTrue(parsed.needsPolling())
        assertEquals(3, parsed.waitingQueuePosition())
    }
    @Test fun malformedPositionsAreNeverPresentedAsAQueueRank() {
        for (value in listOf("null", "0", "-1", "1.5", "2147483648", "1e999", "\"3\"", "true")) {
            assertNull(value, job(",\"queue_position\":$value").queuePosition)
        }
        assertNull(job().queuePosition)
        assertEquals(Int.MAX_VALUE, job(",\"queue_position\":2147483647").queuePosition)
    }
    @Test fun coldStartOnlyAppliesToQueuedKaggleWhileTerminalTruthWins() {
        val starting = job(",\"queue_position\":2")
        for (workerState in listOf("idle", "running", "draining", "stopped", null))
            assertEquals(ComfyJobPhase.QUEUED, starting.copy(workerState = workerState).phase())
        for (channel in listOf("cloud_gpu", "partner_api", null)) {
            assertEquals(ComfyJobPhase.QUEUED, starting.copy(billingChannel = channel).phase())
            assertNull(starting.copy(billingChannel = channel).waitingQueuePosition())
        }
        assertEquals(ComfyJobPhase.RUNNING, starting.copy(status = "running").phase())
        assertNull(starting.copy(status = "running").waitingQueuePosition())
        val failure = starting.copy(status = "failed", errorCode = "kaggle_launch_failed")
        assertEquals(ComfyJobPhase.FAILED, failure.phase()); assertFalse(failure.needsPolling())
        assertNull(failure.waitingQueuePosition())
    }
    @Test fun oldCloudReceiptsStillParseAndPollWithoutNewFields() {
        val parsed = ComfyJob.from(wireJson.parseToJsonElement("""{"request_id":"$id","status":"queued"}""").jsonObject)
        assertNull(parsed.billingChannel); assertNull(parsed.workerState); assertNull(parsed.queuePosition)
        assertEquals(ComfyJobPhase.QUEUED, parsed.phase()); assertTrue(parsed.needsPolling())
    }
    @Test fun enabledFlagIsExplicitAndKaggleWeightsDoNotNeedCloudInventory() {
        val ready = workflow(",\"available\":true")
        assertEquals(true, ready.available); assertEquals(ComfyAvailabilityKind.AVAILABLE, ready.availability(null).kind)
        assertTrue(ready.missingModels(null).isEmpty())
        assertEquals("mountain", ready.parameters(mapOf("prompt" to "mountain"), null).string("prompt"))
        assertNull(workflow().available)
        val disabled = workflow(",\"available\":false,\"unavailable_reason\":\"kaggle_unavailable\"")
        assertEquals(false, disabled.available); assertEquals(ComfyAvailabilityKind.UNAVAILABLE, disabled.availability(null).kind)
        try { disabled.parameters(mapOf("prompt" to "mountain"), null); fail() } catch (e: ComfyFailure) { assertEquals("invalid_workflow", e.code) }
    }
    @Test fun galleryKeepsKaggleSeparateAndListsItFirst() {
        val free = workflow(",\"available\":true")
        val groups = comfyGalleryGroups(listOf(free.copy(id = "cloud", channel = "cloud_gpu"), free.copy(id = "api", channel = "partner_api"), free), "image", "")
        assertEquals(listOf("kaggle_gpu", "cloud_gpu", "partner_api"), groups.map { it.channel })
        assertEquals(free, groups.first().workflows.single())
    }
    @Test fun localKaggleOutputsAreNotCloudAssetReferences() {
        val source = job().copy(status = "succeeded", jobId = "local-job", outputs = listOf(ComfyOutput(0, "image/png", 10)))
        assertFalse(source.canReferenceInCloud(emptyList()))
        assertFalse(source.copy(billingChannel = null).canReferenceInCloud(listOf(workflow())))
        val cloud = source.copy(workflowId = "cloud", billingChannel = "cloud_gpu")
        assertTrue(cloud.canReferenceInCloud(emptyList()))
        assertFalse(cloud.copy(jobId = null).canReferenceInCloud(emptyList()))
        assertFalse(cloud.copy(status = "running").canReferenceInCloud(emptyList()))
        assertEquals(ComfyJobPhase.COMPLETE, source.phase())
    }
}
