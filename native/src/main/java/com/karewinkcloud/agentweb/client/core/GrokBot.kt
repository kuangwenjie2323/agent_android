package com.karewinkcloud.agentweb.client.core

/**
 * A message the server forwarded from one of the user's Grok Bot agents (its grokbot_bridge): a reply to
 * what a conversation sent, or a message that mentions @本机. It arrives as a user message (or a mid-turn
 * message) headed "【Grok Bot · name 的回复】" and closed by a note for the model; the app shows it as the
 * agent's own card, without that note.
 */
data class GrokBotMessage(val agent: String, val mention: Boolean, val text: String)

private val GROK_BOT_HEADER = Regex("""^【Grok Bot · (.+?)(?: 的回复| @本机)】""")
private const val GROK_BOT_NOTE = "（自动转发自 Grok Bot"

fun grokBotMessage(text: String): GrokBotMessage? {
    val header = GROK_BOT_HEADER.find(text) ?: return null
    val body = text.substring(header.range.last + 1).substringBeforeLast(GROK_BOT_NOTE).trim()
    return GrokBotMessage(header.groupValues[1].trim(), header.value.endsWith("@本机】"), body)
}
