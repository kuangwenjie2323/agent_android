package com.karewinkcloud.agentweb.client.ui

import com.karewinkcloud.agentweb.client.core.*
import org.junit.Assert.*
import org.junit.Test

class BackgroundFollowTest {
    @Test fun theOpenChatCarriesWhereItsStreamStoppedAndItsOpenQuestions() {
        val live = TurnState("open", RUN, lastSequence = 12_607, blocks = listOf(
            ChatBlock.Text("half an answer"),
            ChatBlock.Question("q1", "Which colour?", listOf("red", "blue")),
            ChatBlock.Question("q0", "Which size?", listOf("S", "M"), answer = "S")))
        val state = ClientState(chat = ChatState(conversation("open", running = true), live = live))

        val turn = handoverTurns(state).single()
        assertEquals("open", turn.conversationId); assertEquals(RUN, turn.controlId)
        assertEquals(12_607L, turn.from)
        assertEquals(listOf("q1"), turn.questions.map { it.id })  // the answered one is not announced again
        assertEquals(TurnState("open", RUN, lastSequence = 12_607, blocks = turn.questions), turn.resumeState())
    }

    @Test fun aTurnTheScreenNeverFollowedStartsAtTheFirstEvent() {
        val state = ClientState(conversations = listOf(conversation("listed", running = true)))
        val turn = handoverTurns(state).single()
        assertEquals(0L, turn.from)
        assertTrue(turn.questions.isEmpty())
        assertNull(turn.resumeState())  // the follower streams from event 1, as list turns always did
    }

    @Test fun theOpenChatIsNotHandedOverTwice() {
        val open = conversation("open", running = true)
        val state = ClientState(conversations = listOf(open), chat = ChatState(open,
            live = TurnState("open", RUN, lastSequence = 42)))
        assertEquals(listOf("open"), handoverTurns(state).map { it.conversationId })
        assertEquals(42L, handoverTurns(state).single().from)
    }

    @Test fun anIdleOpenChatIsNotFollowed() {
        val state = ClientState(chat = ChatState(conversation("open")))
        assertTrue(handoverTurns(state).isEmpty())
    }

    private fun conversation(id: String, running: Boolean = false) = Conversation(id, "Chat $id", false, running, false,
        if (running) RUN else null, null, ModelChoice("a", "model"))

    private companion object { const val RUN = "1234567890abcdef1234567890abcdef" }
}
