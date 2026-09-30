package com.karewinkcloud.agentweb.client.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TurnUsageTest {
    private fun json(text: String) = wireJson.parseToJsonElement(text).jsonObject
    private val run = "1234567890abcdef1234567890abcdef"
    private fun event(sequence: Long, type: String, body: String) = StreamEvent(EventId(run, sequence), type, json(body))

    @Test fun historyParsesProductionCountersAndPreservesUnknowns() {
        val message = ChatMessage.from(json("""{"id":7,"role":"assistant","usage":{"input_tokens":19524,"output_tokens":752,"cache_read_input_tokens":0,"duration_ms":4744}}"""))
        assertEquals("7", message.id)
        assertEquals(TurnUsage(19524, 752, 0, durationMs = 4744), message.usage)
        assertEquals(TurnUsage(31107, 140, reasoningTokens = 97), ChatMessage.from(json("""{"usage":{"input_tokens":31107,"output_tokens":140,"reasoning_tokens":97}}""")).usage)
        assertNull(ChatMessage.from(json("""{"content":"No usage"}""")).usage)
    }
    @Test fun missingInvalidNegativeAndFractionalCountersAreNotFabricated() {
        assertNull(TurnUsage.from(json("""{"input_tokens":-1,"output_tokens":1.5,"reasoning_tokens":"97","duration_ms":null,"cache_read_input_tokens":true}""")))
        assertNull(TurnUsage.from(json("""{"output_tokens":9223372036854775808}""")))
        assertNull(TurnUsage.from(json("""{}""")))
        assertEquals(TurnUsage(inputTokens = 0), TurnUsage.from(json("""{"input_tokens":0}""")))
    }
    @Test fun usageFinalAndDoneMergeWithoutEndingOnFinal() {
        var state = TurnState("chat", run, startedAt = 10)
        state = TurnReducer.reduce(state, event(1, "usage", """{"input_tokens":19524,"cache_read_input_tokens":0}"""), 20)
        state = TurnReducer.reduce(state, event(2, "final", """{"usage":{"output_tokens":752,"duration_ms":4744}}"""), 30)
        assertFalse(state.done)
        assertEquals(10L, state.startedAt)
        assertEquals(TurnUsage(19524, 752, 0, durationMs = 4744), state.usage)
        state = TurnReducer.reduce(state, event(3, "done", """{"conversationId":"chat","usage":{"reasoning_tokens":97,"input_tokens":-2}}"""), 40)
        assertTrue(state.done)
        assertEquals(40L, state.finishedAt)
        assertEquals(TurnUsage(19524, 752, 0, 97, 4744), state.usage)
    }
    @Test fun directFinalAndDoneCountersAreAcceptedAndUnknownEventsPreserveUsage() {
        var state = TurnState("chat", run)
        state = TurnReducer.reduce(state, event(1, "final", """{"input_tokens":10,"output_tokens":20}"""), 10)
        assertFalse(state.done)
        state = TurnReducer.reduce(state, event(2, "future_event", """{}"""), 20)
        state = TurnReducer.reduce(state, event(3, "done", """{"conversationId":"chat","duration_ms":80}"""), 30)
        assertEquals(TurnUsage(10, 20, durationMs = 80), state.usage)
    }
}
