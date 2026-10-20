package com.karewinkcloud.agentweb.client.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiErrorTest {
    @Test fun anOversizedSendSaysWhatToDo() {
        // The server closes a too-large upload without a readable body; the status alone has to explain it.
        assertEquals(TOO_LARGE_MESSAGE, parseApiError(413, "").message)
        assertEquals(TOO_LARGE_MESSAGE, parseApiError(413, """{"error":"request body too large"}""").message)
    }

    @Test fun cloudflareErrorsSayTheComputerIsUnreachable() {
        assertEquals(SERVER_UNREACHABLE_MESSAGE, parseApiError(530, "error code: 1033").message)
        assertEquals(SERVER_UNREACHABLE_MESSAGE, parseApiError(502, "<html>Bad gateway</html>").message)
        // The server's own coded errors keep their words.
        assertEquals("busy", parseApiError(503, """{"error":"busy","code":"provider_busy"}""").message)
    }

    @Test fun onlyFailuresBeforeTheRequestLeftCountAsNeverSent() {
        assertTrue(javax.net.ssl.SSLHandshakeException("connection closed").failedBeforeSending())
        assertTrue(java.net.UnknownHostException("agent.example.com").failedBeforeSending())
        assertTrue(java.net.SocketTimeoutException("failed to connect to /1.2.3.4 (port 443)").failedBeforeSending())
        assertFalse(java.net.SocketTimeoutException("timeout").failedBeforeSending())
        assertFalse(java.net.SocketException("Software caused connection abort").failedBeforeSending())
    }
}
