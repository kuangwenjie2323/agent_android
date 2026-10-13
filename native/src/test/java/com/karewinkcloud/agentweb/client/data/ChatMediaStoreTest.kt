package com.karewinkcloud.agentweb.client.data

import com.karewinkcloud.agentweb.client.core.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.nio.file.Files

class ChatMediaStoreTest {
    private lateinit var server: MockWebServer
    private lateinit var cache: File
    private val picture = byteArrayOf(-1, -40, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8)
    private val media get() = MessageMedia("a.jpg", "image/jpeg", "/api/files/conversation/images/uploads/a.jpg")

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        cache = Files.createTempDirectory("chat-media-test").toFile()
    }
    @After fun cleanup() { server.shutdown(); cache.deleteRecursively() }

    private fun store(token: String?) = ChatMediaStore(HttpAgentRepository(server.url("/").toString().trimEnd('/'), TokenProvider { token }), cache)
    private fun image() = MockResponse().addHeader("Content-Type", "image/jpeg").setBody(Buffer().write(picture))

    @Test fun picturesSeenBeforeShowAfterARestartWithoutDownloadingAgain() = runBlocking {
        server.enqueue(image())
        val first = store("token-a").load(media)
        assertArrayEquals(picture, first.file.readBytes())
        assertEquals(1, server.requestCount)
        // A new store is what an app restart creates: the same account reuses what is on disk.
        val again = store("token-a").load(media)
        assertArrayEquals(picture, again.file.readBytes())
        assertEquals("image/jpeg", again.mime)
        assertEquals(1, server.requestCount)
        assertFalse(cache.walkTopDown().any { it.name.endsWith(".part") })
    }

    @Test fun anotherAccountNeverReusesTheDownload() = runBlocking {
        server.enqueue(image()); server.enqueue(image())
        store("token-a").load(media)
        store("token-b").load(media)
        assertEquals(2, server.requestCount)
    }

    @Test fun anInterruptedDownloadIsNotTakenAsComplete() = runBlocking {
        server.enqueue(MockResponse().addHeader("Content-Type", "image/jpeg").addHeader("Content-Length", "100")
            .setBody(Buffer().write(picture)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        assertTrue(runCatching { store("token-a").load(media) }.isFailure)
        server.enqueue(image())
        assertArrayEquals(picture, store("token-a").load(media).file.readBytes())
        assertEquals(2, server.requestCount)
    }
}
