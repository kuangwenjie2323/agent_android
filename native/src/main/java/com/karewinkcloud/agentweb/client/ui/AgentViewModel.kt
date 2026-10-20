package com.karewinkcloud.agentweb.client.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.karewinkcloud.agentweb.client.core.*
import com.karewinkcloud.agentweb.client.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.io.IOException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class ChatState(
    val conversation: Conversation,
    val messages: List<ChatMessage> = emptyList(),
    val queued: List<QueueItem> = emptyList(), val queuePaused: Boolean = false,
    val olderCount: Int = 0, val nextCursor: String? = null,
    val loading: Boolean = true, val loadingOlder: Boolean = false,
    val draft: String = "", val live: TurnState? = null,
    /** Quick replies for the latest answer (message id -> chips); cleared once the user sends. */
    val suggestionsFor: String? = null, val suggestions: List<String> = emptyList(),
    val connection: Connection? = null, val error: ClientError? = null,
    val browserError: ClientError? = null,
    val controlBusy: Boolean = false, val stopRequested: Boolean = false,
    val pendingQueue: QueueRequest? = null, val queueUncertain: Boolean = false,
    val liveModel: String? = null, val beforeSendId: Long = 0, val revealing: Boolean = false,
    val attachments: List<Attachment> = emptyList(), val attachmentLoading: Boolean = false,
    /** Counts the user's sends (message, mid-turn message, queued one): each brings the list to the newest. */
    val sends: Int = 0,
    /** Side questions asked in this chat (kept while the app runs; never part of the chat). */
    val side: List<SideQuestion> = emptyList(),
) {
    val running get() = conversation.running || live?.done == false
    /** The turn that takes stop, mid-turn messages and answers: never one that has finished (a resumed queue's
     *  new turn replaced it, and a message sent to the old one was refused). */
    val controlId get() = live?.takeUnless { it.done }?.runId?.takeIf(String::isNotBlank) ?: conversation.controlId
    val hasDraft get() = draft.isNotBlank() || attachments.isNotEmpty()
    val canFork get() = !loading && !loadingOlder && !running && !revealing && !controlBusy && queued.isEmpty() &&
        pendingQueue == null && !attachmentLoading && !conversation.nativeControl
    val canSend get() = !loading && !attachmentLoading && !revealing && !running && queued.isEmpty() && !controlBusy &&
        pendingQueue == null && !conversation.nativeControl
}

enum class AppTab { CONVERSATIONS, CREATE, CLAUDE, SETTINGS }

data class ClientState(
    val conversations: List<Conversation> = emptyList(), val agents: List<Agent> = emptyList(),
    val choice: ModelChoice = ModelChoice(), val refreshing: Boolean = false, val creating: Boolean = false,
    val error: ClientError? = null, val chat: ChatState? = null,
    val claudeVisible: Boolean = false, val claude: ClaudeListState = ClaudeListState(),
    val claudePreview: ClaudePreviewState? = null,
    val signInRequired: Boolean = false,
    val search: String = "", val showEmpty: Boolean = false,
    val tab: AppTab = AppTab.CONVERSATIONS,
    val projects: ProjectSelection = ProjectSelection(), val projectLoading: Boolean = false,
    val projectError: ClientError? = null,
    /** Archived chats, like Claude desktop's archived sessions: how many, after how many idle days (0 = never),
     *  and the archive's rows while it is open (null when closed). */
    val archivedCount: Int = 0, val archiveDays: Int = 14, val archiveChoices: List<Int> = listOf(0, 7, 14, 30),
    val archivedList: List<Conversation>? = null, val archivedLoading: Boolean = false,
    /** Chats whose words match the search (the server's search, archived chats too) and whether it is still looking. */
    val contentHits: List<ChatHit> = emptyList(), val contentSearching: Boolean = false,
) {
    val sections get() = conversationSections(conversations, search, showEmpty)
}

data class ClaudeListState(val sessions: List<ClaudeSession> = emptyList(), val nextCursor: String? = null,
    val loading: Boolean = false, val error: ClientError? = null)
data class ClaudePreviewState(val session: ClaudeSession, val messages: List<ChatMessage> = emptyList(),
    val nextCursor: String? = null, val olderCount: Int = 0, val loading: Boolean = true,
    val loadingOlder: Boolean = false, val adopting: Boolean = false, val error: ClientError? = null) {
    val canAdopt get() = !loading && !loadingOlder && !adopting && error == null && !session.maybeActive && !session.historyLimited
}

/** All callbacks are bound to a repository epoch and a selected-conversation generation. */
class AgentViewModel(
    private var repository: AgentRepository,
    initialChoice: ModelChoice,
    initialSignInRequired: Boolean = false,
    /** Keep watching an open idle chat for turns the server starts on its own (off in tests: it never settles). */
    private val watchIdleChats: Boolean = false,
    /** When the user last looked at each chat, for the list's "new reply" dot. */
    private val seenChats: SeenChats = MemorySeenChats(),
    /** Whether the phone has a working network; a send without one fails at once instead of timing out. */
    private val online: () -> Boolean = { true },
    /** Where the background follower got to, so a return resumes from the furthest event of either stream. */
    private val handover: TurnHandover = TurnStreams,
    private val saveChoice: (ModelChoice) -> Unit,
) : ViewModel() {
    private val mutable = MutableStateFlow(ClientState(choice = initialChoice, signInRequired = initialSignInRequired))
    val state: StateFlow<ClientState> = mutable.asStateFlow()
    // Token-bearing blocks are observed only by the live LazyColumn item.
    val screenState = state.map(::screenSnapshot).distinctUntilChanged()
    private val livePresentation = MutableStateFlow<TurnState?>(null)
    private val seenMarks = MutableStateFlow<Map<String, Long>>(emptyMap())
    /** When the user last looked at each listed chat (seconds on the server's clock). */
    val seen: StateFlow<Map<String, Long>> = seenMarks.asStateFlow()
    private val sideQuestions = mutableMapOf<String, List<SideQuestion>>()
    /** When each unresolved send started (wall clock), to know when the server can no longer be taking it. */
    private val unresolvedAt = mutableMapOf<String, Long>()
    val liveTurn = livePresentation.asStateFlow()
    internal var frameClock: androidx.compose.runtime.MonotonicFrameClock? = null
    internal var appliedConnection: ServerConnection? = null
    private var generation = 0L
    // Foreground/history reconciliation changes the stream generation, but must not
    // invalidate an ActivityResult returning from the photo picker or camera.
    private var attachmentScope = newControlId()
    private var epoch = 0L
    private var projectVersion = 0L
    private var sessionJob: Job? = null
    private var listJob: Job? = null
    private var claudeListJob: Job? = null
    private var authJob: Job? = null
    private var previewsJob: Job? = null
    private var contentSearch: Job? = null
    private val previews = mutableMapOf<String, Conversation>()
    private val messageUsage = mutableMapOf<String, TurnUsage>()
    private val lineages = mutableMapOf<String, ConversationLineage>()
    private val unresolvedForks = mutableMapOf<String, ForkRequest>()
    private val forkRuns = mutableMapOf<String, String>()
    private val lineageTitleReads = mutableSetOf<String>()
    private val messageModels = mutableMapOf<String, String>()
    private val drafts = mutableMapOf<String, String>()
    private val attachmentDrafts = mutableMapOf<String, List<Attachment>>()
    private val unresolvedSends = mutableMapOf<String, SendRequest>()
    /** Last seen history per conversation: reopening shows it at once while a refresh runs. */
    /** History, and a reply still streaming when the chat was left (resumed from its last event, not replayed). */
    private data class CachedChat(val messages: List<ChatMessage>, val olderCount: Int, val nextCursor: String?, val live: TurnState? = null)
    private val chatCache = java.util.concurrent.ConcurrentHashMap<String, CachedChat>()
    /**
     * One open of a chat, from the tap to the fresh page: what was shown first, when, and whether the
     * server's page differed. Written as a single diagnostics line, so a later log says where a slow
     * or jumping open spent its time.
     */
    private class ChatOpen(val id: String, val startedAt: Long) {
        var source: String? = null
        var firstCount = 0
        var firstContentAt: Long? = null
        var freshAt: Long? = null
        var changed: Boolean? = null
        var firstVisible: Int? = null
        var total: Int? = null
        var written = false
    }
    /** The chat open being measured; one line per open (see [writeOpenReport]). */
    private var openReport: ChatOpen? = null
    /** Chats archived from the list this session, so an undo can put one back before the list is read again. */
    private val recentlyArchived = mutableMapOf<String, Conversation>()
    /** Pins and archive moves sent but not yet confirmed, by chat id: the latest one for each chat. */
    private val pendingChanges = java.util.concurrent.ConcurrentHashMap<String, PendingChange>()
    private class PendingChange(val pinned: Boolean?, val archived: Boolean?)
    /** Changes reach the server in the order they were made: an undo never overtakes the archive it undoes. */
    private val changeOrder = kotlinx.coroutines.sync.Mutex()
    // Read-only PC session history from this app run, shown at once while it refreshes.
    private val claudeHistoryCache = java.util.concurrent.ConcurrentHashMap<String, ClaudePreviewState>()
    private var prefetchJob: Job? = null
    private fun cacheChat() {
        state.value.chat?.takeIf { !it.loading && it.error == null }?.let { chat ->
            chatCache[chat.conversation.id] = CachedChat(chat.messages.filterNot { it.id.startsWith("pending-") }, chat.olderCount, chat.nextCursor,
                chat.live?.takeIf { !it.done && it.runId.isNotBlank() })
        }
    }
    private val unresolvedQueues = mutableMapOf<String, QueueRequest>()

    init { observeAuthentication(); refresh() }

    private fun observeAuthentication() {
        val owner = epoch
        authJob = viewModelScope.launch {
            repository.authenticationRequired.collect { required ->
                if (required && owner == epoch) requireSignIn()
            }
        }
    }

    private fun requireSignIn() {
        flushOpenReport()
        generation++; attachmentScope = newControlId(); epoch++; livePresentation.value = null
        repository.clearConversationCache()
        // Close followers and pending client requests; never send Stop or replay mutations.
        viewModelScope.coroutineContext.cancelChildren()
        drafts.clear(); attachmentDrafts.clear(); unresolvedSends.clear(); unresolvedQueues.clear(); chatCache.clear(); claudeHistoryCache.clear()
        previews.clear(); messageModels.clear(); messageUsage.clear(); lineages.clear(); unresolvedForks.clear(); forkRuns.clear(); lineageTitleReads.clear()
        mutable.value = ClientState(choice = state.value.choice, tab = state.value.tab, signInRequired = true, error = signInRequiredError)
    }

    fun changeServer(newRepository: AgentRepository, choice: ModelChoice, signInRequired: Boolean = false) {
        flushOpenReport()
        // Signed out or switched to a server with no credential: no request will run to clear it later,
        // and the previous account's saved chats and lists must not stay on the phone.
        if (signInRequired) repository.clearConversationCache()
        generation++; attachmentScope = newControlId(); epoch++; livePresentation.value = null
        viewModelScope.coroutineContext.cancelChildren()
        repository = newRepository
        drafts.clear(); attachmentDrafts.clear(); unresolvedSends.clear(); unresolvedQueues.clear(); chatCache.clear(); claudeHistoryCache.clear()
        previews.clear(); messageModels.clear(); messageUsage.clear(); lineages.clear(); unresolvedForks.clear(); forkRuns.clear(); lineageTitleReads.clear()
        // An open PC-session scope belongs to the old server; clear it on replacement.
        val tab = if (state.value.claudeVisible) AppTab.CONVERSATIONS else state.value.tab
        mutable.value = ClientState(choice = choice, tab = tab, signInRequired = signInRequired,
            claudeVisible = tab == AppTab.CLAUDE && !signInRequired)
        observeAuthentication()
        refresh()
        if (state.value.claudeVisible) refreshClaudeSessions()
    }

    fun refresh() {
        if (state.value.signInRequired) return
        if (listJob?.isActive == true) return
        val source = repository
        val owner = epoch
        val projectOwner = projectVersion
        mutable.update { it.copy(refreshing = true, error = null) }
        listJob = viewModelScope.launch {
            // Show the last saved list at once on a cold start; the network result replaces it.
            if (state.value.conversations.isEmpty()) {
                val savedAgents = source.cachedAgents()
                if (owner == epoch && savedAgents.isNotEmpty()) mutable.update { old -> if (old.agents.isEmpty()) old.copy(agents = savedAgents) else old }
                val cached = source.cachedConversations()
                if (owner == epoch && cached.isNotEmpty()) {
                    // Saved previews stay valid while a row's updated_at is unchanged.
                    cached.forEach { row -> if (row.messageCount != null) previews.putIfAbsent(row.id, row) }
                    mutable.update { old -> if (old.conversations.isEmpty()) old.copy(conversations = cached) else old }
                }
            }
            supervisorScope {
                val catalog = async { runCatching { readTwice { source.agents() } } }
                val projects = async { runCatching { readTwice { source.projects() } } }
                val archive = async { runCatching { readTwice { source.archiveInfo() } } }
                val conversations = runCatching { readTwice { source.conversations() } }
                val agents = catalog.await()
                val projectResult = projects.await()
                if (owner != epoch) return@supervisorScope
                archive.await().getOrNull()?.let { info ->
                    mutable.update { it.copy(archivedCount = info.archived, archiveDays = info.days, archiveChoices = info.choices) }
                }
                mutable.update { old ->
                    val choices = agents.getOrNull() ?: old.agents
                    val valid = choices.firstOrNull { it.id == old.choice.agent && it.available }
                        ?: choices.firstOrNull { it.available }
                    val choice = if (valid == null) old.choice else old.choice.copy(
                        agent = valid.id,
                        model = valid.resumeModel(old.choice.model).takeIf { it in valid.models } ?: valid.defaultModel,
                        effort = if (valid.supportsEffort) old.choice.effort else null,
                    )
                    old.copy(conversations = withPending(conversations.getOrNull()?.map { row ->
                        previews[row.id]?.takeIf { it.updatedAt == row.updatedAt && !row.running }?.let {
                            row.copy(preview = it.preview, messageCount = it.messageCount,
                                title = if (row.title.isBlank() || row.title.equals("New chat", true)) it.title else row.title)
                        } ?: row
                    } ?: old.conversations, old.conversations), agents = choices,
                        choice = choice, refreshing = false,
                        projects = if (projectOwner == projectVersion) projectResult.getOrNull()?.let(old.projects::refreshed) ?: old.projects else old.projects,
                        projectError = if (projectOwner == projectVersion) projectResult.exceptionOrNull()?.let(::problem) else old.projectError,
                        error = (conversations.exceptionOrNull() ?: agents.exceptionOrNull())?.let(::problem))
                }
                // Save at once (previews over a slow link can take a while); saved again with previews below.
                if (conversations.isSuccess && state.value.conversations.isNotEmpty()) launch { source.saveConversations(state.value.conversations) }
                loadPreviews()
            }
        }
    }

    /**
     * Only the chat rows, quietly: while a chat runs or waits on the user, the list keeps its statuses current
     * the way the session list in Claude's desktop app does (a full refresh also reloads models and projects).
     */
    fun refreshList() {
        if (state.value.signInRequired || listJob?.isActive == true || state.value.chat != null) return
        val source = repository; val owner = epoch
        listJob = viewModelScope.launch {
            val rows = try { source.conversations() } catch (e: CancellationException) { throw e } catch (_: Exception) { return@launch }
            if (owner != epoch) return@launch
            mutable.update { old -> old.copy(conversations = rows.map { row ->
                previews[row.id]?.takeIf { it.updatedAt == row.updatedAt && !row.running }?.let {
                    row.copy(preview = it.preview, messageCount = it.messageCount,
                        title = if (row.title.isBlank() || row.title.equals("New chat", true)) it.title else row.title)
                } ?: row
            }) }
            loadPreviews()
        }
    }

    /**
     * Filters the list by title at once and, from two characters on, also asks the server which chats' words match
     * (after a short pause in typing, so each keystroke is not a request), as Claude's search of past chats does.
     */
    fun search(value: String) {
        mutable.update { it.copy(search = value) }; loadPreviews()
        contentSearch?.cancel()
        val words = value.trim()
        if (words.length < 2) { mutable.update { it.copy(contentHits = emptyList(), contentSearching = false) }; return }
        val source = repository
        contentSearch = viewModelScope.launch {
            delay(350)
            mutable.update { it.copy(contentSearching = true) }
            val hits = try { source.searchChats(words) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            mutable.update { s -> if (s.search.trim() != words) s else s.copy(contentHits = hits ?: s.contentHits, contentSearching = false) }
        }
    }
    fun showEmpty(value: Boolean) { mutable.update { it.copy(showEmpty = value) } }

    // The list API has no previews/counts. Fetch only one message for up to 40 rows
    // per page of visible results, at most three at a time, and cache by updated_at.
    fun loadPreviews(ids: List<String> = state.value.conversations.take(40).map { it.id }) {
        if (state.value.signInRequired || previewsJob?.isActive == true) return
        val source = repository; val owner = epoch
        val rows = state.value.conversations.filter { it.id in ids && it.messageCount == null }.take(40)
        previewsJob = viewModelScope.launch {
            val permits = Semaphore(3)
            supervisorScope { rows.map { row -> launch { permits.withPermit {
                try {
                    val enriched = row.withPreview(source.preview(row.id))
                    if (owner == epoch) {
                        previews[row.id] = enriched
                        mutable.update { old -> old.copy(conversations = old.conversations.map {
                            if (it.id == row.id && it.updatedAt == row.updatedAt) enriched else it
                        }) }
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Unknown is visible; a failed read never hides a conversation. */ }
            } } }.joinAll() }
            if (owner == epoch && rows.isNotEmpty() && state.value.error == null) source.saveConversations(state.value.conversations)
        }
        prefetchRecent(owner)
    }

    /** Warm the history cache for the few most recent chats, so their first open is instant. */
    private fun prefetchRecent(owner: Long) {
        if (prefetchJob?.isActive == true) return
        val source = repository
        // The rows actually shown (empty placeholder chats are hidden), most recent first.
        val ids = state.value.sections.flatMap { it.conversations }.map { it.id }.filterNot { chatCache.containsKey(it) }.take(3)
        prefetchJob = viewModelScope.launch {
            for (id in ids) {
                if (owner != epoch || state.value.chat?.conversation?.id == id) continue
                try {
                    // The saved page makes the first open instant even before this network read lands.
                    if (!chatCache.containsKey(id)) source.cachedDetail(id)?.let { saved ->
                        if (owner == epoch && !chatCache.containsKey(id))
                            chatCache[id] = CachedChat(saved.messages, saved.olderCount, saved.nextCursor)
                    }
                    if (owner != epoch || state.value.chat?.conversation?.id == id) continue
                    val detail = source.detail(id)
                    if (owner == epoch && !chatCache.containsKey(id)) chatCache[id] = CachedChat(detail.messages, detail.olderCount, detail.nextCursor)
                } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            }
        }
    }

    fun choose(choice: ModelChoice) {
        val agent = state.value.agents.firstOrNull { it.id == choice.agent } ?: return
        if (!agent.available || !agent.enabled || choice.model !in agent.models) return
        val levels = agent.effortLevels[choice.model]
        val safe = choice.copy(effort = choice.effort.takeIf { agent.supportsEffort && (levels == null || it in levels) })
        mutable.update { it.copy(choice = safe) }
        saveChoice(safe)
    }

    fun newChat() = createChat(state.value.projects.selectedId)

    private fun createChat(projectId: String?, carry: ChatState? = null) {
        if (state.value.signInRequired) return
        if (state.value.creating) return
        val owner = epoch
        val nav = generation
        val source = repository
        val choice = state.value.choice
        mutable.update { it.copy(creating = true, error = null) }
        viewModelScope.launch {
            try {
                val conversation = source.createConversation(choice, projectId)
                if (owner != epoch) return@launch
                mutable.update { it.copy(conversations = listOf(conversation) + it.conversations, creating = false) }
                if (nav == generation) {
                    if (carry != null) {
                        drafts[conversation.id] = state.value.chat?.draft ?: carry.draft
                        attachmentDrafts[conversation.id] = state.value.chat?.attachments ?: carry.attachments
                        drafts.remove(carry.conversation.id); attachmentDrafts.remove(carry.conversation.id)
                        mutable.update { it.copy(chat = it.chat?.copy(draft = "", attachments = emptyList())) }
                    }
                    open(conversation, fresh = true)
                }
            } catch (e: Exception) {
                if (owner == epoch) mutable.update { it.copy(creating = false, error = problem(e),
                    chat = if (nav == generation) it.chat?.copy(error = problem(e)) else it.chat) }
            }
        }
    }

    fun selectProject(id: String?) {
        val old = state.value
        if (old.creating || old.projectLoading) return
        val selection = old.projects.select(id)
        if (id != null && selection.selectedId != id) return
        val chat = old.chat
        if (chat != null && (chat.loading || chat.running || chat.messages.isNotEmpty() || chat.attachmentLoading || chat.pendingQueue != null)) return
        mutable.update { it.copy(projects = selection) }
        if (chat != null && chat.conversation.projectId != id) createChat(id, chat)
    }

    fun registerProject(root: String, name: String) {
        if (state.value.projectLoading || root.isBlank()) return
        val owner = epoch; val nav = generation; val source = repository
        mutable.update { it.copy(projectLoading = true, projectError = null) }
        viewModelScope.launch {
            try {
                val project = source.registerProject(root, name)
                if (owner == epoch) {
                    projectVersion++
                    mutable.update { it.copy(projectLoading = false, projects = it.projects.refreshed(it.projects.projects.filterNot { p -> p.id == project.id } + project)) }
                    if (nav == generation) selectProject(project.id)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (owner == epoch) mutable.update { it.copy(projectLoading = false, projectError = problem(e)) } }
        }
    }

    /** [fresh] marks a conversation just created here: it is known to be empty, so skip the loading state. */
    fun open(conversation: Conversation, fresh: Boolean = false) {
        if (state.value.signInRequired) return
        flushOpenReport()
        val openedAt = monotonicMillis()
        markSeen(conversation.id, maxOf(conversation.updatedAt, System.currentTimeMillis() / 1000))
        saveDraft()
        attachmentScope = newControlId()
        val cached = chatCache[conversation.id]
        val report = ChatOpen(conversation.id, openedAt)
        openReport = report
        if (cached != null && cached.messages.isNotEmpty()) {
            report.source = "memory"; report.firstCount = cached.messages.size; report.firstContentAt = openedAt
        }
        // A reply that was streaming when this chat was left shows as it was and continues from its last
        // event; starting empty made the follower replay and rebuild the whole turn.
        val resumed = cached?.live?.takeIf { conversation.running && (conversation.controlId == null || it.runId == conversation.controlId) }
        val initial = resumed ?: if (conversation.running) TurnState(conversation.id, conversation.controlId.orEmpty(), startedAt = monotonicMillis()) else null
        livePresentation.value = initial
        val owner = ++generation
        sessionJob?.cancel()
        // Show this chat's own model and settings at once: waiting for its detail left the previous
        // chat's choice in the composer for a moment, which read as the screen jumping.
        if (!fresh) conversation.choice.takeIf { it.agent.isNotBlank() && it.model.isNotBlank() }?.let { choice ->
            val agent = state.value.agents.firstOrNull { it.id == choice.agent }
            mutable.update { it.copy(choice = choice.copy(model = agent?.resumeModel(choice.model) ?: choice.model,
                effort = if (agent?.supportsEffort == true) choice.effort else null)) }
        }
        mutable.update { it.copy(tab = if (it.claudeVisible) AppTab.CLAUDE else AppTab.CONVERSATIONS, claudePreview = null, chat = ChatState(withLineage(conversation), draft = drafts[conversation.id].orEmpty(),
            messages = cached?.messages.orEmpty(), olderCount = cached?.olderCount ?: 0, nextCursor = cached?.nextCursor,
            live = initial, side = sideQuestions[conversation.id].orEmpty(),
            connection = if (conversation.running) Connection.CONNECTING else null,
            attachments = attachmentDrafts[conversation.id].orEmpty(),
            pendingQueue = unresolvedQueues[conversation.id], queueUncertain = unresolvedQueues.containsKey(conversation.id), loading = !fresh && cached == null)) }
        // Creation only stores agent/model; its empty detail has default effort and
        // permissions. Keep the user's selection for the first send in a fresh chat.
        sessionJob = viewModelScope.launch { loadAndFollow(owner, applyChoice = !fresh, open = report) }
    }
    /** Opens a conversation by id (a tapped notification); reads the list first when it is not loaded yet. */
    fun openById(id: String) {
        if (state.value.signInRequired) return
        (state.value.conversations.firstOrNull { it.id == id } ?: state.value.archivedList?.firstOrNull { it.id == id })?.let { open(it); return }
        val owner = epoch; val source = repository
        viewModelScope.launch {
            // A chat found by search may be archived: it is not in the list.
            val found = try { source.conversations().firstOrNull { it.id == id } ?: source.archivedConversations().firstOrNull { it.id == id } }
                catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (found != null && owner == epoch) open(found)
        }
    }
    /** Chats the list shows for the first time count as read; the rest keep their marks. */
    fun adoptSeen(rows: List<Conversation>) {
        seenChats.adopt(rows)
        seenMarks.value = rows.associate { it.id to (seenChats.seenAt(it.id) ?: it.updatedAt) }
    }
    private fun markSeen(id: String, at: Long = System.currentTimeMillis() / 1000) {
        seenChats.markSeen(id, at)
        seenMarks.update { it + (id to (seenChats.seenAt(id) ?: at)) }
    }

    /** Records what a cold open showed first: memory or the disk copy, or nothing until the network. */
    private fun noteFirstContent(report: ChatOpen?, source: String, count: Int) {
        if (report == null || report.source != null) return
        report.source = source; report.firstCount = count
        if (count > 0) report.firstContentAt = monotonicMillis()
    }
    /** The chat list's first layout with messages, for the open's diagnostic line. */
    fun reportChatLayout(conversationId: String, firstVisible: Int, total: Int) {
        val report = openReport?.takeIf { it.id == conversationId } ?: return
        if (report.firstVisible != null) return
        report.firstVisible = firstVisible; report.total = total
        // The fresh page normally comes later; if it is already here, this completes the line.
        if (report.freshAt != null) writeOpenReport(report)
    }
    /** Writes the open's one line; fields not reported yet stay absent rather than guessed. */
    private fun writeOpenReport(report: ChatOpen) {
        if (report.written) return
        report.written = true
        Diagnostics.event("chat", "opened", "conversation" to report.id, "source" to (report.source ?: "none"),
            "messages" to report.firstCount,
            "firstContentMs" to report.firstContentAt?.let { it - report.startedAt },
            "freshMs" to report.freshAt?.let { it - report.startedAt },
            "changed" to report.changed,
            "firstVisible" to report.firstVisible, "total" to report.total)
        if (openReport === report) openReport = null
    }
    private fun flushOpenReport() { openReport?.let(::writeOpenReport) }

    /**
     * A side question, like the side chats in Claude's desktop app: answered from this chat's transcript
     * (the running turn included) without adding to the chat or reaching its agent.
     */
    fun askSide(question: String) {
        val chat = state.value.chat ?: return
        val text = question.trim()
        if (text.isEmpty() || chat.side.any { it.answer == null && !it.failed }) return
        if (!online()) { editChat(generation) { it.copy(error = ClientError(OFFLINE_MESSAGE, "offline", true)) }; return }
        val id = chat.conversation.id
        val source = repository
        val history = chat.side.filter { it.answer != null }
        editSide(id) { it + SideQuestion(text) }
        viewModelScope.launch {
            val reply = try { source.sideQuestion(id, text, history) } catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
            editSide(id) { list -> list.map { item ->
                if (item.question != text || item.answer != null || item.failed) item
                else if (reply.isNullOrBlank()) item.copy(failed = true) else item.copy(answer = reply)
            } }
        }
    }
    /** Drops a side question that could not be answered (to ask it again). */
    fun dropSide(question: SideQuestion) { state.value.chat?.conversation?.id?.let { id -> editSide(id) { it - question } } }
    private fun editSide(id: String, change: (List<SideQuestion>) -> List<SideQuestion>) {
        val next = change(sideQuestions[id].orEmpty())
        sideQuestions[id] = next
        mutable.update { current -> current.chat?.takeIf { it.conversation.id == id }?.let { current.copy(chat = it.copy(side = next)) } ?: current }
    }

    fun back() {
        flushOpenReport()
        state.value.chat?.conversation?.id?.let { markSeen(it) }
        cacheChat(); saveDraft(); generation++; attachmentScope = newControlId(); sessionJob?.cancel()
        val current = state.value
        mutable.update { it.copy(chat = null, claudePreview = null,
            claudeVisible = current.claudeVisible && (current.chat != null || current.claudePreview != null),
            tab = if (current.claudeVisible && (current.chat != null || current.claudePreview != null)) AppTab.CLAUDE else AppTab.CONVERSATIONS) }
        if (state.value.claudeVisible) refreshClaudeSessions() else refresh()
        // A message sent in an archived chat brought it back to the list: the open archive shows that.
        if (state.value.archivedList != null) loadArchive()
    }

    fun openArchive() {
        mutable.update { it.copy(archivedList = it.archivedList ?: emptyList(), archivedLoading = true) }
        loadArchive()
    }
    fun closeArchive() { mutable.update { it.copy(archivedList = null) } }
    private fun loadArchive() {
        val source = repository
        viewModelScope.launch {
            val rows = try { source.archivedConversations().filterNot { pendingChanges[it.id]?.archived == false } }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { mutable.update { it.copy(archivedLoading = false, error = problem(e)) }; return@launch }
            mutable.update { it.copy(archivedList = if (it.archivedList != null) rows else null, archivedLoading = false,
                archivedCount = rows.size) }
        }
    }

    fun setPinned(id: String, pinned: Boolean) = changeConversation(id, pinned = pinned)
    fun setArchived(id: String, archived: Boolean) = changeConversation(id, archived = archived)
    /**
     * Pins, archives or restores a chat. The screens follow at once, so a swipe does not wait on the network; a
     * refused change is undone the same way and says why. An archived open chat is left for the list.
     */
    private fun changeConversation(id: String, pinned: Boolean? = null, archived: Boolean? = null) {
        val source = repository
        val wasOpen = state.value.chat?.conversation?.id == id
        val change = PendingChange(pinned, archived)
        pendingChanges[id] = change
        moveLocally(id, pinned, archived)
        if (archived == true && wasOpen) back()
        viewModelScope.launch {
            try {
                changeOrder.withLock { source.updateConversation(id, pinned, archived) }
                listJob?.join()  // a list read that crossed the change ends with the change laid over it
                pendingChanges.remove(id, change)
                refresh()
            } catch (e: CancellationException) { pendingChanges.remove(id, change); throw e }
            catch (e: Exception) {
                pendingChanges.remove(id, change)
                moveLocally(id, pinned?.not(), archived?.not())
                mutable.update { s -> s.chat?.takeIf { it.conversation.id == id }?.let { chat -> s.copy(chat = chat.copy(error = problem(e))) }
                    ?: s.copy(error = problem(e)) }
            }
        }
    }

    /** A list read laid under the pins and archive moves the server has not confirmed yet. */
    private fun withPending(rows: List<Conversation>, shown: List<Conversation>): List<Conversation> {
        if (pendingChanges.isEmpty()) return rows
        val kept = rows.filterNot { pendingChanges[it.id]?.archived == true }
            .map { row -> pendingChanges[row.id]?.pinned?.let { row.copy(pinned = it) } ?: row }
        return kept + shown.filter { pendingChanges[it.id]?.archived == false && kept.none { row -> row.id == it.id } }
    }

    /** Moves a chat between the list and the archive, and pins it, in what the screens show. */
    private fun moveLocally(id: String, pinned: Boolean?, archived: Boolean?) {
        fun Conversation.changed() = copy(pinned = pinned ?: this.pinned, archived = archived ?: this.archived)
        mutable.update { s ->
            val current = s.conversations.find { it.id == id } ?: s.archivedList?.find { it.id == id }
                ?: s.chat?.conversation?.takeIf { it.id == id } ?: recentlyArchived[id]
            val moves = archived != null && current != null && current.archived != archived
            if (archived == true && current != null) recentlyArchived[id] = current.changed()
            if (archived == false) recentlyArchived.remove(id)
            s.copy(
                chat = s.chat?.let { chat -> if (chat.conversation.id == id) chat.copy(conversation = chat.conversation.changed()) else chat },
                conversations = when {
                    archived == true -> s.conversations.filterNot { it.id == id }
                    archived == false && current != null && s.conversations.none { it.id == id } -> s.conversations + current.changed()
                    else -> s.conversations.map { if (it.id == id) it.changed() else it }
                },
                archivedList = s.archivedList?.let { rows -> when {
                    archived == false -> rows.filterNot { it.id == id }
                    archived == true && current != null && rows.none { it.id == id } -> listOf(current.changed()) + rows
                    else -> rows.map { if (it.id == id) it.changed() else it }
                } },
                archivedCount = (s.archivedCount + if (!moves) 0 else if (archived == true) 1 else -1).coerceAtLeast(0))
        }
    }

    fun setArchiveDays(days: Int) {
        val source = repository
        mutable.update { it.copy(archiveDays = days) }
        viewModelScope.launch {
            try {
                source.setArchiveDays(days)?.let { info -> mutable.update { it.copy(archiveDays = info.days) } }
                refresh()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(error = problem(e)) } }
        }
    }
    private fun saveDraft() { state.value.chat?.let { drafts[it.conversation.id] = it.draft; attachmentDrafts[it.conversation.id] = it.attachments } }

    /** Deletes a chat for good (the user confirmed it): the screen leaves it and the list lets it go. */
    fun deleteConversation(id: String) {
        val owner = generation
        val source = repository
        val open = state.value.chat?.conversation?.id == id
        if (open) editChat(owner) { it.copy(controlBusy = true, error = null) }
        viewModelScope.launch {
            try {
                source.deleteConversation(id)
                if (state.value.chat?.conversation?.id == id) back()
                drafts.remove(id); attachmentDrafts.remove(id); chatCache.remove(id); unresolvedSends.remove(id); unresolvedQueues.remove(id)
                mutable.update { s -> s.copy(conversations = s.conversations.filterNot { c -> c.id == id },
                    archivedList = s.archivedList?.filterNot { c -> c.id == id },
                    archivedCount = if (s.archivedList?.any { c -> c.id == id } == true) (s.archivedCount - 1).coerceAtLeast(0) else s.archivedCount) }
                repository.saveConversations(state.value.conversations)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (open && owned(owner)) editChat(owner) { it.copy(controlBusy = false, error = problem(e)) }
                else mutable.update { it.copy(error = problem(e)) }
            }
        }
    }
    fun browserUnavailable() {
        mutable.update { it.copy(chat = it.chat?.copy(browserError =
            ClientError("Could not open a browser. Install or enable a browser and try again."))) }
    }
    fun dismissBrowserError() { mutable.update { it.copy(chat = it.chat?.copy(browserError = null)) } }
    fun draft(text: String) { mutable.update { it.copy(chat = it.chat?.copy(draft = text)) }; saveDraft() }
    fun attachmentOwner() = attachmentScope
    private fun ownsAttachments(owner: String) = owner == attachmentScope && state.value.chat != null
    fun attachmentLoading(owner: String, value: Boolean) { if (ownsAttachments(owner)) mutable.update { it.copy(chat = it.chat?.copy(attachmentLoading = value)) } }
    fun attachmentFailure(owner: String, message: String) { if (ownsAttachments(owner)) mutable.update { it.copy(chat = it.chat?.copy(attachmentLoading = false, error = ClientError(message))) } }
    fun readAttachments(owner: String, read: suspend () -> List<Attachment>) {
        if (!ownsAttachments(owner)) return
        attachmentLoading(owner, true)
        viewModelScope.launch {
            try {
                val items = read()
                if (!addAttachments(owner, items)) items.forEach { it.previewPath?.let { path -> java.io.File(path).delete() } }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { attachmentFailure(owner, if (e is IllegalArgumentException) e.message ?: "无法读取附件" else "无法读取附件，请重新选择") }
            finally { attachmentLoading(owner, false) }
        }
    }
    fun addAttachments(owner: String, items: List<Attachment>): Boolean {
        if (!ownsAttachments(owner)) return false
        val chat = state.value.chat ?: return false
        val combined = chat.attachments + items
        val error = attachmentError(combined.map { it.size }) ?: if (combined.any { it.kind == AttachmentKind.IMAGE } &&
            !modelReadsImages()) "当前模型不支持图片，请切换模型或移除图片" else null
        if (error != null) { attachmentFailure(owner, error); return false }
        // Bound in-memory drafts across conversations as well as per-message limits.
        val otherBytes = attachmentDrafts.filterKeys { it != chat.conversation.id }.values.flatten().sumOf { it.size.toLong() }
        if (otherBytes + combined.sumOf { it.size.toLong() } > 64L * 1024 * 1024) {
            attachmentFailure(owner, "附件草稿占用过多，请先发送或移除其他对话的附件"); return false
        }
        mutable.update { it.copy(chat = it.chat?.copy(attachments = combined, attachmentLoading = false, error = null)) }; saveDraft()
        return true
    }
    private fun modelReadsImages() = state.value.choice.let { choice ->
        state.value.agents.find { it.id == choice.agent }?.readsImages(choice.model) == true }
    fun removeAttachment(id: String) { mutable.update { it.copy(chat = it.chat?.copy(attachments = it.chat.attachments.filterNot { a -> a.id == id })) }; saveDraft() }
    private fun validAttachments(chat: ChatState): Boolean {
        if (chat.attachmentLoading) return false
        if (chat.attachments.any { it.kind == AttachmentKind.IMAGE } && !modelReadsImages()) {
            attachmentFailure(attachmentScope, "当前模型不支持图片，请切换模型或移除图片"); return false
        }
        return true
    }
    private fun owned(owner: Long) = owner == generation && state.value.chat != null
    private fun editChat(owner: Long, transform: (ChatState) -> ChatState) {
        if (owned(owner)) mutable.update { it.copy(chat = it.chat?.let(transform)) }
    }

    fun reload() {
        if (state.value.chat?.controlBusy == true) return
        if (state.value.chat == null) { refresh(); return }
        val owner = ++generation
        sessionJob?.cancel()
        // A retry after the first page failed has no messages yet: show the loading state again,
        // so the list is recreated at its tail instead of staying anchored at item 0.
        editChat(owner) { it.copy(error = null, loading = it.messages.isEmpty() && it.live == null) }
        // The open's line has not been written yet when the first page failed: this read can finish it.
        val report = openReport?.takeIf { !it.written && it.id == state.value.chat?.conversation?.id }
        sessionJob = viewModelScope.launch { loadAndFollow(owner, open = report) }
    }
    /**
     * Leaving the app hands a running turn to the notification follower, so the screen's own stream stops: a
     * slow link then carries the turn once, not twice (10-19: two streams through a proxy, each reconnecting).
     * Coming back reloads and resumes from the last event seen. A message still on its way is never cut off.
     * Returns false when a control request keeps the screen's stream here (then the turn is not handed over).
     */
    fun yieldStream(): Boolean {
        val chat = state.value.chat ?: return true
        if (!chat.running) return true
        if (chat.controlBusy) return false
        chat.live?.runId?.takeIf(String::isNotBlank)?.let { handover.screenStopped(chat.conversation.id, it) }
        chat.controlId?.let { handover.screenStopped(chat.conversation.id, it) }
        sessionJob?.cancel()
        return true
    }
    /** The app is on screen; an idle open chat watches the server only then. */
    @Volatile private var inForeground = true
    private var backgroundSince = 0L
    private var foregroundToken = 0L
    fun onBackground() {
        inForeground = false; backgroundSince = System.currentTimeMillis(); foregroundToken++
    }
    fun onForeground() {
        val stale = backgroundSince > 0 && System.currentTimeMillis() - backgroundSince > 15_000
        backgroundSince = 0
        val token = ++foregroundToken
        if (!stale) { inForeground = true; resumeForeground(); return }
        viewModelScope.launch {
            // evictAll() closes TLS sockets synchronously; on the main thread Conscrypt's close
            // write trips NetworkOnMainThreadException and kills the app on every return.
            // Stay "away" until it finishes, so the idle poll cannot start on the old pool.
            withContext(Dispatchers.IO) { repository.freshConnections() }
            if (token != foregroundToken) return@launch
            inForeground = true
            resumeForeground()
        }
    }
    private fun resumeForeground() {
        if (!inForeground || state.value.signInRequired) return
        if (state.value.claudeVisible && state.value.chat == null) {
            if (sessionJob?.isActive != true && state.value.claudePreview != null) refreshClaudePreview()
            else if (state.value.claudePreview == null) refreshClaudeSessions()
        } else if (sessionJob?.isActive != true && state.value.chat != null) reload()
        else if (state.value.chat == null) refresh()
    }

    fun selectTab(tab: AppTab) {
        if (tab == AppTab.CLAUDE && !state.value.signInRequired) { showClaudeSessions(); return }
        flushOpenReport()
        saveDraft(); generation++; attachmentScope = newControlId(); sessionJob?.cancel()
        mutable.update { it.copy(tab = tab, chat = null, claudeVisible = false, claudePreview = null) }
        if (tab == AppTab.CONVERSATIONS) refresh()
    }

    fun showClaudeSessions() {
        if (state.value.signInRequired) return
        flushOpenReport()
        saveDraft(); generation++; attachmentScope = newControlId(); sessionJob?.cancel()
        mutable.update { it.copy(tab = AppTab.CLAUDE, chat = null, claudeVisible = true, claudePreview = null) }
        refreshClaudeSessions()
    }

    /** Load the PC session list before the Claude Code tab is first opened. */
    fun preloadClaudeSessions() { if (state.value.claude.sessions.isEmpty()) refreshClaudeSessions() }

    fun refreshClaudeSessions(older: Boolean = false) {
        if (state.value.signInRequired) return
        if (claudeListJob?.isActive == true) return
        val cursor = if (older) state.value.claude.nextCursor ?: return else null
        val source = repository
        val owner = epoch
        mutable.update { it.copy(claude = it.claude.copy(loading = true, error = null)) }
        claudeListJob = viewModelScope.launch {
            try {
                if (!older && state.value.claude.sessions.isEmpty()) source.cachedClaudeSessions()?.let { saved ->
                    if (owner == epoch) mutable.update { if (it.claude.sessions.isEmpty()) it.copy(claude = it.claude.copy(sessions = saved.sessions, nextCursor = saved.nextCursor)) else it }
                }
                val page = source.claudeSessions(cursor)
                if (owner == epoch) mutable.update { it.copy(claude = ClaudeListState(
                    (if (older) it.claude.sessions + page.sessions else page.sessions).distinctBy { s -> s.id }, page.nextCursor)) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (owner == epoch) mutable.update { it.copy(claude = it.claude.copy(loading = false, error = problem(e))) }
            }
        }
    }

    fun openClaudeSession(session: ClaudeSession) {
        if (state.value.signInRequired) return
        if (session.linkedConversationId != null) { open(session.conversation()); return }
        flushOpenReport()
        saveDraft()
        attachmentScope = newControlId()
        livePresentation.value = null
        val owner = ++generation
        sessionJob?.cancel()
        val saved = claudeHistoryCache[session.id]
        mutable.update { it.copy(chat = null, claudeVisible = true, claudePreview = ClaudePreviewState(session,
            saved?.messages.orEmpty(), saved?.nextCursor, saved?.olderCount ?: 0)) }
        val source = repository
        sessionJob = viewModelScope.launch { loadClaudePreview(source, owner, session.id) }
    }

    private fun editPreview(owner: Long, change: (ClaudePreviewState) -> ClaudePreviewState) {
        if (owner == generation) mutable.update { it.copy(claudePreview = it.claudePreview?.let(change)) }
    }

    private suspend fun loadClaudePreview(source: AgentRepository, owner: Long, id: String, cursor: String? = null) {
        try {
            val result = source.claudeHistory(id, cursor)
            editPreview(owner) { it.copy(session = result.session,
                messages = if (cursor == null) result.messages else (result.messages + it.messages).distinctBy { m -> m.id },
                nextCursor = result.nextCursor, olderCount = result.olderCount, loading = false, loadingOlder = false, error = null) }
            if (cursor == null) state.value.claudePreview?.takeIf { owner == generation && it.session.id == id }?.let { claudeHistoryCache[id] = it }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { editPreview(owner) { it.copy(loading = false, loadingOlder = false, error = problem(e)) } }
    }

    fun refreshClaudePreview() {
        val preview = state.value.claudePreview ?: return
        if (preview.adopting) return
        openClaudeSession(preview.session.copy(linkedConversationId = null))
    }

    fun olderClaudeHistory() {
        val preview = state.value.claudePreview ?: return
        val cursor = preview.nextCursor ?: return
        if (preview.loading || preview.loadingOlder || preview.adopting) return
        val owner = generation
        val source = repository
        editPreview(owner) { it.copy(loadingOlder = true) }
        sessionJob = viewModelScope.launch { loadClaudePreview(source, owner, preview.session.id, cursor) }
    }

    fun continueClaudeSession() {
        val preview = state.value.claudePreview ?: return
        if (preview.session.linkedConversationId != null) { open(preview.session.conversation()); return }
        if (!preview.canAdopt) return
        val owner = generation
        val source = repository
        editPreview(owner) { it.copy(adopting = true, error = null) }
        sessionJob = viewModelScope.launch {
            try {
                val id = source.adoptClaudeSession(preview.session.id)
                if (owner == generation && state.value.claudePreview?.session?.id == preview.session.id) open(preview.session.conversation(id))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                editPreview(owner) { it.copy(adopting = false, error = problem(e),
                    session = if (e is ApiException && e.problem.code == "claude_session_active") it.session.copy(maybeActive = true) else it.session) }
            }
        }
    }

    /** True once the open, idle conversation changed on the server; false when the chat was left. Checks only
     *  in the foreground, against the light list row (running, last update, message count). */
    private suspend fun awaitServerActivity(owner: Long, id: String): Boolean {
        var baseline: Conversation? = null
        while (owned(owner)) {
            delay(if (baseline == null) 1_000 else 6_000)
            if (!inForeground) continue
            val row = try { repository.conversations().firstOrNull { it.id == id } } catch (e: CancellationException) { throw e }
                catch (_: Exception) { null } ?: continue
            val before = baseline
            if (before == null) { baseline = row; if (row.running) return true; continue }
            if (row.running || row.updatedAt != before.updatedAt || row.messageCount != before.messageCount) return true
        }
        return false
    }

    /** Shows the page saved on the phone at once, off the main thread, while the network read runs. */
    private suspend fun showDiskPage(owner: Long, id: String, open: ChatOpen?) {
        val current = state.value.chat ?: return
        if (current.messages.isNotEmpty()) return
        val saved = try { repository.cachedDetail(id) } catch (e: CancellationException) { throw e } catch (_: Exception) { null } ?: return
        if (!owned(owner) || state.value.chat?.conversation?.id != id) return
        editChat(owner) { chat ->
            if (chat.messages.isNotEmpty()) chat else chat.copy(
                messages = saved.messages, queued = saved.queued, queuePaused = saved.queuePaused,
                olderCount = saved.olderCount, nextCursor = saved.nextCursor, loading = false)
        }
        val shown = state.value.chat?.takeIf { it.conversation.id == id }?.messages.orEmpty()
        if (shown.isNotEmpty()) noteFirstContent(open, "disk", shown.size)
    }

    private suspend fun loadAndFollow(owner: Long, applyChoice: Boolean = false, allowReconcile: Boolean = true,
        open: ChatOpen? = null) {
        try {
            val id = state.value.chat?.conversation?.id ?: return
            var first = true
            var missingStreams = 0
            var settleWaited = false
            var readDisk = false
            while (owned(owner)) {
                // The page saved on the phone shows at once; the network read below still always runs
                // and replaces it. Reading it off the main thread keeps the chat screen responsive.
                if (!readDisk) { readDisk = true; showDiskPage(owner, id, open) }
                val detail = repository.detail(id)
                if (!owned(owner)) return
                var unresolved = unresolvedSends[id]
                val forkRun = forkRuns[id]
                val priorChat = state.value.chat ?: return
                // A send whose stream dropped is settled once the server has saved its message and
                // nothing runs: a later (queued) turn may own the stream now, so following the old
                // run would fail forever with "run changed" and keep the chat looking busy.
                if (unresolved != null && !detail.conversation.running && unresolvedSettled(unresolved, detail.messages, priorChat.beforeSendId)) {
                    unresolvedSends.remove(id); unresolved = null
                }
                val prior = priorChat.live
                // Coming back resumes after the last event this screen showed: only what happened while it was
                // away is read again. (Starting where the background follower got to would leave a hole in the
                // answer on screen until the turn ends.)
                val resume = prior
                // Attribute only a completed locally observed turn to its captured send model.
                // The API does not persist models for all historical messages.
                val last = detail.messages.lastOrNull()
                if (prior?.done == true && last?.role == "assistant" &&
                    (last.id.toLongOrNull() ?: 0) > priorChat.beforeSendId && last.text == prior.text) {
                    priorChat.liveModel?.let { messageModels["$id:${last.id}"] = it }
                    prior.usage?.let { messageUsage["$id:${last.id}"] = it }
                }
                val firstId = detail.messages.firstOrNull()?.id?.toLongOrNull()
                val prefix = if (firstId == null) emptyList() else priorChat.messages.filter { (it.id.toLongOrNull() ?: Long.MAX_VALUE) < firstId }
                val messages = prefix + detail.messages.map { message ->
                    message.copy(model = message.model ?: messageModels["$id:${message.id}"],
                        usage = messageUsage["$id:${message.id}"]?.merge(message.usage) ?: message.usage)
                }
                // A cold open served from the disk copy can get back exactly what it already shows.
                // Writing that back gives the list a new identity and re-lays it out (the jump seen
                // on the phone), so an unchanged page leaves the whole state object untouched.
                val sameContent = sameMessages(messages, priorChat.messages)
                val next = priorChat.copy(conversation = withLineage(detail.conversation),
                    messages = if (sameContent) priorChat.messages else messages,
                    queued = detail.queued, queuePaused = detail.queuePaused,
                    olderCount = if (prefix.isEmpty()) detail.olderCount else priorChat.olderCount,
                    nextCursor = if (prefix.isEmpty()) detail.nextCursor else priorChat.nextCursor, loading = false,
                    // A message given back to the box keeps its note until the user acts on it.
                    error = priorChat.error?.takeIf { it.code == "not_sent" },
                    live = (unresolved?.controlId ?: forkRun ?: detail.conversation.controlId?.takeIf { detail.conversation.running })?.let { run ->
                        resume?.takeIf { p -> p.runId == run && !p.done } ?: TurnState(id, run,
                            startedAt = prior?.takeIf { p -> p.runId.isBlank() && !p.done }?.startedAt ?: monotonicMillis())
                    } ?: resume?.takeIf { detail.conversation.running && !it.done && it.runId.isBlank() },
                    connection = null, stopRequested = false, revealing = false)
                if (next != priorChat) editChat(owner) { next }
                if (open != null) {
                    val now = monotonicMillis()
                    if (open.freshAt == null) {
                        open.freshAt = now
                        // The first layout is usually known by now; otherwise let the screen report it,
                        // with a fallback so leaving the chat at once still leaves the line in the log.
                        if (open.firstVisible != null || messages.isEmpty()) writeOpenReport(open) else {
                            val report = open
                            viewModelScope.launch { delay(750); writeOpenReport(report) }
                        }
                    }
                    if (open.source == null) {
                        open.source = "none"; open.firstCount = messages.size
                        if (messages.isNotEmpty()) open.firstContentAt = now
                    }
                    if (open.changed == null) open.changed = !sameContent
                }
                cacheChat()
                resolveParentTitle(owner)
                if (applyChoice && first) {
                    val choice = detail.conversation.choice
                    val agent = state.value.agents.firstOrNull { it.id == choice.agent }
                    mutable.update { it.copy(choice = choice.copy(model = agent?.resumeModel(choice.model) ?: choice.model,
                        effort = if (agent?.supportsEffort == true) choice.effort else null)) }
                }
                first = false
                if (detail.conversation.running || unresolved != null || forkRun != null) {
                    val run = detail.conversation.controlId ?: unresolved?.controlId ?: forkRun
                    // An observed successor proves the old turn is no longer our active owner.
                    if (unresolved != null && run != unresolved.controlId) unresolvedSends.remove(id)
                    try {
                        follow(owner, id, run, previous = resume?.takeIf { it.runId == run && !it.done })
                    } catch (e: ApiException) {
                        if (e.status != 404 || e.problem.code !in setOf("no_active_stream", null)) throw e
                        if (detail.conversation.running && ++missingStreams < 3) { delay(1000); continue }
                        if (detail.conversation.running) throw e
                        // A send whose connection broke mid-way may still be on its way in: hold it until the
                        // server cannot still be taking it, so the user never sends it twice.
                        if (unresolved != null && !settleWaited) {
                            val age = System.currentTimeMillis() - (unresolvedAt[id] ?: 0L)
                            if (age < SEND_SETTLE_MS) { settleWaited = true; delay(SEND_SETTLE_MS - age); continue }
                        }
                        // The server has no stream for this run and nothing runs: the turn ended unseen, or a
                        // send never arrived. Stop waiting on it (the chat stayed "running" and refused new
                        // messages until the app was killed) and give back a message the server never saved.
                        unresolved?.let { lost -> if (!unresolvedSettled(lost, detail.messages, priorChat?.beforeSendId ?: 0)) restoreUnsent(owner, lost) }
                        unresolvedSends.remove(id); forkRuns.remove(id)
                        editChat(owner) { it.copy(live = null, connection = null) }
                        continue
                    }
                    if (!owned(owner)) return
                    unresolvedSends.remove(id)
                    forkRuns.remove(id)
                    // The done event may precede durable queued-turn handoff. Query until it settles.
                    delay(200)
                    continue
                }
                if (detail.queued.isEmpty() || detail.queuePaused) {
                    // Idle and open: keep a light watch, so a turn the server starts on its own (a Grok Bot
                    // reply, a follow-up queued elsewhere) shows up live instead of only after reopening.
                    if (!watchIdleChats || !awaitServerActivity(owner, id)) return
                    continue
                }
                delay(1000)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: StreamProtocolException) {
            if (allowReconcile && owned(owner)) {
                editChat(owner) { it.copy(live = null) }
                loadAndFollow(owner, allowReconcile = false, open = open)
            } else {
                // Reconciling failed too: stop following, so the chat is usable again instead of spinning.
                state.value.chat?.conversation?.id?.let { unresolvedSends.remove(it) }
                editChat(owner) { it.copy(loading = false, error = problem(e), live = null, connection = null) }
            }
        }
        catch (e: Exception) {
            editChat(owner) { it.copy(loading = false, error = problem(e)) }
        }
    }

    /** A send the server never saved: its words and files go back in the box, with a plain note. */
    private fun restoreUnsent(owner: Long, send: SendRequest) {
        editChat(owner) { current -> current.copy(
            draft = current.draft.ifEmpty { send.text },
            attachments = current.attachments.ifEmpty { send.attachments },
            messages = current.messages.filterNot { it.id == "pending-${send.controlId}" },
            error = ClientError(NOT_SENT_MESSAGE, "not_sent", true)) }
        saveDraft()
    }

    private fun unresolvedSettled(send: SendRequest, messages: List<ChatMessage>, beforeSendId: Long): Boolean {
        val text = send.text.trim()
        return messages.any { message -> message.role == "user" && (message.id.toLongOrNull() ?: 0) > beforeSendId &&
            (text.isEmpty() || message.text.contains(text)) }
    }

    private suspend fun follow(owner: Long, id: String, run: String?, send: SendRequest? = null, previous: TurnState? = null) = coroutineScope {
        val startedAt = previous?.startedAt ?: state.value.chat?.live?.takeIf { it.runId == run || it.runId.isBlank() }?.startedAt ?: monotonicMillis()
        val initial = previous?.copy(startedAt = startedAt) ?: run?.let { TurnState(id, it, startedAt = startedAt) }
        val visibleInitial = initial ?: TurnState(id, "", startedAt = startedAt)
        val streamRun = initial?.runId.orEmpty()
        if (streamRun.isNotBlank() && !handover.screenStarted(id, streamRun))
            Diagnostics.warn("stream", "the screen took the turn back while the follower still held it", "run" to streamRun)
        Diagnostics.event("stream", "follow started", "who" to "screen", "from" to (initial?.lastSequence ?: 0), "run" to streamRun)
        if (owned(owner)) livePresentation.value = visibleInitial
        editChat(owner) { it.copy(live = visibleInitial, connection = Connection.CONNECTING, loading = false) }
        var latest: TurnSnapshot? = null
        val pacer = StreamPacer()
        val updates = Channel<Unit>(Channel.CONFLATED)
        var frameTime = 0L
        var arrivalTime = 0L
        val painter = launch {
            for (signal in updates) {
                do {
                    val clock = frameClock
                    var minimumElapsed = 16L
                    if (clock != null) {
                        try {
                            // Rotation can dispose the old clock; backgrounding can pause it.
                            if (withTimeoutOrNull(64) { clock.withFrameNanos { true } } != true) minimumElapsed = 64
                        } catch (e: CancellationException) {
                            currentCoroutineContext().ensureActive()
                            delay(16)
                        }
                    } else delay(16)
                    val now = System.nanoTime() / 1_000_000
                    frameTime += if (arrivalTime == 0L) minimumElapsed else (now - arrivalTime).coerceAtLeast(minimumElapsed)
                    arrivalTime = now
                    val snapshot = latest ?: break
                    val shown = pacer.present(snapshot.turn, frameTime)
                    if (owned(owner)) livePresentation.value = shown
                    editChat(owner) { it.copy(live = snapshot.turn, connection = snapshot.connection, loading = false, revealing = shown.revealing) }
                } while (pacer.pending)
                arrivalTime = 0
            }
        }
        try {
            repository.follow(id, run, send, initial).collect { snapshot ->
                handover.note(id, snapshot.turn.runId, snapshot.turn.lastSequence)
                latest = snapshot.copy(turn = snapshot.turn.copy(startedAt = startedAt))
                pacer.offer(snapshot.turn.blocks.sumOf { (it as? ChatBlock.Text)?.content?.length ?: 0 }, frameTime)
                updates.trySend(Unit)
            }
            updates.close()
            painter.join() // Finish revealing before replacing the live item with durable history.
        } catch (e: Exception) {
            if (currentCoroutineContext().isActive) { updates.close(); painter.join() }
            throw e
        } finally {
            updates.close(); painter.cancel()
            if (streamRun.isNotBlank()) handover.screenStopped(id, streamRun)
        }
    }

    fun send() {
        if (state.value.creating) return
        val chat = state.value.chat ?: return
        if (!chat.canSend || !chat.hasDraft || !validAttachments(chat)) return
        if (!online()) { editChat(generation) { it.copy(error = ClientError(OFFLINE_MESSAGE, "offline", true)) }; return }
        val request = SendRequest(chat.conversation.id, chat.draft, state.value.choice, attachments = chat.attachments)
        attachmentScope = newControlId()
        val owner = ++generation
        sessionJob?.cancel()
        val initial = TurnState(request.conversationId, request.controlId, startedAt = monotonicMillis())
        livePresentation.value = initial
        unresolvedSends[request.conversationId] = request
        unresolvedAt[request.conversationId] = System.currentTimeMillis()
        drafts[request.conversationId] = ""
        attachmentDrafts.remove(request.conversationId)
        editChat(owner) { it.copy(draft = "", attachments = emptyList(), error = null, revealing = false, live = initial,
            sends = it.sends + 1, liveModel = request.choice.model, beforeSendId = it.messages.mapNotNull { message -> message.id.toLongOrNull() }.maxOrNull() ?: 0,
            connection = Connection.CONNECTING, messages = it.messages + ChatMessage("pending-${request.controlId}", "user",
                listOf(ChatBlock.Text(request.text)) + if (request.attachments.isNotEmpty()) listOf(ChatBlock.Media(request.attachments.map(Attachment::media))) else emptyList())) }
        sessionJob = viewModelScope.launch {
            try {
                follow(owner, request.conversationId, request.controlId, request)
                unresolvedSends.remove(request.conversationId)
                loadAndFollow(owner)
            } catch (e: CancellationException) { throw e }
            catch (e: StreamProtocolException) {
                if (owned(owner)) {
                    editChat(owner) { it.copy(live = null) }
                    loadAndFollow(owner)
                }
            }
            catch (e: Exception) {
                // Explicit pre-stream 4xx rejection is safe to restore, and so is a failure before the request
                // left the phone (no connection, no TLS): the server cannot have it. Ambiguous failures stay locked.
                val neverSent = e.failedBeforeSending() && (state.value.chat?.live?.lastSequence ?: 0L) == 0L
                val rejected = neverSent || (e is ApiException && e.directSendRejected && state.value.chat?.live?.lastSequence == 0L)
                if (rejected) unresolvedSends.remove(request.conversationId)
                editChat(owner) { current -> current.copy(error = if (neverSent) ClientError(NOT_SENT_MESSAGE, "not_sent", true) else problem(e),
                    draft = if (rejected && current.draft.isEmpty()) request.text else current.draft,
                    attachments = if (rejected && current.attachments.isEmpty()) request.attachments else current.attachments,
                    live = if (rejected) null else current.live,
                    connection = if (rejected) null else current.connection,
                    messages = if (rejected) current.messages.filterNot { it.id == "pending-${request.controlId}" } else current.messages) }
                saveDraft()
            }
        }
    }

    private fun monotonicMillis() = System.nanoTime() / 1_000_000

    private fun withLineage(conversation: Conversation): Conversation {
        val lineage = conversation.lineage ?: lineages[conversation.id] ?: return conversation
        val title = lineage.parentTitle ?: lineages[conversation.id]?.parentTitle
            ?: state.value.conversations.find { it.id == lineage.parentConversationId }?.title
        val enriched = lineage.copy(parentTitle = title)
        lineages[conversation.id] = enriched
        return conversation.copy(lineage = enriched)
    }

    private fun resolveParentTitle(owner: Long) {
        val chat = state.value.chat ?: return
        val lineage = chat.conversation.lineage ?: return
        if (lineage.parentTitle != null || !lineageTitleReads.add(chat.conversation.id)) return
        val source = repository; val sourceEpoch = epoch
        viewModelScope.launch {
            try {
                val parent = source.detail(lineage.parentConversationId).conversation
                if (sourceEpoch == epoch && owned(owner)) {
                    val enriched = lineage.copy(parentTitle = parent.title)
                    lineages[chat.conversation.id] = enriched
                    editChat(owner) { it.copy(conversation = it.conversation.copy(lineage = enriched)) }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* The parent chip remains usable when the title read fails. */ }
        }
    }

    fun openParent() {
        val chat = state.value.chat ?: return
        val lineage = chat.conversation.lineage ?: return
        if (chat.controlBusy) return
        val parent = state.value.conversations.find { it.id == lineage.parentConversationId }
            ?: chat.conversation.copy(id = lineage.parentConversationId, title = lineage.parentTitle.orEmpty(),
                active = false, starting = false, controlId = null, lineage = null)
        open(parent)
    }

    fun retry(messageId: String) = fork(ForkMode.RETRY, messageId, null)
    fun edit(messageId: String, text: String) = fork(ForkMode.EDIT, messageId, text.trim())

    private fun fork(mode: ForkMode, messageId: String, text: String?) {
        val chat = state.value.chat ?: return
        if (!chat.canFork || state.value.signInRequired || state.value.creating) return
        val anchor = chat.messages.lastOrNull { it.role == if (mode == ForkMode.RETRY) "assistant" else "user" } ?: return
        val numericId = messageId.toLongOrNull()?.takeIf { it > 0 } ?: return
        if (anchor.id != messageId || (mode == ForkMode.EDIT && text.isNullOrBlank())) return
        val candidate = ForkRequest(chat.conversation.id, mode, numericId, state.value.choice, text)
        // An explicit retry of an uncertain identical action keeps its idempotency key.
        val pending = unresolvedForks[chat.conversation.id]
        val request = pending?.takeIf { it.copy(requestId = candidate.requestId) == candidate } ?: candidate
        unresolvedForks[chat.conversation.id] = request
        val owner = generation; val sourceEpoch = epoch; val source = repository
        editChat(owner) { it.copy(controlBusy = true, error = null) }
        viewModelScope.launch {
            try {
                val result = source.fork(request)
                if (sourceEpoch != epoch) return@launch
                unresolvedForks.remove(request.conversationId)
                if (!owned(owner)) return@launch
                openFork(result)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (sourceEpoch == epoch) {
                    if (e is ApiException && e.status in 400..499) unresolvedForks.remove(request.conversationId)
                    editChat(owner) { it.copy(controlBusy = false, error = problem(e)) }
                }
            }
        }
    }

    private fun openFork(result: ForkResponse) {
        flushOpenReport()
        saveDraft()
        attachmentScope = newControlId()
        val owner = ++generation
        sessionJob?.cancel()
        val child = withLineage(result.conversation)
        val initial = TurnState(child.id, result.run.id, startedAt = monotonicMillis())
        forkRuns[child.id] = result.run.id
        livePresentation.value = initial
        mutable.update { it.copy(conversations = listOf(child) + it.conversations.filterNot { c -> c.id == child.id },
            choice = result.run.choice, claudePreview = null,
            chat = ChatState(child, loading = false, live = initial, liveModel = result.run.choice.model,
                connection = Connection.CONNECTING)) }
        // Load the actual child IDs, keeping the target assistant exclusively in the replay item.
        sessionJob = viewModelScope.launch {
            try {
                try {
                    val detail = repository.detail(child.id)
                    if (!owned(owner)) return@launch
                    val prefix = if (detail.messages.lastOrNull()?.role == "assistant") detail.messages.dropLast(1) else detail.messages
                    editChat(owner) { it.copy(conversation = withLineage(detail.conversation), messages = prefix,
                        queued = detail.queued, queuePaused = detail.queuePaused, olderCount = detail.olderCount,
                        nextCursor = detail.nextCursor, beforeSendId = prefix.mapNotNull { m -> m.id.toLongOrNull() }.maxOrNull() ?: 0) }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* The admitted run can still be followed if its history read fails. */ }
                follow(owner, child.id, result.run.id, previous = initial)
                if (!owned(owner)) return@launch
                forkRuns.remove(child.id)
                loadAndFollow(owner)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (!owned(owner)) return@launch
                if (e is StreamProtocolException || (e is ApiException && e.status == 404)) {
                    forkRuns.remove(child.id)
                    editChat(owner) { it.copy(live = null, connection = null) }
                    loadAndFollow(owner)
                } else editChat(owner) { it.copy(error = problem(e)) }
            }
        }
    }

    fun loadOlder() {
        val chat = state.value.chat ?: return
        val cursor = chat.nextCursor ?: return
        if (chat.loadingOlder) return
        val owner = generation
        val source = repository
        editChat(owner) { it.copy(loadingOlder = true) }
        viewModelScope.launch {
            try {
                val detail = source.detail(chat.conversation.id, cursor)
                editChat(owner) { current -> current.copy(messages = (detail.messages + current.messages).distinctBy { it.id },
                    olderCount = detail.olderCount, nextCursor = detail.nextCursor, loadingOlder = false) }
            } catch (e: Exception) { editChat(owner) { it.copy(loadingOlder = false, error = problem(e)) } }
        }
    }

    private suspend fun syncControls(owner: Long, id: String) {
        val detail = repository.detail(id)
        editChat(owner) { it.copy(conversation = withLineage(detail.conversation), queued = detail.queued, queuePaused = detail.queuePaused) }
    }

    fun stop() {
        val chat = state.value.chat ?: return
        val controlId = chat.controlId ?: return
        if (!chat.running || chat.controlBusy || chat.conversation.nativeControl || chat.stopRequested) return
        val owner = generation
        val source = repository
        editChat(owner) { it.copy(controlBusy = true, error = null) }
        viewModelScope.launch {
            try {
                val result = source.stop(chat.conversation.id, controlId)
                editChat(owner) { it.copy(controlBusy = false, stopRequested = true) }
                if (result.boolean("committed") == false && result.string("status") != "running") {
                    unresolvedSends.remove(chat.conversation.id)
                    // Exact-owner Stop is the only safe recovery of an uncommitted ambiguous POST.
                    editChat(owner) { it.copy(live = null, connection = null) }
                }
                val terminal = result.string("status") in setOf("ok", "interrupted", "error")
                if (terminal) unresolvedSends.remove(chat.conversation.id)
                if (owned(owner) && (terminal || sessionJob?.isActive != true)) reload()
            } catch (e: Exception) {
                editChat(owner) { it.copy(controlBusy = false, error = problem(e)) }
                if (e is ApiException && e.status == 409 && owned(owner)) reload()
            }
        }
    }

    /** Fetches quick replies once per finished answer; failures just show none. */
    fun loadSuggestions() {
        val chat = state.value.chat ?: return
        val last = chat.messages.lastOrNull() ?: return
        if (chat.running || chat.live != null || last.role != "assistant" || chat.suggestionsFor == last.id) return
        val owner = generation
        val source = repository
        editChat(owner) { it.copy(suggestionsFor = last.id, suggestions = emptyList()) }
        viewModelScope.launch {
            val (forId, items) = try { source.suggestions(chat.conversation.id) } catch (e: CancellationException) { throw e }
                catch (_: Exception) { return@launch }
            editChat(owner) { if (it.suggestionsFor == last.id && (forId == null || forId == last.id)) it.copy(suggestions = items) else it }
        }
    }

    /** A quick reply is sent as the next message. */
    fun sendSuggestion(text: String) {
        editChat(generation) { it.copy(draft = text, suggestions = emptyList()) }
        send()
    }

    /** The user's pick for a question the agent asked; the card updates from the turn's answer event. */
    fun answer(questionId: String, text: String) {
        val chat = state.value.chat ?: return
        val control = chat.controlId ?: return
        if (chat.controlBusy) return
        val owner = generation
        val source = repository
        editChat(owner) { it.copy(controlBusy = true, error = null) }
        viewModelScope.launch {
            try {
                val answered = source.answer(chat.conversation.id, control, questionId, text)
                editChat(owner) { it.copy(controlBusy = false, error = if (answered) null else ClientError("这个问题已经结束了，可以直接发消息告诉它。", "question_closed")) }
            } catch (e: Exception) { editChat(owner) { it.copy(controlBusy = false, error = problem(e)) } }
        }
    }

    /** The latest output of a background task in the open conversation; null once the server no longer has it. */
    suspend fun taskOutput(taskId: String): TaskOutput? {
        val chat = state.value.chat ?: return null
        return repository.taskOutput(chat.conversation.id, taskId)
    }

    /** The agents' shared memory, as the memory page shows it. */
    suspend fun memory(): MemoryOverview = repository.memory()
    suspend fun memoryFile(file: String): String = repository.memoryFile(file)
    suspend fun consolidateMemory() = repository.consolidateMemory()

    /** A file the open conversation names, read from the computer for the file viewer. */
    suspend fun readFile(path: String): FileContent {
        val chat = state.value.chat ?: throw ApiException(404, ClientError("No chat is open.", "not_found", false))
        return repository.readFile(chat.conversation.id, path)
    }

    /** Stops one background task, as in Claude Code's task list; its step updates from the turn's own events. */
    fun stopTask(taskId: String) {
        val chat = state.value.chat ?: return
        val control = chat.controlId ?: return
        if (!chat.running || chat.conversation.nativeControl) return
        val owner = generation
        val source = repository
        viewModelScope.launch {
            try {
                if (!source.stopTask(chat.conversation.id, control, taskId))
                    editChat(owner) { it.copy(error = ClientError("This background task has already ended.", "task_not_running", false)) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { editChat(owner) { it.copy(error = problem(e)) } }
        }
    }

    /** Like Claude Code: a message sent while the agent works reaches it at its next step. */
    fun steer() {
        val chat = state.value.chat ?: return
        if (!chat.running || !chat.hasDraft || chat.controlBusy || chat.conversation.nativeControl || !validAttachments(chat)) {
            queue(); return
        }
        // No known run to steer: queue it for the next turn instead of leaving the button dead.
        val control = chat.controlId ?: run { queue(); return }
        if (!online()) { editChat(generation) { it.copy(error = ClientError(OFFLINE_MESSAGE, "offline", true)) }; return }
        val owner = generation
        val source = repository
        val text = chat.draft
        val attachments = chat.attachments
        editChat(owner) { it.copy(controlBusy = true, error = null, sends = it.sends + 1) }
        viewModelScope.launch {
            val steered = try { source.steer(chat.conversation.id, control, text, attachments) }
                catch (e: Exception) { editChat(owner) { it.copy(controlBusy = false, error = problem(e)) }; return@launch }
            editChat(owner) { it.copy(controlBusy = false, draft = if (steered && it.draft == text) "" else it.draft,
                attachments = if (steered) it.attachments.filterNot { a -> attachments.any { sent -> sent.id == a.id } } else it.attachments) }
            if (owned(owner)) saveDraft()
            if (!steered && owned(owner)) queue()
        }
    }

    fun queue() {
        val chat = state.value.chat ?: return
        if (chat.controlBusy || chat.conversation.nativeControl) return
        val request = chat.pendingQueue ?: run {
            if (!chat.running || !chat.hasDraft || !validAttachments(chat)) return
            QueueRequest(chat.conversation.id, chat.draft, state.value.choice, attachments = chat.attachments)
        }
        val owner = generation
        val source = repository
        unresolvedQueues[request.conversationId] = request
        editChat(owner) { it.copy(pendingQueue = request, controlBusy = true, queueUncertain = false, error = null, sends = it.sends + 1) }
        viewModelScope.launch {
            try {
                source.queue(request)
                unresolvedQueues.remove(request.conversationId)
                editChat(owner) { it.copy(pendingQueue = null, controlBusy = false, queueUncertain = false,
                    draft = if (it.draft == request.text) "" else it.draft,
                    attachments = it.attachments.filterNot { a -> request.attachments.any { sent -> sent.id == a.id } }) }
                if (owned(owner)) saveDraft()
                if (owned(owner)) syncControls(owner, request.conversationId)
            } catch (e: Exception) {
                val rejected = e is ApiException && e.status in 400..499
                if (rejected) unresolvedQueues.remove(request.conversationId)
                if (e is ApiException && e.problem.code == "conversation_idle" && owned(owner)) {
                    // The chat only looked busy here (a turn that ended unseen): send it as a normal message.
                    unresolvedSends.remove(request.conversationId)
                    editChat(owner) { it.copy(controlBusy = false, queueUncertain = false, pendingQueue = null, live = null,
                        connection = null, conversation = it.conversation.copy(active = false, starting = false)) }
                    send()
                    return@launch
                }
                editChat(owner) { it.copy(controlBusy = false, queueUncertain = !rejected,
                    pendingQueue = if (rejected) null else request, error = problem(e)) }
            }
        }
    }

    fun resumeQueue() {
        val chat = state.value.chat ?: return
        val head = chat.queued.firstOrNull() ?: return
        if (!chat.queuePaused || chat.controlBusy || chat.running || chat.conversation.nativeControl) return
        val owner = generation
        val source = repository
        editChat(owner) { it.copy(controlBusy = true, error = null) }
        viewModelScope.launch {
            try {
                val started = source.resume(chat.conversation.id, head.id)
                // The server started the head as a turn: show that at once, with the turn it named, so the
                // button does not invite a second tap (that one found the queue empty and showed an error)
                // while the reload is on its way.
                if (owned(owner)) {
                    editChat(owner) { it.copy(controlBusy = false, queuePaused = false, queued = it.queued.filterNot { q -> q.id == head.id },
                        conversation = it.conversation.copy(active = started.boolean("active") ?: it.conversation.active,
                            starting = started.boolean("starting") ?: true,
                            controlId = started.string("controlId") ?: it.conversation.controlId)) }
                    reload()
                }
            } catch (e: Exception) {
                // Refresh truth after every outcome; never optimistically unpause or repeat the mutation. A queue
                // already running or emptied (another tap, another device) is not an error to show.
                if (owned(owner)) {
                    val settled = e is ApiException && e.problem.code in setOf("queue_empty", "queue_not_paused")
                    runCatching { syncControls(owner, chat.conversation.id) }
                    editChat(owner) { it.copy(controlBusy = false, error = if (settled) null else problem(e)) }
                    if (settled) reload()
                }
            }
        }
    }
}

/**
 * A read whose connection failed is tried once more before the user hears of it: a cold start on mobile data
 * through a VPN can lose its first TLS handshake (release check 420317, N1), and showing "connection lost"
 * for a read that works a second later only alarms.
 */
internal suspend fun <T> readTwice(read: suspend () -> T): T = try { read() } catch (e: IOException) {
    Diagnostics.warn("http", "list read retried", "error" to Diagnostics.describe(e))
    delay(1000); read()
}

fun problem(e: Throwable): ClientError = when (e) {
    is ApiException -> e.problem
    is StreamProtocolException -> ClientError(e.message ?: "Stream verification failed.", "stream_mismatch", false)
    is IOException -> ClientError("Connection interrupted. Reconnect to check server history; no work is automatically resent.", "connection_lost")
    else -> ClientError("Unable to complete this request. Check Settings and reload.")
}.also { Diagnostics.warn("ui", "error shown", "code" to it.code, "error" to Diagnostics.describe(e)) }

/** How long a send whose connection broke may still be on its way to the server (admission takes seconds). */
private const val SEND_SETTLE_MS = 60_000L

/** Whether two lists show the same words: same ids, roles and blocks (a locally known model or usage aside). */
internal fun sameMessages(shown: List<ChatMessage>, fresh: List<ChatMessage>): Boolean =
    shown.size == fresh.size && shown.indices.all { i ->
        shown[i].id == fresh[i].id && shown[i].role == fresh[i].role && shown[i].blocks == fresh[i].blocks
    }

/** Excludes per-token and per-frame content while retaining all actionable server truth. */
internal fun screenSnapshot(state: ClientState): ClientState = state.copy(chat = state.chat?.let { chat ->
    chat.copy(live = chat.live?.copy(blocks = emptyList(), lastSequence = 0, revealing = false))
})
