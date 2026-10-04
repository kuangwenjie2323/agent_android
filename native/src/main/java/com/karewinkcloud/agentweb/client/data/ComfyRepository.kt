package com.karewinkcloud.agentweb.client.data

import com.karewinkcloud.agentweb.client.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.*
import java.io.File

interface ComfyRepository {
    val authenticationRequired: Flow<Boolean>
    suspend fun workflows(): List<ComfyWorkflow>
    suspend fun recommend(prompt: String, channel: String): List<ComfySuggestion>
    suspend fun submit(request: ComfySubmission): ComfyJob
    suspend fun jobs(): List<ComfyJob>
    suspend fun job(requestId: String): ComfyJob
    suspend fun resources(refresh: Boolean = false): ComfyResources
    suspend fun download(requestId: String, output: ComfyOutput, destination: File)
    /** Uploads one finished output to the server's public R2 storage; returns its stable https link. */
    suspend fun publish(requestId: String, output: ComfyOutput): String = throw ComfyFailure("publish_unavailable")
    /** A server-made JPEG preview of an image output, at most [edge] px (512 tiles, 1280 detail). */
    suspend fun thumbnail(requestId: String, output: ComfyOutput, destination: File, edge: Int = 512): Unit = throw ComfyFailure("unavailable")
    /** Deletes a finished creation on the server, including its public R2 copy. */
    suspend fun delete(requestId: String): Unit = throw ComfyFailure("unavailable")
}

class HttpComfyRepository(private val http: HttpAgentRepository) : ComfyRepository {
    override val authenticationRequired = http.authenticationRequired
    private suspend fun <T> operation(block: suspend () -> T): T = try { block() }
    catch (e: CancellationException) { throw e }
    catch (e: ComfyFailure) { throw e }
    catch (e: ApiException) { throw ComfyFailure(when (e.status) { 401 -> "auth"; 403 -> "forbidden"; 404 -> "not_found"; 429 -> "rate_limited"; else -> e.problem.code ?: "unavailable" }) }
    catch (_: java.io.IOException) { throw ComfyFailure("network") }
    catch (_: Exception) { throw ComfyFailure("invalid_response") }

    override suspend fun workflows() = operation {
        val data = http.json("/api/comfy/workflows", timeoutSeconds = 90)
        require(data["workflows"] is JsonArray)
        data.objects("workflows").map(ComfyWorkflow::from)
    }
    override suspend fun recommend(prompt: String, channel: String): List<ComfySuggestion> = operation {
        val data = http.json("/api/comfy/recommend", buildJsonObject { put("prompt", prompt); put("billing_channel", channel) }, 15)
        if (data.boolean("available") != true) emptyList() else data.objects("suggestions").mapNotNull {
            val id = it.string("workflow_id") ?: return@mapNotNull null
            val probability = (it["probability"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            if (!probability.isFinite() || probability !in 0.0..1.0) null else ComfySuggestion(id, probability)
        }.distinctBy { it.workflowId }.take(3)
    }
    private fun readJob(data: JsonObject, id: String) = ComfyJob.from(requireNotNull(data.obj("job"))).also { require(it.requestId == id) }
    override suspend fun submit(request: ComfySubmission) = operation {
        require(validComfyId(request.requestId))
        readJob(http.json("/api/comfy/jobs", request.json(), 90), request.requestId)
    }
    override suspend fun jobs() = operation {
        val data = http.json("/api/comfy/jobs")
        require(data["jobs"] is JsonArray)
        data.objects("jobs").map(ComfyJob::from).distinctBy { it.requestId }.take(50)
    }
    override suspend fun job(requestId: String) = operation {
        require(validComfyId(requestId))
        // GET may finish downloading the upstream media on the server (>100s).
        readJob(http.json("/api/comfy/jobs/$requestId", timeoutSeconds = 300), requestId)
    }
    override suspend fun resources(refresh: Boolean) = operation {
        val data = http.json("/api/comfy/resources" + if (refresh) "?refresh=1" else "")
        require(data["categories"] is JsonArray)
        ComfyResources.from(data)
    }
    override suspend fun download(requestId: String, output: ComfyOutput, destination: File) = operation {
        require(validComfyId(requestId) && output.index in 0..63)
        require(output.mime in mediaExtensions)
        val limit = if (output.mime.startsWith("image/")) 32L * 1024 * 1024 else 256L * 1024 * 1024
        if (output.bytes > limit) throw ComfyFailure("too_large")
        // Derive a same-origin endpoint: never forward the bearer to an output URL.
        http.download("/api/comfy/jobs/$requestId/outputs/${output.index}", destination, output.mime, limit)
    }
    override suspend fun thumbnail(requestId: String, output: ComfyOutput, destination: File, edge: Int) = operation {
        require(validComfyId(requestId) && output.index in 0..63 && output.mime.startsWith("image/") && edge in setOf(512, 1280))
        http.download("/api/comfy/jobs/$requestId/outputs/${output.index}?preview=$edge", destination, "image/jpeg", 8L * 1024 * 1024)
    }
    override suspend fun publish(requestId: String, output: ComfyOutput) = operation {
        require(validComfyId(requestId) && output.index in 0..63)
        val url = http.json("/api/comfy/jobs/$requestId/outputs/${output.index}/publish", buildJsonObject { }, 90).string("public_url")
        requireNotNull(url?.takeIf { it.startsWith("https://") && it.length <= 512 })
    }
    override suspend fun delete(requestId: String) = operation {
        require(validComfyId(requestId))
        require(http.delete("/api/comfy/jobs/$requestId", 90).string("deleted") == requestId.lowercase())
    }
}
val mediaExtensions = mapOf("image/png" to "png", "image/jpeg" to "jpg", "image/jpg" to "jpg", "image/webp" to "webp", "image/gif" to "gif",
    "video/mp4" to "mp4", "video/webm" to "webm", "audio/wav" to "wav", "audio/x-wav" to "wav", "audio/wave" to "wav",
    "audio/mpeg" to "mp3", "audio/mp3" to "mp3", "audio/flac" to "flac", "audio/x-flac" to "flac", "audio/ogg" to "ogg", "application/ogg" to "ogg")
