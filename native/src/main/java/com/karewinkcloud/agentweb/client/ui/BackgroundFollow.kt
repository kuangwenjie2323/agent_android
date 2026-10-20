package com.karewinkcloud.agentweb.client.ui

import com.karewinkcloud.agentweb.client.AgentWorkService
import com.karewinkcloud.agentweb.client.core.ChatBlock

/**
 * The running turns to hand to the background follower. The open chat carries the last event its
 * stream saw and the questions still open on screen, so the follower resumes there instead of
 * replaying the turn from event 1, and still announces those questions. A turn from the list was
 * never followed on screen, so it starts at 0.
 */
internal fun handoverTurns(state: ClientState?): List<AgentWorkService.Turn> {
    val turns = mutableListOf<AgentWorkService.Turn>()
    state?.chat?.let { chat ->
        val control = chat.controlId?.takeIf { chat.running } ?: return@let
        val live = chat.live?.takeIf { it.runId == control && !it.done }
        turns += AgentWorkService.Turn(chat.conversation.id, control, chat.conversation.title,
            from = live?.lastSequence ?: 0,
            questions = live?.blocks.orEmpty().filterIsInstance<ChatBlock.Question>().filter { it.answer == null })
    }
    state?.conversations.orEmpty().forEach { conversation ->
        val control = conversation.controlId?.takeIf { conversation.running } ?: return@forEach
        if (turns.none { it.conversationId == conversation.id })
            turns += AgentWorkService.Turn(conversation.id, control, conversation.title)
    }
    return turns
}
