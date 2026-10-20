package com.karewinkcloud.agentweb.client.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class ConversationListTest {
    private val today = LocalDate.of(2026, 10, 19)
    private fun chat(id: String, daysAgo: Long, pinned: Boolean = false) = Conversation(id, "title $id", pinned, false, false, null, null,
        ModelChoice("deepseek", "deepseek-flash"), updatedAt = today.minusDays(daysAgo).atStartOfDay().toEpochSecond(ZoneOffset.UTC) + 3600,
        messageCount = 2)

    @Test fun pinnedChatsComeFirstWhateverTheirAge() {
        val sections = conversationSections(listOf(chat("new", 0), chat("old-pinned", 40, pinned = true), chat("old", 40)), "", false,
            today, ZoneOffset.UTC)
        assertEquals(listOf(ConversationGroup.PINNED to listOf("old-pinned"), ConversationGroup.TODAY to listOf("new"),
            ConversationGroup.OLDER to listOf("old")), sections.map { it.group to it.conversations.map(Conversation::id) })
    }
}
