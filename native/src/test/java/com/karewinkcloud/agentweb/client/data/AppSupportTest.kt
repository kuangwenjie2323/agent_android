package com.karewinkcloud.agentweb.client.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

class AppSupportTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var support: HttpAppSupport

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        val http = HttpAgentRepository(server.url("/").toString().trimEnd('/'), TokenProvider { "test-token" })
        support = HttpAppSupport(http, now = { 1_791_995_412_000L }, random = { "ab12cd34" })
    }
    @After fun cleanup() { server.shutdown() }
    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status)
        .addHeader("Content-Type", "application/json").setBody(body)
    private fun next() = requireNotNull(server.takeRequest(3, TimeUnit.SECONDS))
    private fun gzip(text: String) = java.io.ByteArrayOutputStream().also { out ->
        java.util.zip.GZIPOutputStream(out).use { it.write(text.toByteArray()) } }.toByteArray()

    @Test fun logPartsGoOutInOrderWithTheMetadataOnTheFirst() = runBlocking {
        repeat(3) { server.enqueue(json("""{"complete":false}""")) }
        val progress = mutableListOf<Pair<Int, Int>>()
        val id = support.uploadLogs(listOf(gzip("a\n"), gzip("b\n"), gzip("c\n")), buildJsonObject { put("note", "slow") }) { sent, total ->
            progress += sent to total }
        assertTrue(Regex("""\d{8}-\d{6}-ab12cd34""").matches(id))
        val bodies = (1..3).map { next() }.map { request ->
            assertEquals("POST", request.method); assertEquals("/api/client-logs", request.path)
            assertEquals("Bearer test-token", request.getHeader("Authorization"))
            Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        }
        assertEquals(listOf(1, 2, 3), bodies.map { it["part"]!!.jsonPrimitive.int })
        bodies.forEach { assertEquals(3, it["parts"]!!.jsonPrimitive.int); assertEquals(id, it["uploadId"]!!.jsonPrimitive.content) }
        assertEquals("slow", bodies[0]["meta"]!!.jsonObject["note"]!!.jsonPrimitive.content)
        assertNull(bodies[1]["meta"])
        val text = bodies.joinToString("") { GZIPInputStream(Base64.getDecoder().decode(it["data"]!!.jsonPrimitive.content).inputStream())
            .readBytes().decodeToString() }
        assertEquals("a\nb\nc\n", text)
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), progress)
    }

    @Test fun aDroppedConnectionResendsThatPartOnly() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(json("{}"))
        // Without the resend this would throw; the second response is consumed by the retry.
        assertTrue(Regex(""".+-ab12cd34""").matches(support.uploadLogs(listOf(gzip("a\n")), buildJsonObject { })))
    }

    @Test fun anOldServerWithoutTheRouteSaysNotFound() = runBlocking {
        server.enqueue(json("""{"error":"not found"}""", 404))
        try { support.uploadLogs(listOf(gzip("a\n")), buildJsonObject { }); fail("expected 404") }
        catch (e: com.karewinkcloud.agentweb.client.core.ApiException) { assertEquals(404, e.status) }
    }

    @Test fun updateCheckReadsThePublishedBuild() = runBlocking {
        server.enqueue(json("""{"available":false}"""))
        assertNull(support.latestUpdate())
        server.enqueue(json("""{"available":true,"versionCode":413280,"versionName":"0.8.0","size":12,"sha256":"ab","notes":"n"}"""))
        assertEquals(AppUpdate(413280, "0.8.0", 12, "ab", "n"), support.latestUpdate())
        assertEquals("/api/android/update", next().path)
        assertEquals("10-15 00:00", AppUpdate(413280, "", 0, "", "").builtAt)
    }

    @Test fun downloadKeepsOnlyTheExactPublishedFile() = runBlocking {
        val apk = "PK".toByteArray() + ByteArray(100_000) { it.toByte() }
        val sha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
        val response = { MockResponse().addHeader("Content-Type", HttpAppSupport.APK_MIME).setBody(Buffer().write(apk)) }
        server.enqueue(response())
        val target = folder.root.resolve("updates/agentweb.apk")
        support.downloadUpdate(AppUpdate(1, "x", apk.size.toLong(), sha, ""), target)
        assertArrayEquals(apk, target.readBytes())
        assertEquals("/api/android/update/apk", next().path)
        server.enqueue(response())
        try { support.downloadUpdate(AppUpdate(1, "x", apk.size.toLong(), "00".repeat(32), ""), target); fail("expected mismatch") }
        catch (_: UpdateMismatchException) { }
        assertFalse(target.exists())
    }
}
