package com.karewinkcloud.agentweb.client.data

import com.karewinkcloud.agentweb.client.core.DiagnosticLog
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

class DiagnosticsTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var log: DiagnosticLog
    // No transparent retry: a dropped connection must reach the interceptor as a failure.
    private val client = OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor(Diagnostics.interceptor).build()

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        log = DiagnosticLog(folder.root)
        Diagnostics.attach(log)
    }
    @After fun cleanup() { Diagnostics.attach(null); server.shutdown() }

    private fun recorded() = Diagnostics.flushed()!!.lines().map { Json.parseToJsonElement(it).jsonObject }
    private fun call(request: Request) = client.newCall(request).execute().use { it.code }

    @Test fun requestsAreRecordedWithoutQueriesHeadersOrBodies() {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"secret-body"}"""))
        call(Request.Builder().url(server.url("/api/chat/message?token=secret-query"))
            .header("Authorization", "Bearer secret-token")
            .post("""{"message":"secret-message"}""".toRequestBody("application/json".toMediaType())).build())
        val line = recorded().single()
        assertEquals("net", line["tag"]!!.jsonPrimitive.content)
        assertEquals("POST /api/chat/message 409", line["m"]!!.jsonPrimitive.content)
        assertEquals("W", line["l"]!!.jsonPrimitive.content)
        val text = log.lines().joinToString()
        listOf("secret-query", "secret-token", "secret-body", "secret-message").forEach { assertFalse(it, it in text) }
    }

    @Test fun quickSuccessfulReadsAreSummedUp() {
        repeat(3) { server.enqueue(MockResponse().setBody("{}")) }
        repeat(2) { call(Request.Builder().url(server.url("/api/chat/conversations")).build()) }
        call(Request.Builder().url(server.url("/api/chat/conversations/0123456789abcdef0123456789abcdef")).build())
        val line = recorded().single()
        assertEquals("GET ok x3", line["m"]!!.jsonPrimitive.content)
        assertEquals("/api/chat/conversations x2, /api/chat/conversations/:id x1", line["paths"]!!.jsonPrimitive.content)
    }

    @Test fun failuresAreRecordedWithTheirKind() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        runCatching { call(Request.Builder().url(server.url("/api/agents")).build()) }
        val line = recorded().single()
        assertEquals("GET /api/agents failed", line["m"]!!.jsonPrimitive.content)
        assertTrue(line["error"]!!.jsonPrimitive.content.isNotBlank())
    }

    @Test fun nothingIsRecordedBeforeInstall() {
        Diagnostics.attach(null)
        server.enqueue(MockResponse().setResponseCode(500))
        call(Request.Builder().url(server.url("/api/agents")).build())
        Diagnostics.event("t", "ignored")
        assertNull(Diagnostics.flushed())
        assertEquals(emptyList<String>(), log.lines())
    }

    @Test fun targetsKeepApiPathsAndStorageHostsButNeverQueries() {
        assertEquals("/api/files/a/b.png", Diagnostics.target("https://agent.example/api/files/a/b.png?sig=1".toHttpUrl()))
        assertEquals("bucket.r2.example/key.png", Diagnostics.target("https://bucket.r2.example/key.png?X-Amz-Signature=s".toHttpUrl()))
    }

    @Test fun parseErrorsNeverQuoteTheirInput() {
        val error = IllegalArgumentException("Unexpected JSON token at offset 9: Expected quotation mark\nJSON input: {\"text\":\"private words\"}")
        val described = Diagnostics.describe(error)
        assertFalse(described, "private words" in described)
        assertTrue(described.startsWith("IllegalArgumentException: Unexpected JSON token"))
        assertFalse("private words" in Diagnostics.redact(error.stackTraceToString()))
    }

    @Test fun anrDumpsKeepTheMainThread() {
        val dump = "----- pid 1 -----\n\"Signal Catcher\" daemon\n  at x\n\n\"main\" prio=5 tid=1 Blocked\n  at a.b(C.kt:1)\n\n\"other\"\n  at y\n"
        assertEquals("\"main\" prio=5 tid=1 Blocked\n  at a.b(C.kt:1)", Diagnostics.mainThread(dump))
        assertEquals("anr", Diagnostics.exitReason(6))
        assertEquals("unknown_99", Diagnostics.exitReason(99))
    }
}
