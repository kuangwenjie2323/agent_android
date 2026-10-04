package com.karewinkcloud.agentweb.client.core

import kotlinx.serialization.json.*
import java.util.UUID

val comfyActive = setOf("pending", "submitting", "queued", "running", "canceling")
val comfyTerminal = setOf("succeeded", "failed", "error", "canceled", "expired")
// Only these answered rejection codes establish that no generation was dispatched.
val comfyRejected = setOf("invalid_request", "invalid_workflow", "invalid_parameters", "configuration_required",
    "client_setup_failed", "conflict", "too_large", "busy", "capacity", "not_found", "rate_limited", "file_exists")
class ComfyFailure(val code: String, val field: String? = null) : Exception(code)
fun validComfyId(id: String) = id.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))

fun validComfyJobId(id: String) = id.matches(Regex("[A-Za-z0-9_-]{1,128}"))

data class ComfyInput(val key: String, val spec: JsonObject) {
    val type get() = spec.string("type") ?: "string"
    val title get() = spec.string("title") ?: key
    val required get() = spec.boolean("required") == true || (spec.long("min_length") ?: 0) > 0
    val resource get() = spec.string("resource")
    val family get() = spec.string("family")
    val options get() = spec.array("enum")
    val default get() = spec["default"]?.let { if (it is JsonPrimitive) it.contentOrNull else it.toString() }.orEmpty()
    val min get() = (spec["minimum"] as? JsonPrimitive)?.doubleOrNull
    val max get() = (spec["maximum"] as? JsonPrimitive)?.doubleOrNull
}
data class ComfySize(val label: String, val parameters: Map<String, String>)
data class ComfyWorkflow(val id: String, val title: String, val description: String, val kind: String,
    val channel: String, val inputs: List<ComfyInput>, val unavailable: String?, val requiredModels: List<Pair<String, String>>,
    val available: Boolean? = null) {
    val promptKey get() = inputs.firstOrNull { it.key == "prompt" }?.key
        ?: inputs.firstOrNull { it.key == "text" }?.key
    fun defaults() = inputs.associate { it.key to it.default }
    fun missingModels(resources: ComfyResources?) = if (channel != "cloud_gpu" || resources?.ready != true) emptyList() else
        requiredModels.filter { (category, name) -> resources.items.none { it.category == category && it.name == name } }.map { it.second }
    fun sizes(): List<ComfySize> {
        val width = inputs.find { it.key == "width" }?.options.orEmpty()
        val height = inputs.find { it.key == "height" }?.options.orEmpty()
        if (width.isNotEmpty() && height.isNotEmpty()) return width.flatMap { w -> height.mapNotNull { h ->
            val x = (w as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            val y = (h as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            if (x <= 0 || y <= 0) return@mapNotNull null
            fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
            val factor = gcd(x, y)
            ComfySize("${x / factor}:${y / factor} · $x × $y", mapOf("width" to "$x", "height" to "$y"))
        } }.take(128)
        return inputs.find { it.key == "size" }?.options.orEmpty().map { value ->
            val raw = (value as? JsonPrimitive)?.content.orEmpty()
            ComfySize(raw, mapOf("size" to raw))
        }
    }
    fun parameters(values: Map<String, String>, resources: ComfyResources?): JsonObject {
        if (availability(resources).kind != ComfyAvailabilityKind.AVAILABLE) throw ComfyFailure("invalid_workflow")
        return buildJsonObject {
            inputs.forEach { input ->
                val raw = values[input.key] ?: input.default
                fun invalid(): Nothing = throw ComfyFailure("invalid_parameters", input.key)
                if (raw.isBlank() && input.type != "boolean") {
                    if (input.required) invalid()
                    if (input.type == "string" && input.resource == null) put(input.key, "")
                    return@forEach
                }
                val value: JsonElement = when (input.type) {
                    "integer" -> JsonPrimitive(raw.toLongOrNull() ?: invalid())
                    "number" -> JsonPrimitive(raw.toDoubleOrNull()?.takeIf { it.isFinite() } ?: invalid())
                    "boolean" -> JsonPrimitive(if (raw.isEmpty()) false else raw.toBooleanStrictOrNull() ?: invalid())
                    "asset" -> runCatching { wireJson.parseToJsonElement(raw).jsonObject }.getOrNull()?.takeIf {
                        it.keys == setOf("job_id", "output_index") &&
                            (it["job_id"] as? JsonPrimitive)?.isString == true &&
                            (it["output_index"] as? JsonPrimitive)?.isString == false &&
                            validComfyJobId(it.string("job_id").orEmpty()) && it.long("output_index")?.let { index -> index in 0..63 } == true
                    } ?: invalid()
                    "string" -> JsonPrimitive(raw)
                    else -> invalid()
                }
                if (input.options.isNotEmpty() && value !in input.options) invalid()
                if (input.type in setOf("integer", "number")) {
                    val number = (value as JsonPrimitive).double
                    if (input.min?.let { number < it } == true || input.max?.let { number > it } == true) invalid()
                }
                if (input.type == "string") {
                    val size = raw.codePointCount(0, raw.length)
                    if (size < (input.spec.long("min_length") ?: 0) || size > (input.spec.long("max_length") ?: 16000)) invalid()
                }
                if (input.resource != null && (resources?.ready != true || resources.items.none { it.category == input.resource && it.name == raw })) invalid()
                put(input.key, value)
            }
        }
    }
    companion object {
        fun from(j: JsonObject): ComfyWorkflow = ComfyWorkflow(
            requireNotNull(j.string("id")), j.string("title") ?: j.string("id").orEmpty(), j.string("description").orEmpty(),
            j.string("kind") ?: j.objects("outputs").firstOrNull()?.string("kind") ?: "image",
            j.string("billing_channel") ?: when (j.boolean("requires_partner_auth")) { true -> "partner_api"; false -> "cloud_gpu"; null -> "unknown" },
            requireNotNull(j.obj("inputs")).map { (key, value) -> ComfyInput(key, value.jsonObject) },
            j.string("unavailable_reason") ?: j.string("unavailable")?.takeIf { it != "false" && it.isNotBlank() }
                ?: if (j.boolean("available") == false) "unavailable" else null,
            j.objects("required_model_files").mapNotNull { file -> file.string("category")?.let { c -> file.string("name")?.let { c to it } } },
            j.boolean("available"),
        )
    }
}
data class ComfyResource(val category: String, val name: String, val family: String?)
data class ComfyResources(val items: List<ComfyResource>, val ready: Boolean, val refreshing: Boolean, val stale: Boolean) {
    companion object {
        fun from(j: JsonObject) = ComfyResources(j.objects("categories").flatMap { category ->
            category.objects("items").map { ComfyResource(requireNotNull(category.string("id")), requireNotNull(it.string("name")), it.string("family")) }
        }, j["fetched_at"] != null && j["fetched_at"] != JsonNull, j.boolean("refreshing") == true, j.boolean("stale") == true)
    }
}
data class ComfySuggestion(val workflowId: String, val probability: Double)
data class ComfyOutput(val index: Int, val mime: String, val bytes: Long, val durationSeconds: Double? = null, val publicUrl: String? = null)
data class ComfyJob(val requestId: String, val workflowId: String, val status: String,
    val parameters: JsonObject = JsonObject(emptyMap()), val outputs: List<ComfyOutput> = emptyList(), val errorCode: String? = null, val jobId: String? = null, val progress: Double? = null,
    val billingChannel: String? = null, val workerState: String? = null, val queuePosition: Int? = null,
    /** Kaggle cold-start stage reported by the worker (installing, models, comfy, …). */
    val workerStage: String? = null) {
    companion object {
        fun from(j: JsonObject): ComfyJob {
            val id = requireNotNull(j.string("request_id")); require(validComfyId(id))
            return ComfyJob(id, j.string("workflow_id").orEmpty(), requireNotNull(j.string("status")), j.obj("parameters") ?: JsonObject(emptyMap()),
                j.objects("outputs").map { ComfyOutput(requireNotNull(it.long("index")).toInt(), requireNotNull(it.string("mime")), it.long("bytes") ?: 0, (it["duration"] as? JsonPrimitive)?.doubleOrNull?.takeIf { d -> d.isFinite() && d >= 0 },
                    it.string("public_url")?.takeIf { url -> url.startsWith("https://") && url.length <= 512 }) },
                j.obj("error")?.string("code"), j.string("job_id"), (j["progress"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it in 0.0..1.0 },
                j.string("billing_channel"), j.string("worker_state"),
                (j["queue_position"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt(),
                j.string("worker_stage")?.takeIf { it in setOf("starting", "installing", "models", "comfy", "ready", "first_image") })
        }
    }
}
data class ComfySubmission(val workflowId: String, val parameters: JsonObject, val requestId: String = UUID.randomUUID().toString()) {
    fun json() = buildJsonObject { put("request_id", requestId); put("workflow_id", workflowId); put("parameters", parameters) }
}
