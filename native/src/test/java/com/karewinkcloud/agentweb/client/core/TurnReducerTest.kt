package com.karewinkcloud.agentweb.client.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TurnReducerTest {
    private fun event(seq: Int, type: String, vararg fields: Pair<String, Any>, run: String = "run"): StreamEvent {
        val body = buildJsonObject {
            put("type", type); put("seq", seq)
            for ((key, value) in fields) when (value) {
                is Boolean -> put(key, value)
                is Number -> put(key, value)
                else -> put(key, value.toString())
            }
        }
        return StreamEvent.from(SseFrame("$run:$seq", body.toString()))
    }
    private val initial = TurnState("conversation", "run")

    @Test fun aStopTheUserAskedForIsANoticeNotAnError() {
        var state = initial
        val events = listOf(
            event(1, "tool_call", "id" to "t1", "name" to "Bash", "input" to "{}"),
            event(2, "tool_result", "id" to "t1", "content" to "Exit code 137", "is_error" to true),
            event(3, "error", "code" to "interrupted", "content" to "stopped by user", "retryable" to true),
            event(4, "done", "conversationId" to "conversation"))
        events.forEachIndexed { i, e -> state = TurnReducer.reduce(state, e, i.toLong()) }
        assertEquals(StepStatus.INTERRUPTED, (state.blocks.first() as ChatBlock.Tool).status)
        assertEquals(ChatBlock.Notice(STOPPED_NOTICE), state.blocks.last())
        assertTrue(state.blocks.none { it is ChatBlock.Error })
        val history = historyBlocks(buildJsonObject {
            put("status", "interrupted")
            putJsonArray("blocks") {
                addJsonObject { put("type", "tool"); put("id", "t1"); put("name", "Bash"); put("result", "Exit code 143"); put("is_error", true) }
                addJsonObject { put("type", "error"); put("code", "interrupted"); put("content", "stopped by user") }
            }
        })
        assertEquals(listOf(StepStatus.INTERRUPTED), history.filterIsInstance<ChatBlock.Tool>().map { it.status })
        assertEquals(1, history.count { it == ChatBlock.Notice(STOPPED_NOTICE) }); assertTrue(history.none { it is ChatBlock.Error })
    }
    @Test fun streamingTextAccumulatesWithoutLosingCjk() {
        var state = initial
        listOf("你", "好", "\n", "**AgentWeb**").forEachIndexed { i, text ->
            state = TurnReducer.reduce(state, event(i + 1, "text", "content" to text), i.toLong())
        }
        assertEquals("你好\n**AgentWeb**", state.text)
        assertEquals(1, state.blocks.size)
        assertFalse(state.done)
    }
    @Test fun heartbeatGoldenReachesDone() {
        val frames = SseParser().feed(fixture("f-05/events.sse"))
        val state = frames.fold(TurnState("conversation-fixture-f05", "run-fixture-f05")) { acc, frame ->
            TurnReducer.reduce(acc, StreamEvent.from(frame), 100)
        }
        assertTrue(state.done)
        assertEquals("heartbeat preserved connection state", state.text)
    }
    @Test fun structuredAndLegacyErrorGoldenPreservesBothAndAdvancesUnknownEvent() {
        val state = SseParser().feed(fixture("f-20/events.sse")).fold(TurnState("conversation-fixture-f20", "run-fixture-f20")) { acc, frame ->
            TurnReducer.reduce(acc, StreamEvent.from(frame), 100)
        }
        val errors = state.blocks.filterIsInstance<ChatBlock.Error>()
        assertEquals(2, errors.size)
        assertNull(errors[0].problem.retryable)
        assertEquals("future_retry_boundary", errors[1].problem.code)
        assertEquals(false, errors[1].problem.retryable)
        assertEquals(5L, state.lastSequence)
        assertTrue(state.done)
        assertEquals("known event after unknown sequence", state.text)
    }
    @Test fun thinkingAndToolTraceMeasureTimeAndPreserveFailure() {
        var state = TurnReducer.reduce(initial, event(1, "thinking_start"), 1000)
        state = TurnReducer.reduce(state, event(2, "thinking", "content" to "Checking files"), 1100)
        state = TurnReducer.reduce(state, event(3, "thinking_end"), 1600)
        state = TurnReducer.reduce(state, event(4, "tool_call", "id" to "t1", "name" to "Read", "input" to "file"), 1800)
        state = TurnReducer.reduce(state, event(5, "tool_result", "id" to "t1", "content" to "Missing file", "is_error" to true), 2200)
        assertEquals(600L, (state.blocks[0] as ChatBlock.Thought).durationMs)
        val tool = state.blocks[1] as ChatBlock.Tool
        assertEquals(StepStatus.ERROR, tool.status)
        assertEquals(400L, tool.durationMs)
        assertEquals("Missing file", tool.result)
    }
    @Test fun toolRefreshUpsertsInsteadOfInflatingCount() {
        var state = TurnReducer.reduce(initial, event(1, "tool_call", "id" to "t1", "name" to "Write", "input" to "before"), 0)
        state = TurnReducer.reduce(state, event(2, "tool_result", "id" to "t1", "content" to "ok", "is_error" to false), 5)
        state = TurnReducer.reduce(state, event(3, "tool_call", "id" to "t1", "name" to "Write", "input" to "after"), 10)
        assertEquals(1, state.blocks.size)
        assertEquals(StepStatus.COMPLETE, (state.blocks.single() as ChatBlock.Tool).status)
    }
    @Test fun duplicateIdsFromReconnectAreIgnored() {
        val first = event(1, "text", "content" to "one")
        val state = TurnReducer.reduce(initial, first, 1)
        assertSame(state, TurnReducer.reduce(state, first, 2))
        assertEquals("onetwo", TurnReducer.reduce(state, event(2, "text", "content" to "two"), 3).text)
    }
    @Test fun fullReplayOfSameRunDeduplicates() {
        val events = listOf(event(1, "text", "content" to "a"), event(2, "text", "content" to "b"))
        val partial = TurnReducer.reduce(initial, events.first(), 0)
        val replayed = events.fold(partial) { state, item -> TurnReducer.reduce(state, item, 10) }
        assertEquals("ab", replayed.text)
    }
    @Test fun sequenceGapFailsClosed() {
        assertThrows(StreamProtocolException::class.java) { TurnReducer.reduce(initial, event(2, "text", "content" to "missing"), 0) }
    }
    @Test fun foreignRunCannotMutateTurn() {
        assertThrows(StreamProtocolException::class.java) { TurnReducer.reduce(initial, event(1, "text", "content" to "x", run = "other"), 0) }
    }
    @Test fun errorAloneIsNotTerminal() {
        val state = TurnReducer.reduce(initial, event(1, "error", "content" to "Restarted", "code" to "server_restarted", "retryable" to false), 0)
        assertFalse(state.done)
    }
    @Test fun doneClosesUnresolvedToolsWithoutInventingSuccess() {
        var state = TurnReducer.reduce(initial, event(1, "tool_call", "id" to "tool", "name" to "Bash", "input" to "pwd"), 0)
        state = TurnReducer.reduce(state, event(2, "done", "conversationId" to "conversation"), 100)
        assertTrue(state.done)
        assertEquals(StepStatus.INTERRUPTED, (state.blocks.single() as ChatBlock.Tool).status)
    }
    @Test fun foreignDoneIsRejected() {
        assertThrows(StreamProtocolException::class.java) { TurnReducer.reduce(initial, event(1, "done", "conversationId" to "other"), 0) }
    }
    @Test fun eventsAfterDoneAreRejected() {
        val done = TurnReducer.reduce(initial, event(1, "done", "conversationId" to "conversation"), 0)
        assertThrows(StreamProtocolException::class.java) { TurnReducer.reduce(done, event(2, "text", "content" to "late"), 10) }
    }
    @Test fun persistedBlocksKeepErrorsAndToolStatus() {
        val message = wireJson.parseToJsonElement("""{"id":42,"role":"assistant","content":"answer","status":"error","blocks":[{"type":"thinking","content":"summary","duration":1.5},{"type":"tool","id":"t","name":"Read","result":"bad","is_error":true},{"type":"text","content":"answer"},{"type":"error","content":"cancelled","code":"provider_cancelled","retryable":false}]}""").jsonObject
        val parsed = ChatMessage.from(message)
        assertEquals("42", parsed.id)
        assertEquals("answer", parsed.text)
        assertEquals(1500L, (parsed.blocks[0] as ChatBlock.Thought).durationMs)
        assertEquals(StepStatus.ERROR, (parsed.blocks[1] as ChatBlock.Tool).status)
        assertEquals(false, (parsed.blocks[3] as ChatBlock.Error).problem.retryable)
    }
}
