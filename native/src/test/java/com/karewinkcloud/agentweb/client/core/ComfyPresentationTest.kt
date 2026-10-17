package com.karewinkcloud.agentweb.client.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class ComfyPresentationTest {
    private fun workflow(id: String = "sdxl", channel: String = "cloud_gpu", fields: String = "{}") =
        ComfyWorkflow.from(wireJson.parseToJsonElement("""{"id":"$id","title":"$id","kind":"image","billing_channel":"$channel","inputs":$fields}""").jsonObject)
    private val output = ComfyOutput(0, "image/png", 20)
    private fun job(parameters: String = "{}", workflowId: String = "sdxl") = ComfyJob("12345678-1234-1234-1234-123456789012", workflowId, "succeeded", wireJson.parseToJsonElement(parameters).jsonObject)

    @Test fun galleryGroupsChannelThenFamilyAndKeepsCatalogOrder() {
        val workflows = listOf(workflow("flux2", "partner_api"), workflow("sdxl-second"), workflow("sdxl-first"), workflow("qwen"), workflow("unknown", "other"))
        val groups = comfyGalleryGroups(workflows, "image", "")
        assertEquals(listOf("Qwen", "SDXL", "FLUX", ""), groups.map { it.family })
        assertEquals(listOf("sdxl-second", "sdxl-first"), groups[1].workflows.map { it.id })
        assertEquals(listOf("cloud_gpu", "cloud_gpu", "partner_api", "other"), groups.map { it.channel })
        assertTrue(comfyGalleryGroups(workflows, "audio", "").isEmpty())
    }
    @Test fun gallerySearchesIdTitleDescriptionAndBrandedFamily() {
        val item = workflow("custom").copy(title = "Example", description = "Soft light", inputs = listOf(ComfyInput("loader", buildJsonObject { put("family", "flux1") })))
        for (query in listOf(" custom ", "EXAMPLE", "light", "FLUX")) assertEquals(listOf(item), comfyGalleryGroups(listOf(item), "image", query).single().workflows)
        assertTrue(comfyGalleryGroups(listOf(item), "image", "unmatched").isEmpty())
    }
    @Test fun availabilityUsesExactCategoryAndNameAndKeepsStaleInventory() {
        val item = workflow().copy(requiredModels = listOf("vae" to "base", "loras" to "detail"))
        assertEquals(ComfyAvailabilityKind.AVAILABLE, item.availability(null).kind)
        val inventory = ComfyResources(listOf(ComfyResource("checkpoints", "base", null)), true, false, true)
        assertEquals(listOf("base", "detail"), item.availability(inventory).missingModels)
        assertEquals(ComfyAvailabilityKind.AVAILABLE, item.availability(inventory).kind)
        val ready = inventory.copy(items = listOf(ComfyResource("vae", "base", null), ComfyResource("loras", "detail", null)))
        assertEquals(ComfyAvailabilityKind.AVAILABLE, item.availability(ready).kind)
        assertEquals(ComfyAvailabilityKind.AVAILABLE, item.copy(channel = "partner_api").availability(null).kind)
        assertEquals(ComfyAvailabilityKind.UNAVAILABLE, item.copy(unavailable = "disabled").availability(null).kind)
    }
    @Test fun fieldPartitionIsCompleteAndPrioritizesCreativeControls() {
        val item = workflow(fields = """{"checkpoint":{"resource":"checkpoints"},"seed":{"type":"integer"},"prompt":{},"negative_prompt":{},"lora":{"resource":"loras"},"strength_clip":{"type":"number"},"steps":{"type":"integer"},"guidance":{"type":"number"},"width":{"type":"integer"},"image":{"type":"asset"},"rare_option":{},"vae":{"resource":"vae"}}""")
        val groups = item.fieldGroups()
        assertEquals(listOf("width", "image", "negative_prompt", "lora", "strength_clip", "guidance", "steps", "seed"), groups.basic.map { it.key })
        assertEquals(listOf("checkpoint", "rare_option", "vae"), groups.advanced.map { it.key })
        assertEquals(item.inputs.map { it.key }.filterNot { it == "prompt" }.sorted(), (groups.basic + groups.advanced).map { it.key }.sorted())
    }
    @Test fun textPrimaryPromptIsExcludedButSecondaryTextIsBasic() {
        assertTrue(workflow(fields = """{"text":{}}""").fieldGroups().basic.isEmpty())
        assertEquals("text", workflow(fields = """{"prompt":{},"text":{}}""").fieldGroups().basic.single().key)
    }
    @Test fun slidersClampSnapAndNeverEmitScientificNotation() {
        val range = ComfySliderRange(-10.0, 10.0, true)
        assertEquals("-10", range.value(-1f)); assertEquals("10", range.value(2f)); assertEquals("1", range.value(.55f))
        assertEquals("-10", range.value(Float.NaN)); assertEquals("10", range.value(Float.POSITIVE_INFINITY))
        assertEquals(0f, range.fraction("NaN")); assertEquals(0f, range.fraction("Infinity")); assertEquals(1f, range.fraction("100"))
        val decimal = ComfySliderRange(-.5, 1.5, false)
        assertEquals("0.5", decimal.value(.5f))
        assertEquals("0.3", ComfySliderRange(0.0, 1.0, false).value(.3f))
        assertEquals(.34f, decimal.fraction(decimal.value(.34f)), .000001f)
        assertFalse(ComfySliderRange(0.0, 9007199254740991.0, true).value(.8f).contains("E"))
    }
    @Test fun sliderRequiresFiniteOrderedBoundsAndNumericNonEnum() {
        fun input(type: String, low: String, high: String, extra: String = "") = ComfyInput("x", wireJson.parseToJsonElement("""{"type":"$type","minimum":$low,"maximum":$high$extra}""").jsonObject)
        assertNotNull(input("number", "-1", "1").sliderRange())
        assertNull(input("string", "0", "1").sliderRange()); assertNull(input("integer", "1", "1").sliderRange())
        assertNull(input("number", "2", "1").sliderRange()); assertNull(input("number", "0", "1e999").sliderRange())
        assertNull(input("integer", "0", "1", """, "enum":[0,1]""").sliderRange())
        assertNull(input("integer", "0.5", "1.5").sliderRange())
        val huge = input("integer", "0", "9223372036854775807").sliderRange()!!
        assertEquals("9223372036854775807", huge.value(1f))
        assertEquals(1f, huge.fraction("9223372036854775807"))
    }
    @Test fun phasesIncludeFinishingAndCancelingWithoutInventingProgress() {
        for (status in listOf("pending", "submitting", "queued")) assertEquals(ComfyJobPhase.QUEUED, job().copy(status = status).phase())
        assertEquals(ComfyJobPhase.RUNNING, job().copy(status = "canceling").phase())
        assertTrue(job().copy(status = "canceling").needsPolling())
        assertEquals(ComfyJobPhase.FINISHING, job().phase())
        assertEquals(ComfyJobPhase.FINISHING, job().copy(outputs = listOf(output), errorCode = "output_unavailable").phase())
        assertEquals(ComfyJobPhase.COMPLETE, job().copy(outputs = listOf(output)).phase())
        assertFalse(job().copy(outputs = listOf(output)).needsPolling())
        assertEquals(ComfyJobPhase.FAILED, job().copy(status = "failed").phase())
        assertEquals(ComfyJobPhase.UNKNOWN, job().copy(status = "query_failed").phase())
        for (progress in listOf("", ",\"progress\":-1", ",\"progress\":2", ",\"progress\":1e999", ",\"progress\":null")) {
            assertNull(ComfyJob.from(wireJson.parseToJsonElement("""{"request_id":"${job().requestId}","status":"running"$progress}""").jsonObject).progress)
        }
    }
    @Test fun prefillKeepsTypedValuesAssetsAndDefaultsButDropsUnknownKeys() {
        val item = workflow(fields = """{"prompt":{"default":"default"},"seed":{"type":"integer","default":5},"enabled":{"type":"boolean"},"image":{"type":"asset"},"steps":{"default":20}}""")
        val draft = item.prefill(job("""{"prompt":"山水","seed":123,"enabled":false,"image":{"job_id":"upstream-id","output_index":1},"unknown":"x"}"""))
        assertEquals("山水", draft.prompt); assertEquals("false", draft.values["enabled"]); assertEquals("20", draft.values["steps"])
        assertEquals("{\"job_id\":\"upstream-id\",\"output_index\":1}", draft.values["image"]); assertFalse(draft.values.containsKey("unknown"))
        try { item.prefill(job(workflowId = "removed")); fail() } catch (e: ComfyFailure) { assertEquals("invalid_workflow", e.code) }
    }
    @Test fun againCopiesOriginalParametersAndChangesOnlyBoundedSeed() {
        val item = workflow(fields = """{"prompt":{},"seed":{"type":"integer","minimum":-2,"maximum":2},"steps":{"default":20}}""")
        val source = job("""{"prompt":"keep","seed":0,"unknown":true}""")
        for (i in 0..20) {
            val result = item.againParameters(source, Random(i))
            assertNotEquals(0L, result.long("seed")); assertTrue(result.long("seed")!! in -2L..2L)
            assertEquals(source.parameters - "seed", result - "seed")
        }
        assertEquals(0L, source.parameters.long("seed"))
        assertEquals(source.parameters, workflow().againParameters(source))
    }
    @Test fun seedHonorsEnumSingletonAndExactLargeIntegerBounds() {
        val enum = workflow(fields = """{"seed":{"type":"integer","enum":[1,3]}}""")
        assertEquals(3L, enum.againParameters(job("""{"seed":1}"""), Random(0)).long("seed"))
        val singleton = workflow(fields = """{"seed":{"type":"integer","minimum":3,"maximum":3}}""")
        assertEquals(3L, singleton.againParameters(job("""{"seed":3}"""), Random(0)).long("seed"))
        val huge = workflow(fields = """{"seed":{"type":"integer","minimum":9223372036854775806,"maximum":9223372036854775807}}""")
        assertEquals(Long.MAX_VALUE, huge.againParameters(job("""{"seed":9223372036854775806}"""), Random(0)).long("seed"))
    }
    @Test fun assetsUseUpstreamIdsAndRejectExtraKeysAndInvalidReferences() {
        val item = workflow(fields = """{"image":{"type":"asset","required":true}}""")
        assertEquals("upstream-job_1", item.parameters(mapOf("image" to """{"job_id":"upstream-job_1","output_index":0}"""), null).obj("image")?.string("job_id"))
        for (value in listOf("""{"job_id":123,"output_index":0}""", """{"job_id":"ok","output_index":"0"}""", """{"job_id":"../secret","output_index":0}""", """{"job_id":"ok","output_index":64}""", """{"job_id":"ok","output_index":0,"url":"extra"}""")) {
            try { item.parameters(mapOf("image" to value), null); fail() } catch (e: ComfyFailure) { assertEquals("image", e.field) }
        }
    }
    @Test fun uploadedPhotoIsAcceptedOnlyAsAServerUploadId() {
        val item = workflow(fields = """{"image":{"type":"asset","required":true}}""")
        val upload = "0123456789abcdef0123456789abcdef"
        assertEquals(upload, item.parameters(mapOf("image" to """{"upload_id":"$upload"}"""), null).obj("image")?.string("upload_id"))
        for (value in listOf("""{"upload_id":"0123456789ABCDEF0123456789ABCDEF"}""", """{"upload_id":"abc"}""", """{"upload_id":123}""",
                "{\"upload_id\":\"$upload\",\"job_id\":\"ok\"}", """{"upload_id":"../../0123456789abcdef0123456789"}""")) {
            try { item.parameters(mapOf("image" to value), null); fail(value) } catch (e: ComfyFailure) { assertEquals("image", e.field) }
        }
    }
}
