package com.karewinkcloud.agentweb.client.core

import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Assert.*

class ComfySchemaTest {
    private fun workflow(inputs: String) = ComfyWorkflow.from(wireJson.parseToJsonElement("""{"id":"test","title":"Test","kind":"image","billing_channel":"cloud_gpu","inputs":$inputs}""").jsonObject)
    @Test fun fieldsRetainTypedDefaultsAndOptionalEmptyText() {
        val workflow = workflow("""{"prompt":{"type":"string","required":true,"min_length":1},"steps":{"type":"integer","default":20,"minimum":1,"maximum":30},"negative_prompt":{"type":"string","default":"blur"},"loop":{"type":"boolean","default":true}}""")
        val result = workflow.parameters(workflow.defaults() + mapOf("prompt" to "山水", "negative_prompt" to "", "loop" to "false"), null)
        assertEquals(20L, result.long("steps")); assertEquals("", result.string("negative_prompt")); assertEquals(false, result.boolean("loop"))
    }
    @Test fun invalidBoundsEnumsAndRequiredFieldsFailBeforeSubmission() {
        val workflow = workflow("""{"prompt":{"type":"string","required":true,"max_length":4},"steps":{"type":"integer","minimum":1,"maximum":30},"style":{"type":"string","enum":["a","b"]}}""")
        for (values in listOf(mapOf("prompt" to " "), mapOf("prompt" to "12345"), mapOf("steps" to "31"), mapOf("steps" to "NaN"), mapOf("steps" to "1.5"), mapOf("style" to "c"))) {
            try { workflow.parameters(mapOf("prompt" to "山水", "steps" to "20", "style" to "a") + values, null); fail() }
            catch (e: ComfyFailure) { assertEquals("invalid_parameters", e.code) }
        }
    }
    @Test fun resourceRequiresLoadedMatchingInventory() {
        val workflow = workflow("""{"lora":{"type":"string","resource":"loras","required":true}}""")
        for (inventory in listOf(null, ComfyResources(emptyList(), true, false, false))) {
            try { workflow.parameters(mapOf("lora" to "detail"), inventory); fail() } catch (e: ComfyFailure) { assertEquals("lora", e.field) }
        }
        assertEquals("detail", workflow.parameters(mapOf("lora" to "detail"), ComfyResources(listOf(ComfyResource("loras", "detail", "sdxl")), true, false, false)).string("lora"))
    }
    @Test fun sizePresetsKeepNonSquareWorkflowDefault() {
        val workflow = workflow("""{"width":{"type":"integer","default":1344,"enum":[768,1344]},"height":{"type":"integer","default":768,"enum":[768,1344]}}""")
        assertEquals("1344", workflow.defaults()["width"])
        assertTrue(workflow.sizes().any { it.label == "7:4 · 1344 × 768" && it.parameters == mapOf("width" to "1344", "height" to "768") })
    }
    @Test fun videoSizesStayWithinTheModelFrameAndUseFamiliarRatios() {
        val video = ComfyWorkflow.from(wireJson.parseToJsonElement("""{"id":"fasth3","kind":"video","billing_channel":"runpod_gpu","inputs":{
            "duration":{"type":"integer","default":5,"enum":[5,8,10]},
            "width":{"type":"integer","default":1344,"enum":[768,1024,1344]},"height":{"type":"integer","default":768,"enum":[768,1024,1344]}}}""").jsonObject)
        assertEquals(listOf("16:9 · 1344 × 768", "9:16 · 768 × 1344", "1:1 · 768 × 768", "4:3 · 1024 × 768", "3:4 · 768 × 1024"),
            video.sizes().map { it.label })  // 1024 × 1024 and larger exceed the 1344 × 768 budget
        assertEquals("8", video.parameters(video.defaults() + ("duration" to "8"), null).let { (it["duration"] as JsonPrimitive).content })
        try { video.parameters(video.defaults() + ("duration" to "15"), null); fail() } catch (e: ComfyFailure) { assertEquals("duration", e.field) }
        // Image workflows keep exact ratios and every combination.
        val image = workflow("""{"width":{"type":"integer","default":1344,"enum":[768,1024,1344]},"height":{"type":"integer","default":768,"enum":[768,1024,1344]}}""")
        assertEquals(9, image.sizes().size)
    }
    @Test fun jobTimingParsesServerTimesAndIgnoresJunk() {
        fun job(extra: String) = ComfyJob.from(wireJson.parseToJsonElement("""{"request_id":"12345678-1234-1234-1234-123456789012","status":"succeeded"$extra}""").jsonObject)
        val done = job(""","created_at":1791212704,"finished_at":1791213494,"timing":{"queue_ms":61000,"run_ms":216487}""")
        assertEquals(790L, done.totalSeconds)
        assertEquals(61000L, done.queueMs); assertEquals(216487L, done.runMs)
        assertEquals(1791212704L, job(""","created_at":1791212704.75""").createdAt)  // Kaggle stores REAL seconds
        val junk = job(""","created_at":"soon","finished_at":5,"timing":{"queue_ms":-1,"run_ms":"x"}""")
        assertNull(junk.createdAt); assertNull(junk.finishedAt); assertNull(junk.totalSeconds); assertNull(junk.queueMs); assertNull(junk.runMs)
        assertNull(job(""","created_at":1791213494,"finished_at":1791212704""").totalSeconds)  // never negative
    }
    @Test fun unclassifiedBillingNeverBecomesCloudGpu() {
        val workflow = ComfyWorkflow.from(wireJson.parseToJsonElement("""{"id":"unknown","inputs":{}}""").jsonObject)
        assertEquals("unknown", workflow.channel)
    }
}
