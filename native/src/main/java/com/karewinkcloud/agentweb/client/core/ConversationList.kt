package com.karewinkcloud.agentweb.client.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class ConversationGroup { TODAY, YESTERDAY, OLDER }
data class ConversationSection(val group: ConversationGroup, val conversations: List<Conversation>)

fun conversationSections(conversations: List<Conversation>, query: String, showEmpty: Boolean,
    today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): List<ConversationSection> {
    val needle = query.trim()
    val filtered = conversations.filter { conversation ->
        (showEmpty || conversation.messageCount != 0 || conversation.running || conversation.pinned ||
            !(conversation.title.isBlank() || conversation.title.equals("New chat", true))) &&
            (needle.isEmpty() || listOf(conversation.title, conversation.preview, conversation.project.orEmpty(),
                conversation.choice.agent, conversation.choice.model).any { it.contains(needle, ignoreCase = true) })
    }.sortedByDescending { it.updatedAt }
    val groups = filtered.groupBy {
        val date = Instant.ofEpochSecond(it.updatedAt).atZone(zone).toLocalDate()
        when {
            date >= today -> ConversationGroup.TODAY
            date == today.minusDays(1) -> ConversationGroup.YESTERDAY
            else -> ConversationGroup.OLDER
        }
    }
    return ConversationGroup.entries.mapNotNull { group -> groups[group]?.let { ConversationSection(group, it) } }
}

fun Conversation.withPreview(detail: ConversationDetail): Conversation = copy(
    messageCount = detail.olderCount + detail.messages.size,
    preview = detail.messages.lastOrNull { it.role in setOf("assistant", "user") && it.text.isNotBlank() }?.text?.let(::plainPreview).orEmpty(),
    title = if (title.isBlank() || title.equals("New chat", true))
        detail.messages.firstOrNull { it.role == "user" }?.text?.take(100)?.ifBlank { title } ?: title else title,
)

fun plainPreview(text: String): String = text.lineSequence().map { line ->
    simpleHtmlText(line, " ").trim().replace(Regex("^`{3,}.*$|^~{3,}.*$|^\\s*[-*_]{3,}\\s*$"), "")
        .replace(Regex("!?\\[([^]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("<((?:https?://|mailto:)[^>]+)>"), "$1")
        .replace(Regex("^\\s*(?:#{1,6}\\s+|>\\s*|[-+*]\\s+|\\d+[.)]\\s+)"), "")
        .replace(Regex("^\\[[ xX]]\\s*"), "")
        .replace(Regex("[`*~]|(?<![\\p{L}\\p{N}])_+|_+(?![\\p{L}\\p{N}])"), "").replace(Regex("\\s+"), " ").trim()
}.lastOrNull { it.isNotBlank() }?.take(180).orEmpty()
