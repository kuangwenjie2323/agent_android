package com.karewinkcloud.agentweb.client.core

import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.random.Random

// Availability mirrors Studio: selectable resources are validated separately at submission.
enum class ComfyAvailabilityKind { AVAILABLE, UNAVAILABLE, WAITING_RESOURCES, MISSING_MODELS }
data class ComfyAvailability(val kind: ComfyAvailabilityKind, val missingModels: List<String> = emptyList())
// Comfy Cloud's published model list is incomplete (Qwen Image 2.1 runs but is unlisted, verified
// 2026-09-29), so unlisted weights never block: they ride along as a note on an AVAILABLE result.
fun ComfyWorkflow.availability(resources: ComfyResources?): ComfyAvailability = when {
    unavailable != null -> ComfyAvailability(ComfyAvailabilityKind.UNAVAILABLE)
    channel == "cloud_gpu" && requiredModels.isNotEmpty() && resources?.ready == true ->
        ComfyAvailability(ComfyAvailabilityKind.AVAILABLE, missingModels(resources))
    else -> ComfyAvailability(ComfyAvailabilityKind.AVAILABLE)
}

fun ComfyWorkflow.modelFamily(): String {
    val source = (inputs.mapNotNull { it.family }.filter { it != "unknown" }.joinToString(" ") + " $id $title").lowercase()
    return listOf("sdxl" to "SDXL", "flux" to "FLUX", "qwen" to "Qwen", "seedance" to "Seedance",
        "seedream" to "Seedream", "seed-audio" to "Seed Audio", "wan" to "Wan", "hunyuan" to "Hunyuan",
        "fasth3" to "Hunyuan", "ltx" to "LTX", "z-image" to "Z-Image", "nano-banana" to "Nano Banana",
        "gemini" to "Gemini", "gpt-image" to "GPT Image", "grok" to "Grok", "recraft" to "Recraft",
        "ideogram" to "Ideogram", "kling" to "Kling", "luma" to "Luma", "vidu" to "Vidu", "pruna" to "Pruna",
        "elevenlabs" to "ElevenLabs", "bria" to "Bria", "topaz" to "Topaz").firstOrNull { (key, _) -> source.contains(key) }?.second.orEmpty()
}
data class ComfyGalleryGroup(val channel: String, val family: String, val workflows: List<ComfyWorkflow>)
fun comfyGalleryGroups(workflows: List<ComfyWorkflow>, kind: String, query: String): List<ComfyGalleryGroup> {
    val needle = query.trim().lowercase()
    return workflows.filter { it.kind == kind && (needle.isEmpty() ||
        listOf(it.id, it.title, it.description, it.modelFamily()).any { text -> text.lowercase().contains(needle) }) }
        .groupBy { it.channel to it.modelFamily() }.map { (key, entries) -> ComfyGalleryGroup(key.first, key.second, entries) }
        .sortedWith(compareBy<ComfyGalleryGroup> { when (it.channel) { "kaggle_gpu" -> 0; "runpod_gpu" -> 1; "cloud_gpu" -> 2; "partner_api" -> 3; else -> 4 } }
            .thenBy { it.channel }.thenBy { it.family.ifEmpty { "\uffff" } })
}

data class ComfyFieldGroups(val basic: List<ComfyInput>, val advanced: List<ComfyInput>)
fun ComfyWorkflow.fieldGroups(): ComfyFieldGroups {
    val dimensions = setOf("width", "height", "size", "resolution", "aspect_ratio", "ratio")
    val creative = setOf("negative_prompt", "lyrics", "text", "style", "language", "language_code", "voice", "model",
        "duration", "loop", "camera_fixed", "generate_audio", "movement_amplitude", "quality", "background", "thinking",
        "prompt_extend", "raw", "sample_rate", "speech_rate", "loudness_rate", "pitch_rate", "stability", "prompt_influence",
        "duration_seconds", "model_name", "voice_id", "camera", "audio", "image_weight", "subject_detection", "creativity", "face_enhancement", "color_preservation", "rendering_speed")
    fun rank(input: ComfyInput): Int = when {
        input.resource != null -> if (input.resource == "loras") 3 else 99
        input.key in dimensions -> 0
        input.type == "asset" -> 1
        input.key in creative -> 2
        input.key.startsWith("lora_") || input.key in setOf("strength_model", "strength_clip") -> 3
        input.key in setOf("guidance", "guidance_scale", "cfg", "cfg_scale") -> 4
        input.key == "steps" -> 5
        input.key == "seed" -> 6
        else -> 99
    }
    val fields = inputs.filterNot { it.key == promptKey }
    return ComfyFieldGroups(fields.filter { rank(it) < 99 }.sortedBy(::rank), fields.filter { rank(it) == 99 })
}

data class ComfySliderRange(val minimum: Double, val maximum: Double, val integer: Boolean,
    private val exactMinimum: String? = null, private val exactMaximum: String? = null) {
    private val low get() = exactMinimum?.toBigDecimalOrNull() ?: BigDecimal.valueOf(minimum)
    private val high get() = exactMaximum?.toBigDecimalOrNull() ?: BigDecimal.valueOf(maximum)
    fun fraction(value: String): Float {
        val number = value.toBigDecimalOrNull() ?: return 0f
        return number.max(low).min(high).subtract(low).divide(high.subtract(low), 16, java.math.RoundingMode.HALF_UP).toFloat().coerceIn(0f, 1f)
    }
    fun value(fraction: Float): String {
        val normalized = if (fraction.isNaN()) 0f else fraction.coerceIn(0f, 1f)
        var number = low.add(high.subtract(low).multiply(BigDecimal.valueOf(normalized.toDouble())))
        number = number.setScale(if (integer) 0 else maxOf(3, low.scale(), high.scale()), java.math.RoundingMode.HALF_UP)
        return number.max(low).min(high).stripTrailingZeros().toPlainString()
    }
}
fun ComfyInput.sliderRange(): ComfySliderRange? {
    val low = min ?: return null; val high = max ?: return null
    if (type !in setOf("integer", "number") || options.isNotEmpty() || !low.isFinite() || !high.isFinite() || high <= low) return null
    if (type == "integer" && (low % 1.0 != 0.0 || high % 1.0 != 0.0)) return null
    return ComfySliderRange(low, high, type == "integer", (spec["minimum"] as? JsonPrimitive)?.content, (spec["maximum"] as? JsonPrimitive)?.content)
}

enum class ComfyJobPhase { STARTING, QUEUED, RUNNING, FINISHING, COMPLETE, FAILED, UNKNOWN }
fun ComfyJob.phase(): ComfyJobPhase = when {
    billingChannel == "kaggle_gpu" && workerState == "launching" && status in setOf("pending", "submitting", "queued") -> ComfyJobPhase.STARTING
    status in setOf("pending", "submitting", "queued") -> ComfyJobPhase.QUEUED
    status in setOf("running", "canceling") -> ComfyJobPhase.RUNNING
    status == "succeeded" && (outputs.isEmpty() || errorCode == "output_unavailable") -> ComfyJobPhase.FINISHING
    status == "succeeded" -> ComfyJobPhase.COMPLETE
    status in comfyTerminal -> ComfyJobPhase.FAILED
    else -> ComfyJobPhase.UNKNOWN
}
fun ComfyJob.needsPolling() = phase() in setOf(ComfyJobPhase.STARTING, ComfyJobPhase.QUEUED, ComfyJobPhase.RUNNING, ComfyJobPhase.FINISHING)
fun ComfyJob.waitingQueuePosition() = queuePosition?.takeIf { it > 0 && status == "queued" && billingChannel == "kaggle_gpu" }
// Kaggle IDs refer to AgentWeb-local output; Cloud asset inputs require a Cloud receipt.
fun ComfyJob.canReferenceInCloud(workflows: List<ComfyWorkflow>) = status == "succeeded" && jobId != null &&
    billingChannel !in setOf("kaggle_gpu", "runpod_gpu") && workflows.find { it.id == workflowId }?.channel !in setOf("kaggle_gpu", "runpod_gpu")

data class ComfyDraft(val workflowId: String, val kind: String, val channel: String, val prompt: String, val values: Map<String, String>)
fun ComfyWorkflow.prefill(job: ComfyJob): ComfyDraft {
    if (job.workflowId != id) throw ComfyFailure("invalid_workflow")
    val values = inputs.associate { input -> input.key to (job.parameters[input.key]?.let {
        if (it is JsonPrimitive) it.contentOrNull ?: input.default else it.toString()
    } ?: input.default) }
    return ComfyDraft(id, kind, channel, values[promptKey].orEmpty(), values)
}
fun ComfyWorkflow.againParameters(job: ComfyJob, random: Random = Random.Default): JsonObject {
    if (job.workflowId != id) throw ComfyFailure("invalid_workflow")
    val seed = inputs.find { it.key == "seed" && it.type == "integer" } ?: return job.parameters
    val old = (job.parameters[seed.key] as? JsonPrimitive)?.longOrNull
    val enum = seed.options.mapNotNull { (it as? JsonPrimitive)?.longOrNull }.distinct().filter {
        (seed.min == null || it.toDouble() >= seed.min!!) && (seed.max == null || it.toDouble() <= seed.max!!)
    }
    if (seed.options.isNotEmpty() && enum.isEmpty()) throw ComfyFailure("invalid_parameters", seed.key)
    val selected: Long = if (enum.isNotEmpty()) {
        val choices = enum.filter { it != old }.ifEmpty { enum }
        choices[random.nextInt(choices.size)]
    } else {
        // Work with exact schema integers: Double cannot represent the largest seed bounds.
        val low = (seed.spec["minimum"] as? JsonPrimitive)?.longOrNull ?: 0L
        val high = (seed.spec["maximum"] as? JsonPrimitive)?.longOrNull ?: Long.MAX_VALUE
        if (high < low) throw ComfyFailure("invalid_parameters", seed.key)
        if (high == low) return JsonObject(job.parameters + (seed.key to JsonPrimitive(low)))
        val lo = BigInteger.valueOf(low); val span = BigInteger.valueOf(high).subtract(lo).add(BigInteger.ONE)
        var offset: BigInteger
        do {
            val bytes = random.nextBytes((span.bitLength() + 7) / 8)
            bytes[0] = (bytes[0].toInt() and (0xff ushr (bytes.size * 8 - span.bitLength()))).toByte()
            offset = BigInteger(1, bytes)
        } while (offset >= span)
        var result = lo.add(offset).toLong()
        if (result == old && high > low) result = if (result == high) low else result + 1
        result
    }
    return JsonObject(job.parameters + (seed.key to JsonPrimitive(selected)))
}
