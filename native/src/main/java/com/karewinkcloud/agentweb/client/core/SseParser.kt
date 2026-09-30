package com.karewinkcloud.agentweb.client.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

data class SseFrame(val id: String?, val data: String)

/** Incremental, platform-free SSE framing. EOF never dispatches an unfinished block. */
class SseParser(private val maxFrameChars: Int = 2 * 1024 * 1024) {
    private val line = StringBuilder()
    private val data = StringBuilder()
    private var id: String? = null
    private var skipLf = false
    private var firstCharacter = true
    private var frameChars = 0

    fun feed(chunk: String): List<SseFrame> {
        val frames = mutableListOf<SseFrame>()
        for (c in chunk) {
            if (firstCharacter) {
                firstCharacter = false
                if (c == '\uFEFF') continue
            }
            if (skipLf && c == '\n') { skipLf = false; continue }
            skipLf = false
            if (++frameChars > maxFrameChars) throw StreamProtocolException("A stream frame exceeded the safe size limit.")
            if (c == '\n' || c == '\r') {
                finishLine(frames)
                skipLf = c == '\r'
            } else line.append(c)
        }
        return frames
    }

    private fun finishLine(frames: MutableList<SseFrame>) {
        val value = line.toString()
        line.setLength(0)
        if (value.isEmpty()) {
            if (data.isNotEmpty()) frames += SseFrame(id, data.dropLast(1).toString())
            data.setLength(0)
            id = null
            frameChars = 0
        } else if (!value.startsWith(':')) {
            val field = value.substringBefore(':')
            val content = value.substringAfter(':', "").removePrefix(" ")
            when (field) {
                "id" -> if ('\u0000' !in content) id = content
                "data" -> data.append(content).append('\n')
            }
        }
    }
}

data class EventId(val run: String, val sequence: Long) {
    override fun toString() = "$run:$sequence"
    companion object {
        fun parse(value: String?): EventId? {
            if (value == null || value.length > 256) return null
            val run = value.substringBeforeLast(':', "")
            val seq = value.substringAfterLast(':', "")
            if (run.isBlank() || seq.isEmpty() || seq.length > 12 || seq.any { it !in '0'..'9' }) return null
            return EventId(run, seq.toLong())
        }
    }
}

data class StreamEvent(val id: EventId, val type: String, val payload: JsonObject) {
    companion object {
        fun from(frame: SseFrame): StreamEvent {
            val id = EventId.parse(frame.id) ?: throw StreamProtocolException("The stream has an invalid event ID.")
            val json = runCatching { wireJson.parseToJsonElement(frame.data) as? JsonObject }.getOrNull()
                ?: throw StreamProtocolException("The stream contains malformed JSON.")
            val type = json.string("type")?.takeIf { it.isNotBlank() && it.length <= 80 }
                ?: throw StreamProtocolException("The stream is missing an event type.")
            if ((json["type"] as? JsonPrimitive)?.isString != true || (json["seq"] as? JsonPrimitive)?.isString != false ||
                id.sequence < 1 || json.long("seq") != id.sequence) {
                throw StreamProtocolException("The stream event ID and sequence disagree.")
            }
            val stringFields = when (type) {
                "text", "thinking", "error", "notice", "queued" -> listOf("content")
                "tool_call" -> listOf("id", "name")
                "tool_result" -> listOf("id", "content")
                "done" -> listOf("conversationId")
                else -> emptyList()
            }
            if (stringFields.any { (json[it] as? JsonPrimitive)?.isString != true } ||
                (type == "tool_call" && !json.containsKey("input")) ||
                (type == "tool_result" && ((json["is_error"] as? JsonPrimitive)?.isString != false ||
                    (json["is_error"] as? JsonPrimitive)?.booleanOrNull == null))) {
                throw StreamProtocolException("The stream event does not match the protocol schema.")
            }
            return StreamEvent(id, type, json)
        }
    }
}
