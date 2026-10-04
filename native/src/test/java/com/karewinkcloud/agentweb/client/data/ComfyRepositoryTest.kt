package com.karewinkcloud.agentweb.client.data

import com.karewinkcloud.agentweb.client.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class ComfyRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var repo: HttpComfyRepository
    private val id = "12345678-1234-1234-1234-123456789012"
    private val job get() = """{"request_id":"$id","job_id":"upstream","workflow_id":"draw","status":"queued","parameters":{"prompt":"山水"},"outputs":[]}"""
    @Before fun setup() {
        server = MockWebServer().apply { start() }
        repo = HttpComfyRepository(HttpAgentRepository(server.url("/").toString().trimEnd('/'), TokenProvider { "test-only" }))
    }
    @After fun cleanup() { server.shutdown() }
    private fun json(value: String, status: Int = 200) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(value)
    private fun next() = requireNotNull(server.takeRequest(3, TimeUnit.SECONDS))
    private suspend fun fails(code: String, action: suspend () -> Unit) {
        try { action(); fail("Expected $code") } catch (e: ComfyFailure) { assertEquals(code, e.code) }
    }
    @Test fun workflowCatalogPreservesSchemaAndChannel() = runBlocking {
        server.enqueue(json("""{"workflows":[{"id":"draw","title":"画图","kind":"image","billing_channel":"cloud_gpu","inputs":{"prompt":{"type":"string","required":true},"steps":{"type":"integer","default":20,"minimum":1,"maximum":30}},"required_model_files":[{"category":"checkpoints","name":"sdxl.safetensors"}]}]}"""))
        val workflow = repo.workflows().single()
        assertEquals("cloud_gpu", workflow.channel); assertEquals("20", workflow.defaults()["steps"])
        assertEquals(listOf("checkpoints" to "sdxl.safetensors"), workflow.requiredModels)
        val request = next(); assertEquals("/api/comfy/workflows", request.path)
        assertEquals("Bearer test-only", request.getHeader("Authorization"))
        assertEquals("1.0", request.getHeader("AgentWeb-Protocol-Min"))
    }
    @Test fun workflowsError() = runBlocking {
        server.enqueue(json("""{"error":"unavailable"}""", 503)); fails("unavailable") { repo.workflows() }
    }
    @Test fun malformedCatalogFailsClosed() = runBlocking {
        server.enqueue(json("{}")); fails("invalid_response") { repo.workflows() }
    }
    @Test fun recommendationIsExplicitAndFailOpen() = runBlocking {
        server.enqueue(json("""{"available":true,"suggestions":[{"workflow_id":"draw","probability":0.8},{"workflow_id":"bad","probability":-1}]}"""))
        assertEquals(listOf(ComfySuggestion("draw", .8)), repo.recommend("山水画创作", "cloud_gpu"))
        val request = next(); assertEquals("POST", request.method); assertEquals("/api/comfy/recommend", request.path)
        val body = wireJson.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("山水画创作", body.string("prompt")); assertEquals("cloud_gpu", body.string("billing_channel"))
        server.enqueue(json("""{"available":false}""")); assertTrue(repo.recommend("山水画创作", "cloud_gpu").isEmpty())
    }
    @Test fun recommendationError() = runBlocking {
        server.enqueue(json("""{"error":"unavailable"}""", 503)); fails("unavailable") { repo.recommend("prompt", "cloud_gpu") }
    }
    @Test fun submitPreservesRequestIdentityAndTypedParameters() = runBlocking {
        server.enqueue(json("""{"job":$job}""", 201))
        val submission = ComfySubmission("draw", buildJsonObject { put("prompt", "山水"); put("steps", 20); put("loop", false) }, id)
        assertEquals(id, repo.submit(submission).requestId)
        val request = next(); assertEquals("POST", request.method); assertEquals("/api/comfy/jobs", request.path)
        assertEquals(submission.json(), wireJson.parseToJsonElement(request.body.readUtf8()))
    }
    @Test fun answeredSubmitRejectionRemainsStructured() = runBlocking {
        server.enqueue(json("""{"error":"invalid_parameters","message":"Invalid parameters"}""", 400))
        fails("invalid_parameters") { repo.submit(ComfySubmission("draw", JsonObject(emptyMap()), id)) }
        assertEquals(1, server.requestCount)
    }
    @Test fun uncertainSubmitIsRecoveredOnlyByGetWithOriginalId() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        fails("network") { repo.submit(ComfySubmission("draw", JsonObject(emptyMap()), id)) }
        assertEquals(1, server.requestCount); assertEquals("POST", next().method)
        server.enqueue(json("""{"job":$job}""")); assertEquals(id, repo.job(id).requestId)
        val recovery = next(); assertEquals("GET", recovery.method); assertEquals("/api/comfy/jobs/$id", recovery.path)
        assertEquals(2, server.requestCount)
    }
    @Test fun unavailableAndLegacyRejectionDoNotEstablishFailure() = runBlocking {
        for (code in listOf("unavailable", "plugin_rejected", "unknown")) {
            server.enqueue(json("""{"error":{"code":"$code"}}""", 503))
            fails(code) { repo.submit(ComfySubmission("draw", JsonObject(emptyMap()), id)) }
            assertFalse(code in comfyRejected)
        }
        assertEquals(3, server.requestCount)
    }
    @Test fun mismatchedReceiptIsNotAccepted() = runBlocking {
        server.enqueue(json("""{"job":${job.replace(id, "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")}}"""))
        fails("invalid_response") { repo.submit(ComfySubmission("draw", JsonObject(emptyMap()), id)) }
    }
    @Test fun historyAndJobPolling() = runBlocking {
        server.enqueue(json("""{"jobs":[$job]}""")); assertEquals(id, repo.jobs().single().requestId)
        assertEquals("/api/comfy/jobs", next().path)
        server.enqueue(json("""{"job":$job}""")); assertEquals("queued", repo.job(id).status)
        assertEquals("/api/comfy/jobs/$id", next().path)
    }
    @Test fun historyError() = runBlocking {
        server.enqueue(json("""{"error":"unavailable"}""", 503)); fails("unavailable") { repo.jobs() }
    }
    @Test fun missingRecoveryDoesNotReplaySubmission() = runBlocking {
        server.enqueue(json("{}", 404)); fails("not_found") { repo.job(id) }
        assertEquals("GET", next().method); assertEquals(1, server.requestCount)
    }
    @Test fun resourcesPreserveColdAndCachedStates() = runBlocking {
        server.enqueue(json("""{"categories":[],"fetched_at":null,"refreshing":true,"stale":true}"""))
        val cold = repo.resources(); assertFalse(cold.ready); assertTrue(cold.refreshing)
        assertEquals("/api/comfy/resources", next().path)
        server.enqueue(json("""{"categories":[{"id":"loras","items":[{"name":"detail.safetensors","family":"sdxl"}]}],"fetched_at":1234,"refreshing":false,"stale":true}"""))
        val cached = repo.resources(true); assertTrue(cached.ready); assertEquals("loras", cached.items.single().category)
        assertEquals("/api/comfy/resources?refresh=1", next().path)
    }
    @Test fun resourcesError() = runBlocking {
        server.enqueue(json("""{"error":"unavailable"}""", 503)); fails("unavailable") { repo.resources() }
    }
    @Test fun outputDownloadsAuthenticatedBytesFromDerivedEndpoint() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3, 4)
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(bytes)))
        val file = temporary.newFile()
        repo.download(id, ComfyOutput(2, "image/png", 4), file)
        assertArrayEquals(bytes, file.readBytes())
        val request = next(); assertEquals("/api/comfy/jobs/$id/outputs/2", request.path)
        assertEquals("Bearer test-only", request.getHeader("Authorization"))
    }
    @Test fun outputErrorAndWrongMimeLeaveNoPartialFile() = runBlocking {
        val file = temporary.newFile()
        server.enqueue(json("""{"error":"output_unavailable"}""", 503))
        fails("output_unavailable") { repo.download(id, ComfyOutput(0, "image/png", 4), file) }; assertFalse(file.exists())
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("html"))
        fails("network") { repo.download(id, ComfyOutput(0, "image/png", 4), file) }; assertFalse(file.exists())
    }
    @Test fun cloudStorageLinksMustBePlainHttps() {
        assertEquals("https://kaggle.karewinkcloud.com/outputs/k.png", storageUrl("https://kaggle.karewinkcloud.com/outputs/k.png"))
        assertNull(storageUrl("http://kaggle.karewinkcloud.com/outputs/k.png"))
        assertNull(storageUrl("https://user:secret@kaggle.karewinkcloud.com/k.png"))
        assertNull(storageUrl("not a url"))
        assertNull(storageUrl(null))
    }
    @Test fun oversizedOutputRejectedBeforeDownload() = runBlocking {
        fails("too_large") { repo.download(id, ComfyOutput(0, "image/png", 65L * 1024 * 1024), temporary.newFile()) }
        assertEquals(0, server.requestCount)
    }
    @Test fun authenticationLossUsesSharedTokenProviderAndBlocksLaterRequests() = runBlocking {
        var rejected = false
        repo = HttpComfyRepository(HttpAgentRepository(server.url("/").toString().trimEnd('/'), object : TokenProvider {
            override fun tokenFor(origin: String) = "test-only"
            override fun unauthorized(origin: String, rejectedToken: String?) { rejected = rejectedToken == "test-only" }
        }))
        server.enqueue(json("{}", 401)); fails("auth") { repo.jobs() }; assertTrue(rejected)
        fails("auth") { repo.workflows() }; assertEquals(1, server.requestCount)
    }
    @Test fun cancelStatusReadClosesSlowBodyWithoutRetry() = runBlocking {
        server.enqueue(json("""{"job":$job}""").throttleBody(1, 1, TimeUnit.SECONDS))
        val checking = launch(Dispatchers.IO) { repo.job(id) }
        assertEquals("GET", next().method)
        withTimeout(2000) { checking.cancelAndJoin() }
        assertEquals(1, server.requestCount)
    }
    @Test fun chunkedOutputEnforcesStreamingByteLimit() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setChunkedBody("123456789", 3))
        val file = temporary.newFile()
        val transport = HttpAgentRepository(server.url("/").toString().trimEnd('/'), TokenProvider { "test-only" })
        try { transport.download("/api/comfy/jobs/$id/outputs/0", file, "image/png", 4); fail() }
        catch (_: java.io.IOException) { assertFalse(file.exists()) }
    }
    @Test fun redirectsNeverSendCredentialOrRepeatMutation() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "https://other.invalid/media"))
        fails("unavailable") { repo.submit(ComfySubmission("draw", JsonObject(emptyMap()), id)) }
        assertEquals(1, server.requestCount)
    }
}
