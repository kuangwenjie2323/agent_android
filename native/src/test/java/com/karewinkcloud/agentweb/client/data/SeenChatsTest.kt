package com.karewinkcloud.agentweb.client.data

import com.karewinkcloud.agentweb.client.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SeenChatsTest {
    private fun chat(id: String, updated: Long, active: Boolean = false, waiting: Boolean = false) =
        Conversation(id, id, false, active, false, null, null, ModelChoice("a", "m"), updatedAt = updated, waitingInput = waiting)

    @Test fun aReplyAfterTheUserLookedIsNews() {
        val seen = MemorySeenChats()
        seen.adopt(listOf(chat("a", 100), chat("b", 200)))  // first sight: read up to now
        assertFalse(chat("a", 100).unread(seen.seenAt("a")))
        assertTrue(chat("a", 150).unread(seen.seenAt("a")))  // a reply came later
        assertFalse(chat("a", 150, active = true).unread(seen.seenAt("a")))  // still running: not news yet
        assertFalse(chat("a", 150, waiting = true).unread(seen.seenAt("a")))  // waiting shows its own state
        seen.markSeen("a", 160)
        assertFalse(chat("a", 150).unread(seen.seenAt("a")))
        seen.adopt(listOf(chat("a", 500)))  // adopting never moves an existing mark
        assertEquals(160L, seen.seenAt("a"))
        assertFalse(chat("unknown", 999).unread(null))
    }

    @Test fun theListSaysWhichChatWaitsOnTheUser() {
        val row = Conversation.from(buildJsonObject { put("id", "c"); put("title", "t"); put("active", true); put("waiting_input", true) })
        assertTrue(row.waitingInput)
        assertFalse(Conversation.from(buildJsonObject { put("id", "c"); put("title", "t") }).waitingInput)
    }
}
