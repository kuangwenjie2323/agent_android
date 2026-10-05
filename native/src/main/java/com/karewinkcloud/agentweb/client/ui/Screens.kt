@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.karewinkcloud.agentweb.client.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.karewinkcloud.agentweb.client.R
import com.karewinkcloud.agentweb.client.R.string as S
import com.karewinkcloud.agentweb.client.core.*
import com.karewinkcloud.agentweb.client.data.*
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun AgentWebApp(vm: AgentViewModel, settings: SettingsViewModel, studio: ComfyViewModel, openBrowser: (String) -> Unit,
    passkey: PasskeyProvider) {
    val initialScreen = remember(vm) { screenSnapshot(vm.state.value) }
    val state by vm.screenState.collectAsStateWithLifecycle(initialValue = initialScreen)
    var modelsOpen by rememberSaveable { mutableStateOf(false) }
    val showSettings by settings.showSettings.collectAsStateWithLifecycle()
    LaunchedEffect(showSettings) { if (showSettings) { vm.selectTab(AppTab.SETTINGS); settings.settingsSeen() } }
    LaunchedEffect(vm) {
        vm.frameClock = kotlin.coroutines.coroutineContext[MonotonicFrameClock]
        try { kotlinx.coroutines.awaitCancellation() } finally { vm.frameClock = null }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.onForeground() }
    BackHandler(!modelsOpen && (state.chat != null || state.claudePreview != null || state.tab != AppTab.CONVERSATIONS)) {
        if (state.chat != null || state.claudePreview != null) vm.back() else vm.selectTab(AppTab.CONVERSATIONS)
    }
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            val availableHeight = maxHeight
            val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
            // Landscape phones are short: move navigation to a side rail so content keeps the height.
            val sideNavigation = maxWidth > maxHeight && maxHeight < 480.dp
            val showNavigation = !keyboardVisible && state.chat == null && state.claudePreview == null
            Row(Modifier.fillMaxSize()) {
            if (sideNavigation && showNavigation) AppNavigationRail(state.tab, vm::selectTab)
            Box(Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.widthIn(max = 840.dp).fillMaxSize().align(Alignment.TopCenter)) {
                Column(Modifier.weight(1f)) {
                    when {
                        state.tab == AppTab.SETTINGS -> SettingsScreen(settings, openBrowser, passkey,
                            state.agents.find { it.id == state.choice.agent }?.label(state.choice.model).orEmpty(), { modelsOpen = true })
                        state.signInRequired -> {
                            ScreenTitle(when (state.tab) { AppTab.CREATE -> tr(S.studio); AppTab.CLAUDE -> "Claude Code"; else -> tr(S.conversations) })
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.Center) {
                                EmptyState(R.drawable.aw_chat, tr(S.sign_in_title), tr(S.sign_in_hint),
                                    tr(S.open_sign_in), { vm.selectTab(AppTab.SETTINGS) })
                                state.error?.let { ErrorBlock(it) }
                            }
                        }
                        state.chat != null -> ChatScreen(state, vm, availableHeight) { modelsOpen = true }
                        state.claudePreview != null -> ClaudeHistoryScreen(state.claudePreview!!, vm)
                        state.tab == AppTab.CLAUDE -> ClaudeSessionsScreen(state.claude, vm)
                        // Reserve input space as well as the fixed 48dp action row when the IME is open.
                        state.tab == AppTab.CREATE -> ComfyScreen(studio, (availableHeight * .45f).coerceIn(150.dp, 320.dp))
                        else -> ConversationList(state, vm)
                    }
                }
                if (showNavigation && !sideNavigation) AppNavigation(state.tab, vm::selectTab)
            }
            }
            }
        }
    }
    if (modelsOpen) ModelSheet(state.agents, state.choice, vm::choose, vm::refresh) { modelsOpen = false }
}

@Composable
internal fun AppNavigation(selected: AppTab, onSelect: (AppTab) -> Unit) {
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        NavigationBar(Modifier.height(64.dp), containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp,
            windowInsets = WindowInsets(0, 0, 0, 0)) {
            AppTab.entries.forEach { tab ->
                NavigationBarItem(selected == tab, { onSelect(tab) }, icon = { AppIcon(tabIcon(tab, selected == tab)) },
                    label = { Text(tabLabel(tab), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall) },
                    colors = appNavColors())
            }
        }
    }
}

@Composable
internal fun AppNavigationRail(selected: AppTab, onSelect: (AppTab) -> Unit) {
    Row {
        NavigationRail(containerColor = MaterialTheme.colorScheme.surface, windowInsets = WindowInsets(0, 0, 0, 0)) {
            Spacer(Modifier.weight(1f))
            AppTab.entries.forEach { tab ->
                NavigationRailItem(selected == tab, { onSelect(tab) }, icon = { AppIcon(tabIcon(tab, selected == tab)) },
                    label = { Text(tabLabel(tab), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium) })
            }
            Spacer(Modifier.weight(1f))
        }
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun tabLabel(tab: AppTab) = when (tab) { AppTab.CONVERSATIONS -> tr(S.conversations); AppTab.CREATE -> tr(S.studio)
    AppTab.CLAUDE -> "Claude Code"; AppTab.SETTINGS -> tr(S.settings) }
private fun tabIcon(tab: AppTab, selected: Boolean) = when (tab) {
    AppTab.CONVERSATIONS -> if (selected) R.drawable.aw_chat_fill else R.drawable.aw_chat
    AppTab.CREATE -> if (selected) R.drawable.aw_create_fill else R.drawable.aw_create
    AppTab.CLAUDE -> if (selected) R.drawable.aw_terminal_fill else R.drawable.aw_terminal
    AppTab.SETTINGS -> if (selected) R.drawable.aw_gear_fill else R.drawable.aw_gear
}

@Composable
private fun appNavColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary,
    indicatorColor = Color.Transparent, unselectedIconColor = MaterialTheme.colorScheme.outline,
    unselectedTextColor = MaterialTheme.colorScheme.outline)

@Composable
internal fun Toolbar(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null, onSettings: (() -> Unit)? = null,
    onNew: (() -> Unit)? = null, newEnabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) ActionIcon(R.drawable.aw_back, tr(S.back), onClick = onBack)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            if (!subtitle.isNullOrBlank()) Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onNew != null) ActionIcon(R.drawable.aw_plus, tr(S.new_chat), newEnabled, onNew)
        if (onSettings != null) ActionIcon(R.drawable.aw_settings, tr(S.settings), onClick = onSettings)
    }
}

@Composable
private fun ColumnScope.ConversationList(state: ClientState, vm: AgentViewModel) {
    val focusManager = LocalFocusManager.current
    val list = rememberLazyListState()
    var menu by remember { mutableStateOf(false) }
    val visibleIds by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String } } }
    LaunchedEffect(visibleIds, state.conversations) { vm.loadPreviews(visibleIds) }
    ScreenTitle(tr(S.conversations)) {
        if (state.creating) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
        else IconButton({ vm.newChat() }, colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary)) {
            AppIcon(R.drawable.aw_plus, tr(S.new_chat))
        }
        Box {
            ActionIcon(R.drawable.aw_more, tr(S.more)) { menu = true }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text(tr(S.overflow_empty)) }, trailingIcon = { if (state.showEmpty) AppIcon(R.drawable.aw_check) },
                    onClick = { vm.showEmpty(!state.showEmpty); menu = false })
                DropdownMenuItem(text = { Text(tr(S.refresh)) }, enabled = !state.refreshing, onClick = { vm.refresh(); menu = false })
            }
        }
    }
    TextField(state.search, vm::search, Modifier.fillMaxWidth().padding(horizontal = PageGutter), singleLine = true,
        shape = RoundedCornerShape(12.dp), placeholder = { Text(tr(S.search_conversations), style = MaterialTheme.typography.bodyMedium) },
        leadingIcon = { AppIcon(R.drawable.aw_search) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
        trailingIcon = { if (state.search.isNotEmpty()) ActionIcon(R.drawable.aw_close, tr(S.clear_search)) { vm.search("") } })
    Box(Modifier.weight(1f)) {
        PullToRefreshBox(state.refreshing, vm::refresh) {
            LazyColumn(state = list, contentPadding = PaddingValues(start = PageGutter, end = PageGutter, bottom = 24.dp),
                modifier = Modifier.fillMaxSize()) {
                if (state.error != null) item("error") { ErrorBlock(state.error, vm::refresh, tr(S.refresh)) }
                if (state.sections.isEmpty() && state.refreshing) item("loading") { LoadingState(tr(S.loading)) }
                if (state.sections.isEmpty() && !state.refreshing && state.error == null) item("empty") {
                    EmptyState(if (state.search.isEmpty()) R.drawable.aw_chat else R.drawable.aw_search,
                        tr(if (state.search.isEmpty()) S.empty_list else S.no_matches),
                        tr(if (state.search.isEmpty()) S.empty_list_hint else S.search_hint),
                        if (state.search.isNotEmpty()) tr(S.clear_search) else null,
                        if (state.search.isNotEmpty()) ({ vm.search("") }) else null)
                }
                state.sections.forEach { section ->
                    item("group-${section.group}") { GroupLabel(tr(when(section.group) {
                        ConversationGroup.TODAY -> S.today; ConversationGroup.YESTERDAY -> S.yesterday; else -> S.older
                    })) }
                    itemsIndexed(section.conversations, key = { _, it -> it.id }) { index, conversation ->
                        ConversationRow(conversation, state.agents, rowPosition(index, section.conversations.size)) { vm.open(conversation) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ConversationRow(conversation: Conversation, agents: List<Agent>, position: RowPosition = RowPosition.ONLY, onOpen: () -> Unit) {
    val model = agents.find { it.id == conversation.choice.agent }?.label(conversation.choice.model)
        ?: conversation.choice.model.ifBlank { tr(S.assistant) }
    val preview = "${compactModelLabel(model)}: ${plainPreview(conversation.preview).ifBlank { if (conversation.messageCount == null) "…" else tr(S.unused_chat) }}"
    ListRow(conversationTitle(conversation), preview, position, leading = { Avatar(modelBadge(conversation.choice), tint = Color(modelTint(conversation.choice))) },
        extra = conversation.project?.takeIf { it.isNotBlank() }?.let { project -> {
            Text(project, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        } },
        trailing = {
            if (conversation.running) Text("● ${tr(S.running)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            else if (conversation.updatedAt > 0) Text(conversationTime(conversation.updatedAt), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }, onClick = onOpen)
}

/** "Model · effort · permission", listing only non-default settings so the chip stays short. */
internal fun composerModelLabel(model: String, effort: String?, permission: String?): String =
    listOfNotNull(model, effort, permission).joinToString(" · ")

/** Brand tint for a model's artwork tile: DeepSeek blue, OpenAI green, Claude clay, Grok graphite. */
internal fun modelTint(choice: ModelChoice): Long = when {
    choice.agent == "deepseek" || choice.model.contains("deepseek", true) -> 0xff4d6bfe
    choice.agent == "claude" || listOf("opus", "sonnet", "haiku", "claude").any { choice.model.contains(it, true) } -> 0xffd97757
    choice.agent == "grok" || choice.model.contains("grok", true) -> 0xff3a3a3c
    choice.agent == "codex" || choice.model.contains("gpt", true) -> 0xff10a37f
    else -> 0xff8e8e93
}

internal fun modelBadge(choice: ModelChoice): String = when {
    choice.agent == "deepseek" || choice.model.contains("deepseek", true) -> "DS"
    choice.model.contains("opus", true) -> "Op"
    choice.model.contains("sonnet", true) -> "So"
    choice.model.contains("grok", true) -> "Gk"
    choice.model.contains("gpt", true) -> "G" + choice.model.filter(Char::isDigit).take(1)
    else -> choice.model.ifBlank { choice.agent }.take(2).uppercase().ifBlank { "AI" }
}
internal fun conversationTime(seconds: Long, today: java.time.LocalDate = java.time.LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): String {
    val date = Instant.ofEpochSecond(seconds).atZone(zone)
    return date.format(DateTimeFormatter.ofPattern(if (date.toLocalDate() == today) "HH:mm" else "M/d"))
}

@Composable
private fun ColumnScope.ChatScreen(state: ClientState, vm: AgentViewModel, availableHeight: Dp, onModels: () -> Unit) {
    val chat = state.chat ?: return
    var overflow by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { Toolbar(conversationTitle(chat.conversation), chat.conversation.project ?: tr(S.default_project), vm::back) }
        Box {
            ActionIcon(R.drawable.aw_more, tr(S.more)) { overflow = true }
            DropdownMenu(overflow, { overflow = false }) {
                DropdownMenuItem(text = { Text(tr(S.model_settings)) }, onClick = { overflow = false; onModels() })
                DropdownMenuItem(text = { Text(tr(S.reconnect)) }, onClick = { overflow = false; vm.reload() })
            }
        }
    }
    chat.browserError?.let { error ->
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            ErrorBlock(error, vm::dismissBrowserError, tr(S.close))
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    chat.conversation.lineage?.let { lineage ->
        val parentTitle = lineage.parentTitle?.takeIf { it.isNotBlank() }
            ?: state.conversations.find { it.id == lineage.parentConversationId }?.let { conversationTitle(it) }
            ?: tr(S.variant_parent)
        AssistChip(onClick = vm::openParent, label = { Text(tr(S.variant_of, parentTitle), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { AppIcon(R.drawable.aw_back) }, modifier = Modifier.padding(horizontal = 16.dp).heightIn(min = 48.dp))
    }
    key(chat.conversation.id) {
        val list = rememberLazyListState()
        var followTail by remember { mutableStateOf(true) }
        var automaticScroll by remember { mutableStateOf(false) }
        LaunchedEffect(list) {
            snapshotFlow { list.isScrollInProgress to list.canScrollForward }.distinctUntilChanged().collect { (scrolling, canForward) ->
                if (scrolling && !automaticScroll) followTail = false
                if (!scrolling && !canForward) followTail = true
            }
        }
        LaunchedEffect(list, followTail, chat.loading) {
            snapshotFlow { list.layoutInfo.let { info ->
                Triple(info.totalItemsCount, info.visibleItemsInfo.lastOrNull()?.size, info.viewportEndOffset)
            } }.distinctUntilChanged().collect {
                if (followTail && !chat.loading) {
                    automaticScroll = true
                    try {
                        val total = list.layoutInfo.totalItemsCount
                        // One clamped jump to the very end. Scrolling to the item's top and then
                        // down by its height showed an intermediate frame on every growth (jitter).
                        if (total > 0) list.scrollToItem(total - 1, Int.MAX_VALUE)
                    } finally { automaticScroll = false }
                }
            }
        }
        LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (chat.loading) item("loading") { LoadingState(tr(S.loading_chat)) }
            if (chat.nextCursor != null) item("older") {
                TextButton(onClick = { followTail = false; vm.loadOlder() }, enabled = !chat.loadingOlder,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (chat.loadingOlder) tr(S.loading) else countLabel(R.plurals.load_older, chat.olderCount)) }
            }
            if (!chat.loading && chat.messages.isEmpty() && chat.live == null && chat.error == null) item("empty") {
                EmptyState(R.drawable.aw_chat, tr(S.empty_chat), tr(S.empty_chat_hint))
            }
            items(chat.messages, key = { it.id }, contentType = { it.role }) { message ->
                MessageView(message, modelLabel = message.model?.let { model -> state.agents.find { model in it.models }?.label(model) ?: readableModelId(model) },
                    canFork = chat.canFork,
                    onRetry = if (message.role == "assistant" && message.id == chat.messages.lastOrNull { it.role == "assistant" }?.id) ({ vm.retry(message.id) }) else null,
                    onEdit = if (message.role == "user" && message.id == chat.messages.lastOrNull { it.role == "user" }?.id) ({ text -> vm.edit(message.id, text) }) else null)
            }
            if (chat.live != null) item("live-${chat.live.runId}") {
                LiveMessage(vm, (chat.liveModel ?: chat.conversation.choice.model).takeIf { it.isNotBlank() }?.let { model ->
                    state.agents.find { model in it.models }?.label(model) ?: model
                }, chat.connection)
            }
            if (chat.error != null) item("error") { ErrorBlock(chat.error, vm::reload, tr(S.reconnect)) }
        }
        if (!followTail) TextButton({ followTail = true }, Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp)) {
            AppIcon(R.drawable.aw_down); Text(tr(S.latest_messages))
        }
        LaunchedEffect(followTail) {
            if (followTail && list.layoutInfo.totalItemsCount > 0) {
                automaticScroll = true
                try { list.scrollToItem(list.layoutInfo.totalItemsCount - 1, Int.MAX_VALUE) } finally { automaticScroll = false }
            }
        }
    }
    Composer(chat, state, vm, (availableHeight * .55f).coerceAtLeast(100.dp), onModels)
}

@Composable
private fun LiveMessage(vm: AgentViewModel, label: String?, connection: Connection?) {
    val turn by vm.liveTurn.collectAsStateWithLifecycle()
    turn?.let { MessageView(ChatMessage("live-${it.runId}", "assistant", it.blocks, usage = it.usage),
        live = !it.done || it.revealing, modelLabel = label, turn = it, connection = connection) }
}

@Composable
private fun Composer(chat: ChatState, state: ClientState, vm: AgentViewModel, maxHeight: Dp, onModels: () -> Unit) {
    val agent = state.agents.find { it.id == state.choice.agent }
    var projectsOpen by remember { mutableStateOf(false) }
    if (projectsOpen) ProjectSheet(state, vm) { projectsOpen = false }
    ComposerSurface(maxHeight, actions = {
        if (!chat.conversation.nativeControl) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                AttachmentPicker(chat, agent?.supportsImages == true, vm)
                ComposerChip(agent?.label(state.choice.model)?.let { label -> composerModelLabel(compactModelLabel(label),
                    state.choice.effort?.takeIf { agent.supportsEffort }?.let { effortLabel(it) },
                    state.choice.permission.takeIf { it != "auto" }?.let { permissionLabel(it) }) } ?: tr(S.choose_model),
                    onModels, Modifier.weight(1f), selected = true)
                if (chat.queueUncertain || (chat.running && chat.hasDraft)) ActionIcon(
                    if (chat.queueUncertain) R.drawable.aw_refresh else R.drawable.aw_queue,
                    tr(if (chat.queueUncertain) S.check_queue else S.queue), !chat.controlBusy && !chat.attachmentLoading, vm::queue)
                FilledIconButton(onClick = if (chat.running) vm::stop else vm::send,
                    enabled = if (chat.running) !chat.controlBusy && !chat.stopRequested && chat.controlId != null
                        else chat.canSend && chat.hasDraft && agent?.available == true && !state.creating, modifier = Modifier.size(48.dp)) {
                    AppIcon(if (chat.running) R.drawable.aw_stop else R.drawable.aw_send, tr(if (chat.running) S.stop else S.send))
                }
            }
        }
    }) {
        if (chat.conversation.nativeControl) { StatusNotice(tr(S.desktop_controlled)); return@ComposerSurface }
        if (chat.queued.isNotEmpty() || chat.pendingQueue != null) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(chat.queued, key = { it.id }) { item ->
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.widthIn(max = 260.dp)) {
                    Text(tr(S.queued, item.text), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (chat.pendingQueue != null) item { Text(tr(if (chat.queueUncertain) S.queue_uncertain else S.queue_adding), Modifier.padding(8.dp)) }
        }
        if (chat.queuePaused) Row(Modifier.padding(start = 8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tr(S.queue_paused), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(vm::resumeQueue, enabled = !chat.running && !chat.controlBusy && chat.queued.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { Text(tr(S.resume_queue)) }
        }
        if (chat.connection in listOf(Connection.CONNECTING, Connection.RECONNECTING) || chat.stopRequested) StatusNotice(tr(when {
            chat.stopRequested -> S.stop_requested; chat.connection == Connection.CONNECTING -> S.connecting; else -> S.reconnecting
        }), busy = true)
        if (chat.messages.isEmpty() && !chat.running && !chat.loading) ComposerChip(chat.conversation.project ?: tr(S.default_project),
            { projectsOpen = true }, Modifier.widthIn(max = 280.dp))
        if (state.creating) LoadingState(tr(S.loading))
        AttachmentChips(chat, vm)
        val messageDescription = tr(S.message)
        TextField(chat.draft, vm::draft, Modifier.fillMaxWidth().heightIn(min = 48.dp, max = 140.dp).semantics { contentDescription = messageDescription },
            placeholder = { Text(tr(if (chat.running) S.followup_hint else S.message_hint)) }, maxLines = 5,
            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
    }
}

@Composable
internal fun ModelSheet(agents: List<Agent>, choice: ModelChoice, onChoice: (ModelChoice) -> Unit, onRefresh: () -> Unit, onClose: () -> Unit) {
    AppModalBottomSheet(onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surface) {
        BoxWithConstraints(Modifier.fillMaxWidth().fillMaxHeight(.88f)) {
            val pinControls = maxHeight >= 480.dp * LocalDensity.current.fontScale
            val visibleAgents = agents.filter { it.enabled }
            // Start at the current model in the very first frame. Scrolling after the sheet
            // appeared moved rows under a finger mid-tap and picked the wrong model.
            // Compact layouts stay at the controls.
            val initialIndex = remember {
                val selectedAgent = visibleAgents.indexOfFirst { it.id == choice.agent }
                if (!pinControls || selectedAgent < 0) 0 else {
                    val modelIndex = visibleAgents[selectedAgent].models.indexOf(choice.model)
                    visibleAgents.take(selectedAgent).sumOf { 1 + it.models.size } + if (modelIndex > 0) 1 + modelIndex else 0
                }
            }
            val modelListState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr(S.model_settings), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    ActionIcon(R.drawable.aw_check, tr(S.done), onClick = onClose)
                }
                val agent = agents.find { it.id == choice.agent }
                if (pinControls) ModelChoiceControls(agent, choice, onChoice)
                LazyColumn(state = modelListState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!pinControls) item("controls") { ModelChoiceControls(agent, choice, onChoice) }
                    if (agents.isEmpty()) item { TextButton(onRefresh, Modifier.heightIn(min = 48.dp)) { Text(tr(S.refresh)) } }
                    visibleAgents.forEach { agent ->
                        item("agent-${agent.id}") { Text(agent.name, Modifier.padding(top = 12.dp).semantics { heading() },
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        items(agent.models, key = { "${agent.id}-$it" }) { model ->
                            ChoiceRow(agent.label(model), choice.agent == agent.id && choice.model == model, agent.available,
                                if (!agent.available) tr(S.model_unavailable) else modelHint(agent, model)) {
                                val efforts = agent.effortLevels[model]
                                onChoice(choice.copy(agent = agent.id, model = model, effort = choice.effort.takeIf { agent.supportsEffort && (efforts == null || it in efforts) }))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelChoiceControls(agent: Agent?, choice: ModelChoice, onChoice: (ModelChoice) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (agent?.supportsEffort == true) {
            Text(tr(S.effort), style = MaterialTheme.typography.labelMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf("default") + (agent.effortLevels[choice.model] ?: listOf("low", "medium", "high", "xhigh", "max"))) { value ->
                    FilterChip((choice.effort ?: "default") == value, { onChoice(choice.copy(effort = value.takeUnless { it == "default" })) },
                        label = { Text(effortLabel(value)) }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
        }
        Text(tr(S.permission), style = MaterialTheme.typography.labelMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("auto", "plan", "full")) { permission -> FilterChip(choice.permission == permission,
                { onChoice(choice.copy(permission = permission)) }, label = { Text(permissionLabel(permission)) }, modifier = Modifier.heightIn(min = 48.dp)) }
        }
        Text(tr(when { choice.permission == "full" -> S.permission_full_hint; choice.permission == "plan" -> S.permission_plan_hint
            choice.agent == "grok" -> S.permission_grok_hint; else -> S.permission_auto_hint }), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
    }
}

@Composable
internal fun ChoiceRow(title: String, selected: Boolean, enabled: Boolean = true, subtitle: String? = null, onClick: () -> Unit) {
    Surface(color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected, onClick = null, enabled = enabled)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
