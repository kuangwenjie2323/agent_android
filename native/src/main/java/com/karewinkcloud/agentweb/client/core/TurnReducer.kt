package com.karewinkcloud.agentweb.client.core

data class TurnState(
    val conversationId: String, val runId: String, val lastSequence: Long = 0,
    val blocks: List<ChatBlock> = emptyList(), val done: Boolean = false,
    val startedAt: Long? = null, val finishedAt: Long? = null,
    val revealing: Boolean = false, val usage: TurnUsage? = null,
) {
    val lastEventId get() = EventId(runId, lastSequence).toString()
    val text get() = blocks.filterIsInstance<ChatBlock.Text>().joinToString("") { it.content }
}

/** Only server events change terminal truth. Callers inject a monotonic clock. */
object TurnReducer {
    fun reduce(state: TurnState, event: StreamEvent, now: Long): TurnState {
        if (event.id.run != state.runId) throw StreamProtocolException("The active run changed. Reload conversation history.")
        if (event.id.sequence <= state.lastSequence) return state
        if (event.id.sequence != state.lastSequence + 1) throw StreamProtocolException("The stream has a sequence gap. Reload conversation history.")
        if (state.done) throw StreamProtocolException("The stream sent an event after completion.")
        val j = event.payload
        val blocks = state.blocks.toMutableList()
        val reportedUsage = TurnUsage.from(j.obj("usage") ?: j.takeIf { event.type in setOf("usage", "final", "done") })
        val usage = state.usage?.merge(reportedUsage) ?: reportedUsage
        fun content(): String = j.string("content") ?: throw StreamProtocolException("The stream event has no content.")
        fun closeThought() {
            val index = blocks.indexOfLast { it is ChatBlock.Thought && it.status == StepStatus.RUNNING }
            if (index >= 0) {
                val b = blocks[index] as ChatBlock.Thought
                blocks[index] = b.copy(durationMs = (now - b.startedAt).coerceAtLeast(0), status = StepStatus.COMPLETE)
            }
        }
        when (event.type) {
            "text" -> {
                closeThought()
                val text = content()
                val last = blocks.lastOrNull()
                if (last is ChatBlock.Text) blocks[blocks.lastIndex] = last.copy(content = last.content + text)
                else blocks += ChatBlock.Text(text)
            }
            "thinking_start" -> { closeThought(); blocks += ChatBlock.Thought(startedAt = now) }
            "thinking" -> {
                val text = content()
                val last = blocks.lastOrNull()
                if (last is ChatBlock.Thought && last.status == StepStatus.RUNNING) {
                    blocks[blocks.lastIndex] = last.copy(content = last.content + text)
                } else blocks += ChatBlock.Thought(text, startedAt = now)
            }
            "thinking_end" -> closeThought()
            "tool_call" -> {
                closeThought()
                val id = j.string("id") ?: throw StreamProtocolException("Tool identity is missing.")
                val name = j.string("name") ?: throw StreamProtocolException("Tool name is missing.")
                val index = blocks.indexOfLast { it is ChatBlock.Tool && it.id == id }
                if (index >= 0) blocks[index] = (blocks[index] as ChatBlock.Tool).copy(name = name, input = j["input"].toString(), diff = j.string("diff") ?: (blocks[index] as ChatBlock.Tool).diff)
                else blocks += ChatBlock.Tool(id, name, j["input"].toString(), startedAt = now, diff = j.string("diff"))
            }
            "tool_result" -> {
                val id = j.string("id") ?: throw StreamProtocolException("Tool identity is missing.")
                val index = blocks.indexOfLast { it is ChatBlock.Tool && it.id == id }
                val b = if (index >= 0) blocks[index] as ChatBlock.Tool else ChatBlock.Tool(id, "Tool", startedAt = now)
                val result = b.copy(result = content(), status = if (j.boolean("is_error") == true) StepStatus.ERROR else StepStatus.COMPLETE,
                    durationMs = (now - b.startedAt).coerceAtLeast(0), diff = j.string("diff") ?: b.diff)
                if (index >= 0) blocks[index] = result else blocks += result
            }
            "error" -> blocks += ChatBlock.Error(ClientError(content(), j.string("code"), j.boolean("retryable")))
            "notice", "queued" -> blocks += ChatBlock.Notice(content())
            "steer" -> blocks += ChatBlock.Steer(content())
            "artifacts" -> {
                val media = j.objects("items").ifEmpty { j.objects("images") }.map(MessageMedia::from)
                // User uploads already live in the optimistic/history user bubble.
                if (media.isNotEmpty() && j.string("role") != "user") blocks += ChatBlock.Media(media)
            }
            "done" -> {
                if (j.string("conversationId") != state.conversationId) throw StreamProtocolException("Completion belongs to a different conversation.")
                closeThought()
                blocks.indices.forEach { i ->
                    val b = blocks[i]
                    if (b is ChatBlock.Tool && b.status == StepStatus.RUNNING) blocks[i] = b.copy(status = StepStatus.INTERRUPTED,
                        durationMs = (now - b.startedAt).coerceAtLeast(0))
                }
            }
            // Additive events still consume their sequence, including usage/artifacts.
        }
        return state.copy(lastSequence = event.id.sequence, blocks = blocks, done = event.type == "done",
            usage = usage, startedAt = state.startedAt ?: now, finishedAt = if (event.type == "done") now else null)
    }
}
