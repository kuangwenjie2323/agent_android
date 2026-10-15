package com.karewinkcloud.agentweb.client.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiErrorTest {
    @Test fun anOversizedSendSaysWhatToDo() {
        // The server closes a too-large upload without a readable body; the status alone has to explain it.
        assertEquals(TOO_LARGE_MESSAGE, parseApiError(413, "").message)
        assertEquals(TOO_LARGE_MESSAGE, parseApiError(413, """{"error":"request body too large"}""").message)
    }
}
