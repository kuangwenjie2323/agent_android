package com.karewinkcloud.agentweb.client.ui

import com.karewinkcloud.agentweb.client.core.*
import com.karewinkcloud.agentweb.client.data.AgentRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * The Claude Code session list and a session's read-only history. A linked or adopted session is
 * handed to the view model's chat through [openChat]; everything else stays here. The repository is
 * read per call, so switching servers uses the new one.
 */
internal class ClaudeSessionsController(
    private val repository: () -> AgentRepository,
    private val scope: CoroutineScope,
    private val host: StateHost,
    /** The repository epoch a list read belongs to, and the chat generation a preview read belongs to. */
    private val epoch: () -> Long,
    private val generation: () -> Long,
    /** Advances the chat generation and returns it: a newer preview read replaces the older one. */
    private val nextGeneration: () -> Long,
    /** The view model's one stream job: showing a Claude screen takes it from the chat. */
    private val cancelSessionJob: () -> Unit,
    private val launchSessionJob: (suspend () -> Unit) -> Unit,
    /** Steps out of the open chat: flush its open's line, save its draft, drop its attachment owner. */
    private val leaveChat: () -> Unit,
    /** A preview has no live turn. */
    private val dropLiveTurn: () -> Unit,
    /** Shows a linked or adopted session as a normal chat. */
    private val openChat: (Conversation) -> Unit,
) {
    private var listJob: Job? = null
    private val historyCache = ConcurrentHashMap<String, ClaudePreviewState>()

    fun show() {
        if (host.state().signInRequired) return
        leaveChat()
        nextGeneration()
        cancelSessionJob()
        host.update { it.copy(tab = AppTab.CLAUDE, chat = null, claudeVisible = true, claudePreview = null) }
        refresh()
    }

    fun refresh(older: Boolean = false) {
        if (host.state().signInRequired) return
        if (listJob?.isActive == true) return
        val cursor = if (older) host.state().claude.nextCursor ?: return else null
        val source = repository()
        val owner = epoch()
        host.update { it.copy(claude = it.claude.copy(loading = true, error = null)) }
        listJob = scope.launch {
            try {
                if (!older && host.state().claude.sessions.isEmpty()) source.cachedClaudeSessions()?.let { saved ->
                    if (owner == epoch()) host.update { if (it.claude.sessions.isEmpty()) it.copy(claude = it.claude.copy(sessions = saved.sessions, nextCursor = saved.nextCursor)) else it }
                }
                val page = source.claudeSessions(cursor)
                if (owner == epoch()) host.update { it.copy(claude = ClaudeListState(
                    (if (older) it.claude.sessions + page.sessions else page.sessions).distinctBy { s -> s.id }, page.nextCursor)) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (owner == epoch()) host.update { it.copy(claude = it.claude.copy(loading = false, error = problem(e))) }
            }
        }
    }

    fun open(session: ClaudeSession) {
        if (host.state().signInRequired) return
        if (session.linkedConversationId != null) { openChat(session.conversation()); return }
        leaveChat()
        dropLiveTurn()
        val owner = nextGeneration()
        cancelSessionJob()
        val saved = historyCache[session.id]
        host.update { it.copy(chat = null, claudeVisible = true, claudePreview = ClaudePreviewState(session,
            saved?.messages.orEmpty(), saved?.nextCursor, saved?.olderCount ?: 0)) }
        val source = repository()
        launchSessionJob { loadPreview(source, owner, session.id) }
    }

    private fun editPreview(owner: Long, change: (ClaudePreviewState) -> ClaudePreviewState) {
        if (owner == generation()) host.update { it.copy(claudePreview = it.claudePreview?.let(change)) }
    }

    private suspend fun loadPreview(source: AgentRepository, owner: Long, id: String, cursor: String? = null) {
        try {
            val result = source.claudeHistory(id, cursor)
            editPreview(owner) { it.copy(session = result.session,
                messages = if (cursor == null) result.messages else (result.messages + it.messages).distinctBy { m -> m.id },
                nextCursor = result.nextCursor, olderCount = result.olderCount, loading = false, loadingOlder = false, error = null) }
            if (cursor == null) host.state().claudePreview?.takeIf { owner == generation() && it.session.id == id }?.let { historyCache[id] = it }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { editPreview(owner) { it.copy(loading = false, loadingOlder = false, error = problem(e)) } }
    }

    fun refreshPreview() {
        val preview = host.state().claudePreview ?: return
        if (preview.adopting) return
        open(preview.session.copy(linkedConversationId = null))
    }

    fun olderHistory() {
        val preview = host.state().claudePreview ?: return
        val cursor = preview.nextCursor ?: return
        if (preview.loading || preview.loadingOlder || preview.adopting) return
        val owner = generation()
        val source = repository()
        editPreview(owner) { it.copy(loadingOlder = true) }
        launchSessionJob { loadPreview(source, owner, preview.session.id, cursor) }
    }

    fun continueSession() {
        val preview = host.state().claudePreview ?: return
        if (preview.session.linkedConversationId != null) { openChat(preview.session.conversation()); return }
        if (!preview.canAdopt) return
        val owner = generation()
        val source = repository()
        editPreview(owner) { it.copy(adopting = true, error = null) }
        launchSessionJob {
            try {
                val id = source.adoptClaudeSession(preview.session.id)
                if (owner == generation() && host.state().claudePreview?.session?.id == preview.session.id) openChat(preview.session.conversation(id))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                editPreview(owner) { it.copy(adopting = false, error = problem(e),
                    session = if (e is ApiException && e.problem.code == "claude_session_active") it.session.copy(maybeActive = true) else it.session) }
            }
        }
    }

    /** The server changed or signed out: the saved history does not belong to it. */
    fun clearCache() { historyCache.clear() }
}
