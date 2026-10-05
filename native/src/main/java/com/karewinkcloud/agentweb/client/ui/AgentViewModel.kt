package com.karewinkcloud.agentweb.client.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.karewinkcloud.agentweb.client.core.*
import com.karewinkcloud.agentweb.client.data.*
import kotlinx.coroutines.*
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
    val connection: Connection? = null, val error: ClientError? = null,
    val browserError: ClientError? = null,
    val controlBusy: Boolean = false, val stopRequested: Boolean = false,
    val pendingQueue: QueueRequest? = null, val queueUncertain: Boolean = false,
    val liveModel: String? = null, val beforeSendId: Long = 0, val revealing: Boolean = false,
    val attachments: List<Attachment> = emptyList(), val attachmentLoading: Boolean = false,
) {
    val running get() = conversation.running || live?.done == false
    val controlId get() = live?.runId?.takeIf(String::isNotBlank) ?: conversation.controlId
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
    private val saveChoice: (ModelChoice) -> Unit,
) : ViewModel() {
    private val mutable = MutableStateFlow(ClientState(choice = initialChoice, signInRequired = initialSignInRequired))
    val state: StateFlow<ClientState> = mutable.asStateFlow()
    // Token-bearing blocks are observed only by the live LazyColumn item.
    val screenState = state.map(::screenSnapshot).distinctUntilChanged()
    private val livePresentation = MutableStateFlow<TurnState?>(null)
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
    private data class CachedChat(val messages: List<ChatMessage>, val olderCount: Int, val nextCursor: String?)
    private val chatCache = java.util.concurrent.ConcurrentHashMap<String, CachedChat>()
    private var prefetchJob: Job? = null
    private fun cacheChat() {
        state.value.chat?.takeIf { !it.loading && it.error == null }?.let { chat ->
            chatCache[chat.conversation.id] = CachedChat(chat.messages.filterNot { it.id.startsWith("pending-") }, chat.olderCount, chat.nextCursor)
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
        generation++; attachmentScope = newControlId(); epoch++; livePresentation.value = null
        repository.clearConversationCache()
        // Close followers and pending client requests; never send Stop or replay mutations.
        viewModelScope.coroutineContext.cancelChildren()
        drafts.clear(); attachmentDrafts.clear(); unresolvedSends.clear(); unresolvedQueues.clear(); chatCache.clear()
        previews.clear(); messageModels.clear(); messageUsage.clear(); lineages.clear(); unresolvedForks.clear(); forkRuns.clear(); lineageTitleReads.clear()
        mutable.value = ClientState(choice = state.value.choice, tab = state.value.tab, signInRequired = true, error = signInRequiredError)
    }

    fun changeServer(newRepository: AgentRepository, choice: ModelChoice, signInRequired: Boolean = false) {
        generation++; attachmentScope = newControlId(); epoch++; livePresentation.value = null
        viewModelScope.coroutineContext.cancelChildren()
        repository = newRepository
        drafts.clear(); attachmentDrafts.clear(); unresolvedSends.clear(); unresolvedQueues.clear(); chatCache.clear()
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
                val cached = source.cachedConversations()
                if (owner == epoch && cached.isNotEmpty()) {
                    // Saved previews stay valid while a row's updated_at is unchanged.
                    cached.forEach { row -> if (row.messageCount != null) previews.putIfAbsent(row.id, row) }
                    mutable.update { old -> if (old.conversations.isEmpty()) old.copy(conversations = cached) else old }
                }
            }
            supervisorScope {
                val catalog = async { runCatching { source.agents() } }
                val projects = async { runCatching { source.projects() } }
                val conversations = runCatching { source.conversations() }
                val agents = catalog.await()
                val projectResult = projects.await()
                if (owner != epoch) return@supervisorScope
                mutable.update { old ->
                    val choices = agents.getOrNull() ?: old.agents
                    val valid = choices.firstOrNull { it.id == old.choice.agent && it.available }
                        ?: choices.firstOrNull { it.available }
                    val choice = if (valid == null) old.choice else old.choice.copy(
                        agent = valid.id,
                        model = valid.resumeModel(old.choice.model).takeIf { it in valid.models } ?: valid.defaultModel,
                        effort = if (valid.supportsEffort) old.choice.effort else null,
                    )
                    old.copy(conversations = conversations.getOrNull()?.map { row ->
                        previews[row.id]?.takeIf { it.updatedAt == row.updatedAt && !row.running }?.let {
                            row.copy(preview = it.preview, messageCount = it.messageCount,
                                title = if (row.title.isBlank() || row.title.equals("New chat", true)) it.title else row.title)
                        } ?: row
                    } ?: old.conversations, agents = choices,
                        choice = choice, refreshing = false,
                        projects = if (projectOwner == projectVersion) projectResult.getOrNull()?.let(old.projects::refreshed) ?: old.projects else old.projects,
                        projectError = if (projectOwner == projectVersion) projectResult.exceptionOrNull()?.let(::problem) else old.projectError,
                        error = (conversations.exceptionOrNull() ?: agents.exceptionOrNull())?.let(::problem))
                }
                loadPreviews()
            }
        }
    }

    fun search(value: String) { mutable.update { it.copy(search = value) }; loadPreviews() }
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
            if (owner == epoch && state.value.error == null) source.saveConversations(state.value.conversations)
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
        saveDraft()
        attachmentScope = newControlId()
        val initial = if (conversation.running) TurnState(conversation.id, conversation.controlId.orEmpty(), startedAt = monotonicMillis()) else null
        livePresentation.value = initial
        val owner = ++generation
        sessionJob?.cancel()
        val cached = chatCache[conversation.id]
        mutable.update { it.copy(tab = if (it.claudeVisible) AppTab.CLAUDE else AppTab.CONVERSATIONS, claudePreview = null, chat = ChatState(withLineage(conversation), draft = drafts[conversation.id].orEmpty(),
            messages = cached?.messages.orEmpty(), olderCount = cached?.olderCount ?: 0, nextCursor = cached?.nextCursor,
            live = initial,
            connection = if (conversation.running) Connection.CONNECTING else null,
            attachments = attachmentDrafts[conversation.id].orEmpty(),
            pendingQueue = unresolvedQueues[conversation.id], queueUncertain = unresolvedQueues.containsKey(conversation.id), loading = !fresh && cached == null)) }
        // Creation only stores agent/model; its empty detail has default effort and
        // permissions. Keep the user's selection for the first send in a fresh chat.
        sessionJob = viewModelScope.launch { loadAndFollow(owner, applyChoice = !fresh) }
    }
    fun back() {
        cacheChat(); saveDraft(); generation++; attachmentScope = newControlId(); sessionJob?.cancel()
        val current = state.value
        mutable.update { it.copy(chat = null, claudePreview = null,
            claudeVisible = current.claudeVisible && (current.chat != null || current.claudePreview != null),
            tab = if (current.claudeVisible && (current.chat != null || current.claudePreview != null)) AppTab.CLAUDE else AppTab.CONVERSATIONS) }
        if (state.value.claudeVisible) refreshClaudeSessions() else refresh()
    }
    private fun saveDraft() { state.value.chat?.let { drafts[it.conversation.id] = it.draft; attachmentDrafts[it.conversation.id] = it.attachments } }
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
            state.value.agents.find { it.id == state.value.choice.agent }?.supportsImages != true) "当前模型不支持图片，请切换模型或移除图片" else null
        if (error != null) { attachmentFailure(owner, error); return false }
        // Bound in-memory drafts across conversations as well as per-message limits.
        val otherBytes = attachmentDrafts.filterKeys { it != chat.conversation.id }.values.flatten().sumOf { it.size.toLong() }
        if (otherBytes + combined.sumOf { it.size.toLong() } > 64L * 1024 * 1024) {
            attachmentFailure(owner, "附件草稿占用过多，请先发送或移除其他对话的附件"); return false
        }
        mutable.update { it.copy(chat = it.chat?.copy(attachments = combined, attachmentLoading = false, error = null)) }; saveDraft()
        return true
    }
    fun removeAttachment(id: String) { mutable.update { it.copy(chat = it.chat?.copy(attachments = it.chat.attachments.filterNot { a -> a.id == id })) }; saveDraft() }
    private fun validAttachments(chat: ChatState): Boolean {
        if (chat.attachmentLoading) return false
        if (chat.attachments.any { it.kind == AttachmentKind.IMAGE } && state.value.agents.find { it.id == state.value.choice.agent }?.supportsImages != true) {
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
        editChat(owner) { it.copy(error = null) }
        sessionJob = viewModelScope.launch { loadAndFollow(owner) }
    }
    fun onForeground() {
        if (state.value.signInRequired) return
        if (state.value.claudeVisible && state.value.chat == null) {
            if (sessionJob?.isActive != true && state.value.claudePreview != null) refreshClaudePreview()
            else if (state.value.claudePreview == null) refreshClaudeSessions()
        } else if (sessionJob?.isActive != true && state.value.chat != null) reload()
        else if (state.value.chat == null) refresh()
    }

    fun selectTab(tab: AppTab) {
        if (tab == AppTab.CLAUDE && !state.value.signInRequired) { showClaudeSessions(); return }
        saveDraft(); generation++; attachmentScope = newControlId(); sessionJob?.cancel()
        mutable.update { it.copy(tab = tab, chat = null, claudeVisible = false, claudePreview = null) }
        if (tab == AppTab.CONVERSATIONS) refresh()
    }

    fun showClaudeSessions() {
        if (state.value.signInRequired) return
        saveDraft(); generation++; attachmentScope = newControlId(); sessionJob?.cancel()
        mutable.update { it.copy(tab = AppTab.CLAUDE, chat = null, claudeVisible = true, claudePreview = null) }
        refreshClaudeSessions()
    }

    fun refreshClaudeSessions(older: Boolean = false) {
        if (state.value.signInRequired) return
        if (claudeListJob?.isActive == true) return
        val cursor = if (older) state.value.claude.nextCursor ?: return else null
        val source = repository
        val owner = epoch
        mutable.update { it.copy(claude = it.claude.copy(loading = true, error = null)) }
        claudeListJob = viewModelScope.launch {
            try {
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
        saveDraft()
        attachmentScope = newControlId()
        livePresentation.value = null
        val owner = ++generation
        sessionJob?.cancel()
        mutable.update { it.copy(chat = null, claudeVisible = true, claudePreview = ClaudePreviewState(session)) }
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

    private suspend fun loadAndFollow(owner: Long, applyChoice: Boolean = false, allowReconcile: Boolean = true) {
        try {
            val id = state.value.chat?.conversation?.id ?: return
            var first = true
            while (owned(owner)) {
                val detail = repository.detail(id)
                if (!owned(owner)) return
                var unresolved = unresolvedSends[id]
                val forkRun = forkRuns[id]
                val priorChat = state.value.chat
                // A send whose stream dropped is settled once the server has saved its message and
                // nothing runs: a later (queued) turn may own the stream now, so following the old
                // run would fail forever with "run changed" and keep the chat looking busy.
                if (unresolved != null && !detail.conversation.running && unresolvedSettled(unresolved, detail.messages, priorChat?.beforeSendId ?: 0)) {
                    unresolvedSends.remove(id); unresolved = null
                }
                val prior = priorChat?.live
                // Attribute only a completed locally observed turn to its captured send model.
                // The API does not persist models for all historical messages.
                val last = detail.messages.lastOrNull()
                if (prior?.done == true && last?.role == "assistant" &&
                    (last.id.toLongOrNull() ?: 0) > priorChat.beforeSendId && last.text == prior.text) {
                    priorChat.liveModel?.let { messageModels["$id:${last.id}"] = it }
                    prior.usage?.let { messageUsage["$id:${last.id}"] = it }
                }
                editChat(owner) { old ->
                    val firstId = detail.messages.firstOrNull()?.id?.toLongOrNull()
                    val prefix = if (firstId == null) emptyList() else old.messages.filter { (it.id.toLongOrNull() ?: Long.MAX_VALUE) < firstId }
                    old.copy(conversation = withLineage(detail.conversation), messages = prefix + detail.messages.map { message ->
                        message.copy(model = message.model ?: messageModels["$id:${message.id}"],
                            usage = messageUsage["$id:${message.id}"]?.merge(message.usage) ?: message.usage)
                    },
                        queued = detail.queued, queuePaused = detail.queuePaused,
                        olderCount = if (prefix.isEmpty()) detail.olderCount else old.olderCount,
                        nextCursor = if (prefix.isEmpty()) detail.nextCursor else old.nextCursor, loading = false, error = null,
                        live = (unresolved?.controlId ?: forkRun ?: detail.conversation.controlId?.takeIf { detail.conversation.running })?.let { run ->
                            prior?.takeIf { p -> p.runId == run && !p.done } ?: TurnState(id, run,
                                startedAt = prior?.takeIf { p -> p.runId.isBlank() && !p.done }?.startedAt ?: monotonicMillis())
                        } ?: prior?.takeIf { detail.conversation.running && !it.done && it.runId.isBlank() },
                        connection = null, stopRequested = false, revealing = false)
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
                    follow(owner, id, run, previous = prior?.takeIf { it.runId == run && !it.done })
                    if (!owned(owner)) return
                    unresolvedSends.remove(id)
                    forkRuns.remove(id)
                    // The done event may precede durable queued-turn handoff. Query until it settles.
                    delay(200)
                    continue
                }
                if (detail.queued.isEmpty() || detail.queuePaused) return
                delay(1000)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: StreamProtocolException) {
            if (allowReconcile && owned(owner)) {
                editChat(owner) { it.copy(live = null) }
                loadAndFollow(owner, allowReconcile = false)
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

    private fun unresolvedSettled(send: SendRequest, messages: List<ChatMessage>, beforeSendId: Long): Boolean {
        val text = send.text.trim()
        return messages.any { message -> message.role == "user" && (message.id.toLongOrNull() ?: 0) > beforeSendId &&
            (text.isEmpty() || message.text.contains(text)) }
    }

    private suspend fun follow(owner: Long, id: String, run: String?, send: SendRequest? = null, previous: TurnState? = null) = coroutineScope {
        val startedAt = previous?.startedAt ?: state.value.chat?.live?.takeIf { it.runId == run || it.runId.isBlank() }?.startedAt ?: monotonicMillis()
        val initial = previous?.copy(startedAt = startedAt) ?: run?.let { TurnState(id, it, startedAt = startedAt) }
        val visibleInitial = initial ?: TurnState(id, "", startedAt = startedAt)
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
                latest = snapshot.copy(turn = snapshot.turn.copy(startedAt = startedAt))
                pacer.offer(snapshot.turn.blocks.sumOf { (it as? ChatBlock.Text)?.content?.length ?: 0 }, frameTime)
                updates.trySend(Unit)
            }
            updates.close()
            painter.join() // Finish revealing before replacing the live item with durable history.
        } catch (e: Exception) {
            if (currentCoroutineContext().isActive) { updates.close(); painter.join() }
            throw e
        } finally { updates.close(); painter.cancel() }
    }

    fun send() {
        if (state.value.creating) return
        val chat = state.value.chat ?: return
        if (!chat.canSend || !chat.hasDraft || !validAttachments(chat)) return
        val request = SendRequest(chat.conversation.id, chat.draft, state.value.choice, attachments = chat.attachments)
        attachmentScope = newControlId()
        val owner = ++generation
        sessionJob?.cancel()
        val initial = TurnState(request.conversationId, request.controlId, startedAt = monotonicMillis())
        livePresentation.value = initial
        unresolvedSends[request.conversationId] = request
        drafts[request.conversationId] = ""
        attachmentDrafts.remove(request.conversationId)
        editChat(owner) { it.copy(draft = "", attachments = emptyList(), error = null, revealing = false, live = initial,
            liveModel = request.choice.model, beforeSendId = it.messages.mapNotNull { message -> message.id.toLongOrNull() }.maxOrNull() ?: 0,
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
                // Explicit pre-stream 4xx rejection is safe to restore. Ambiguous failures stay locked.
                val rejected = e is ApiException && e.directSendRejected && state.value.chat?.live?.lastSequence == 0L
                if (rejected) unresolvedSends.remove(request.conversationId)
                editChat(owner) { current -> current.copy(error = problem(e),
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
        editChat(owner) { it.copy(pendingQueue = request, controlBusy = true, queueUncertain = false, error = null) }
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
                source.resume(chat.conversation.id, head.id)
                if (owned(owner)) { editChat(owner) { it.copy(controlBusy = false) }; reload() }
            } catch (e: Exception) {
                // Refresh truth after every outcome; never optimistically unpause or repeat the mutation.
                if (owned(owner)) {
                    runCatching { syncControls(owner, chat.conversation.id) }
                    editChat(owner) { it.copy(controlBusy = false, error = problem(e)) }
                }
            }
        }
    }
}

fun problem(e: Throwable): ClientError = when (e) {
    is ApiException -> e.problem
    is StreamProtocolException -> ClientError(e.message ?: "Stream verification failed.", "stream_mismatch", false)
    is IOException -> ClientError("Connection interrupted. Reconnect to check server history; no work is automatically resent.", "connection_lost")
    else -> ClientError("Unable to complete this request. Check Settings and reload.")
}

/** Excludes per-token and per-frame content while retaining all actionable server truth. */
internal fun screenSnapshot(state: ClientState): ClientState = state.copy(chat = state.chat?.let { chat ->
    chat.copy(live = chat.live?.copy(blocks = emptyList(), lastSequence = 0, revealing = false))
})
