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
    /** For a reference (asset) input: image, video or audio. */
    val media get() = spec.string("media") ?: "image"
    val family get() = spec.string("family")
    val options get() = spec.array("enum")
    val default get() = spec["default"]?.let { if (it is JsonPrimitive) it.contentOrNull else it.toString() }.orEmpty()
    val min get() = (spec["minimum"] as? JsonPrimitive)?.doubleOrNull
    val max get() = (spec["maximum"] as? JsonPrimitive)?.doubleOrNull
}
data class ComfySize(val label: String, val parameters: Map<String, String>)

/** A parameter offered as a composer shortcut, with the values to choose from. */
data class ComfyQuickChoice(val input: ComfyInput, val values: List<String>)
fun ComfyInput.enumValues() = options.mapNotNull { (it as? JsonPrimitive)?.content }
/** The enum, or for a numeric range a few round values inside it (a 0.5-30 s duration offers 3, 5, 8, 10, 15, 30). */
fun ComfyInput.choiceValues(): List<String> = enumValues().ifEmpty {
    val low = min ?: return emptyList(); val high = max ?: return emptyList()
    listOf(3L, 5L, 8L, 10L, 15L, 30L).filter { it.toDouble() in low..high }.map { it.toString() }
}
/** Two values that mean the same number ("5" and "5.0") count as the same choice. */
fun sameChoice(a: String?, b: String?) = a == b || (a?.toDoubleOrNull() != null && a.toDoubleOrNull() == b?.toDoubleOrNull())

/** Familiar video frame shapes, most common first; 1344 × 768 reads as 16:9 rather than 7:4. */
private val VideoRatios = listOf(16 to 9, 9 to 16, 1 to 1, 4 to 3, 3 to 4, 21 to 9)
private fun videoRatio(width: Int, height: Int): String? = VideoRatios.firstOrNull { (a, b) ->
    kotlin.math.abs(width.toDouble() / height / (a.toDouble() / b) - 1) < 0.03 }?.let { (a, b) -> "$a:$b" }
data class ComfyWorkflow(val id: String, val title: String, val description: String, val kind: String,
    val channel: String, val inputs: List<ComfyInput>, val unavailable: String?, val requiredModels: List<Pair<String, String>>,
    val available: Boolean? = null, val coverUrl: String? = null) {
    val promptKey get() = inputs.firstOrNull { it.key == "prompt" }?.key
        ?: inputs.firstOrNull { it.key == "text" }?.key
    fun defaults() = inputs.associate { it.key to it.default }
    fun missingModels(resources: ComfyResources?) = if (channel != "cloud_gpu" || resources?.ready != true) emptyList() else
        requiredModels.filter { (category, name) -> resources.items.none { it.category == category && it.name == name } }.map { it.second }
    /** Shortcuts for the composer: frame shape and duration for video, voice and duration for audio. */
    fun quickChoices(): List<ComfyQuickChoice> {
        fun pick(vararg keys: String) = inputs.firstOrNull { it.key in keys }
        val keys = when (kind) {
            "video" -> listOfNotNull(pick("aspect_ratio", "ratio").takeIf { sizes().isEmpty() }, pick("duration", "duration_seconds"))
            "audio" -> listOfNotNull(pick("voice", "voice_id"), pick("duration", "duration_seconds"))
            else -> emptyList()
        }
        return keys.map { ComfyQuickChoice(it, if (it.key in setOf("duration", "duration_seconds")) it.choiceValues() else it.enumValues()) }
            .filter { it.values.size > 1 }
    }
    fun sizes(): List<ComfySize> {
        val width = inputs.find { it.key == "width" }?.options.orEmpty()
        val height = inputs.find { it.key == "height" }?.options.orEmpty()
        // A video model has a fixed pixel budget: never offer a frame larger than its default one.
        val budget = if (kind != "video") null else defaults().let { d ->
            d["width"]?.toLongOrNull()?.let { w -> d["height"]?.toLongOrNull()?.let { h -> w * h } } }
        if (width.isNotEmpty() && height.isNotEmpty()) return width.flatMap { w -> height.mapNotNull { h ->
            val x = (w as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            val y = (h as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            if (x <= 0 || y <= 0 || budget != null && x.toLong() * y > budget) return@mapNotNull null
            fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
            val factor = gcd(x, y)
            val ratio = if (kind == "video") videoRatio(x, y) ?: "${x / factor}:${y / factor}" else "${x / factor}:${y / factor}"
            ComfySize("$ratio · $x × $y", mapOf("width" to "$x", "height" to "$y"))
        } }.let { all -> if (kind == "video") all.sortedBy { size -> VideoRatios.indexOfFirst { size.label.startsWith("${it.first}:${it.second} ") }
            .let { if (it < 0) VideoRatios.size else it } } else all }.take(128)
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
                        (it.keys == setOf("job_id", "output_index") &&
                            (it["job_id"] as? JsonPrimitive)?.isString == true &&
                            (it["output_index"] as? JsonPrimitive)?.isString == false &&
                            validComfyJobId(it.string("job_id").orEmpty()) && it.long("output_index")?.let { index -> index in 0..63 } == true) ||
                            // A photo the user uploaded from this device (see ComfyRepository.uploadInput).
                            (it.keys == setOf("upload_id") && (it["upload_id"] as? JsonPrimitive)?.isString == true &&
                                validUploadId(it.string("upload_id").orEmpty()))
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
            storageUrl(j.string("cover_url")),
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
data class ComfyOutput(val index: Int, val mime: String, val bytes: Long, val durationSeconds: Double? = null, val publicUrl: String? = null,
    /** Cloud-storage (R2) links for the original, the 1280 preview and the 512 tile. */
    val originalUrl: String? = null, val previewUrl: String? = null, val thumbUrl: String? = null)
/** Server-issued id of an uploaded reference photo: 32 lowercase hex digits. */
fun validUploadId(value: String) = value.length == 32 && value.all { it in '0'..'9' || it in 'a'..'f' }
/** HTTPS links without embedded credentials; they are fetched without any of ours. */
fun storageUrl(value: String?): String? = value?.takeIf {
    it.length <= 2048 && runCatching { java.net.URI(it) }.getOrNull()?.let { uri ->
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null
    } == true
}
data class ComfyJob(val requestId: String, val workflowId: String, val status: String,
    val parameters: JsonObject = JsonObject(emptyMap()), val outputs: List<ComfyOutput> = emptyList(), val errorCode: String? = null, val jobId: String? = null, val progress: Double? = null,
    val billingChannel: String? = null, val workerState: String? = null, val queuePosition: Int? = null,
    /** Kaggle cold-start stage reported by the worker (installing, models, comfy, …). */
    val workerStage: String? = null,
    /** Server clock, epoch seconds: when the job was submitted and when it reached a final state. */
    val createdAt: Long? = null, val finishedAt: Long? = null,
    /** The GPU provider's own split, when it reports one: waiting for a worker vs. running on it. */
    val queueMs: Long? = null, val runMs: Long? = null) {
    /** Seconds from submission to the final state, when both ends are known. */
    val totalSeconds get() = createdAt?.let { start -> finishedAt?.let { end -> (end - start).takeIf { it >= 0 } } }
    companion object {
        fun from(j: JsonObject): ComfyJob {
            val id = requireNotNull(j.string("request_id")); require(validComfyId(id))
            return ComfyJob(id, j.string("workflow_id").orEmpty(), requireNotNull(j.string("status")), j.obj("parameters") ?: JsonObject(emptyMap()),
                j.objects("outputs").map { ComfyOutput(requireNotNull(it.long("index")).toInt(), requireNotNull(it.string("mime")), it.long("bytes") ?: 0, (it["duration"] as? JsonPrimitive)?.doubleOrNull?.takeIf { d -> d.isFinite() && d >= 0 },
                    it.string("public_url")?.takeIf { url -> url.startsWith("https://") && url.length <= 512 },
                    storageUrl(it.string("original_url")), storageUrl(it.string("preview_url")), storageUrl(it.string("thumb_url"))) },
                j.obj("error")?.string("code"), j.string("job_id"), (j["progress"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it in 0.0..1.0 },
                j.string("billing_channel"), j.string("worker_state"),
                (j["queue_position"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt(),
                j.string("worker_stage")?.takeIf { it in setOf("starting", "installing", "models", "comfy", "ready", "first_image", "waiting_gpu") },
                epochSeconds(j["created_at"]), epochSeconds(j["finished_at"]),
                j.obj("timing")?.let { duration(it["queue_ms"]) }, j.obj("timing")?.let { duration(it["run_ms"]) })
        }
    }
}
private fun epochSeconds(value: JsonElement?) = (value as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
    ?.takeIf { it.isFinite() && it > 1_000_000_000 && it < 10_000_000_000 }?.toLong()
private fun duration(value: JsonElement?) = (value as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it in 0..1_000_000_000 }
data class ComfySubmission(val workflowId: String, val parameters: JsonObject, val requestId: String = UUID.randomUUID().toString()) {
    fun json() = buildJsonObject { put("request_id", requestId); put("workflow_id", workflowId); put("parameters", parameters) }
}
