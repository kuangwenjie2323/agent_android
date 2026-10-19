@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.karewinkcloud.agentweb.client.ui

import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.DragInteraction
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.delay
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.karewinkcloud.agentweb.client.R
import com.karewinkcloud.agentweb.client.R.string as S
import com.karewinkcloud.agentweb.client.core.*
import com.karewinkcloud.agentweb.client.data.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.onBackground() }
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
    val seen by vm.seen.collectAsStateWithLifecycle()
    // The list saved on the phone can be stale: only a fresh one sets what counts as read.
    LaunchedEffect(state.conversations, state.refreshing) { if (!state.refreshing) vm.adoptSeen(state.conversations) }
    // While a chat runs or waits on the user, keep the statuses current (only while the app is on screen).
    val busy = state.conversations.any { it.running || it.waitingInput }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(busy, lifecycle) {
        if (busy) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { delay(5_000); vm.refreshList() } }
    }
    // Rows that arrive above the first visible one (the fresh list after the cached one, a chat that just
    // became the newest) would sit hidden above it, since the list keeps that row in place. Near the top,
    // show the newest instead.
    val newest = state.sections.firstOrNull()?.conversations?.firstOrNull()?.id
    LaunchedEffect(newest) { if (list.firstVisibleItemIndex <= 2 && !list.isScrollInProgress) list.scrollToItem(0) }
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
        // The top indicator answers a pull only; background refreshes stay quiet and an
        // empty first load shows the single in-list loading row instead.
        var pulled by remember { mutableStateOf(false) }
        LaunchedEffect(state.refreshing) { if (!state.refreshing) pulled = false }
        PullToRefreshBox(state.refreshing && pulled, { pulled = true; vm.refresh() }) {
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
                        ConversationRow(conversation, state.agents, rowPosition(index, section.conversations.size),
                            unread = conversation.unread(seen[conversation.id])) { vm.open(conversation) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ConversationRow(conversation: Conversation, agents: List<Agent>, position: RowPosition = RowPosition.ONLY,
    unread: Boolean = false, onOpen: () -> Unit) {
    val model = agents.find { it.id == conversation.choice.agent }?.label(conversation.choice.model)
        ?: conversation.choice.model.ifBlank { tr(S.assistant) }
    val preview = "${compactModelLabel(model)}: ${plainPreview(conversation.preview).ifBlank { if (conversation.messageCount == null) "…" else tr(S.unused_chat) }}"
    ListRow(conversationTitle(conversation), preview, position, leading = { Avatar(modelBadge(conversation.choice), tint = Color(modelTint(conversation.choice))) },
        extra = conversation.project?.takeIf { it.isNotBlank() }?.let { project -> {
            Text(project, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        } },
        trailing = { ConversationStatus(conversation, unread, conversation.updatedAt.takeIf { it > 0 }?.let { conversationTime(it) }) },
        emphasized = unread, onClick = onOpen)
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
    // From Claude's desktop app: a list to jump between the questions of a long chat, and side questions
    // that neither steer nor interrupt the agent.
    var outline by remember(chat.conversation.id) { mutableStateOf(false) }
    var sideChat by remember(chat.conversation.id) { mutableStateOf(false) }
    var jumpTo by remember(chat.conversation.id) { mutableStateOf<String?>(null) }
    // Also from the desktop app: files the chat names open at full length (a step's file, a path in an answer),
    // and a list of the files the chat changed.
    var filesSheet by remember(chat.conversation.id) { mutableStateOf(false) }
    var openedFile by remember(chat.conversation.id) { mutableStateOf<FileRef?>(null) }
    val openFile: (FileRef) -> Unit = remember(chat.conversation.id) { { ref -> openedFile = ref } }
    val changedAny = remember(chat.messages) { changedFiles(chat.messages.map { it.blocks }).isNotEmpty() }
    CompositionLocalProvider(LocalOpenFile provides openFile) {
        if (outline) OutlineSheet(chat.messages, chat.nextCursor != null, vm::loadOlder, { id -> outline = false; jumpTo = id }) { outline = false }
        if (sideChat) SideChatSheet(chat.side, vm::askSide, { failed -> vm.dropSide(failed); vm.askSide(failed.question) }) { sideChat = false }
        if (filesSheet) ChatFilesHost(vm, chat, { path -> openedFile = FileRef(path) }) { filesSheet = false }
        openedFile?.let { ref ->
            FileViewer(ref, vm::readFile, { path -> chatEdits(chat.messages.map { it.blocks } + listOfNotNull(vm.liveTurn.value?.blocks), path) }) {
                openedFile = null
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Toolbar(conversationTitle(chat.conversation), chat.conversation.project ?: tr(S.default_project), vm::back) }
            if (!chat.conversation.nativeControl) ActionIcon(R.drawable.aw_side_chat, tr(S.side_title)) { sideChat = true }
            if (chat.messages.any { it.role == "user" }) ActionIcon(R.drawable.aw_outline, tr(S.outline_title)) { outline = true }
            Box {
                ActionIcon(R.drawable.aw_more, tr(S.more)) { overflow = true }
                DropdownMenu(overflow, { overflow = false }) {
                    DropdownMenuItem(text = { Text(tr(S.model_settings)) }, onClick = { overflow = false; onModels() })
                    if (changedAny || chat.live != null) DropdownMenuItem(text = { Text(tr(S.files_title)) }, onClick = { overflow = false; filesSheet = true })
                    DropdownMenuItem(text = { Text(tr(S.reconnect)) }, onClick = { overflow = false; vm.reload() })
                }
            }
        }
        // Background tasks: steps, notices and the pill open one list, where output is read and tasks stopped.
        var taskSheet by remember(chat.conversation.id) { mutableStateOf<String?>(null) }
        val taskControls = remember(vm, chat.conversation.id) { TaskControls(vm::taskOutput, vm::stopTask) { id -> taskSheet = id ?: "" } }
        CompositionLocalProvider(LocalTaskControls provides taskControls) {
            taskSheet?.let { request -> TaskSheetHost(vm, chat, request) { taskSheet = null } }
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
                // Selected text clears like anywhere on Android: a tap elsewhere or a scroll ends it (selection
                // lives while its container is focused; the handles would otherwise stay behind when the list moves).
                val focus = LocalFocusManager.current
                // Only the user's own drag stops following the newest text. Watching "a scroll is in progress"
                // also caught the list's own jumps to the end (seen a moment late), which left a streaming
                // answer running on below the screen.
                LaunchedEffect(list) {
                    list.interactionSource.interactions.collect { interaction ->
                        if (interaction is DragInteraction.Start) { followTail = false; focus.clearFocus(force = true) }
                    }
                }
                LaunchedEffect(list) {
                    snapshotFlow { list.isScrollInProgress to list.canScrollForward }.distinctUntilChanged().collect { (scrolling, canForward) ->
                        if (!scrolling && !canForward) followTail = true
                    }
                }
                LaunchedEffect(list, followTail, chat.loading) {
                    snapshotFlow { list.layoutInfo.let { info ->
                        Triple(info.totalItemsCount, info.visibleItemsInfo.lastOrNull()?.size, info.viewportEndOffset)
                    } }.distinctUntilChanged().collect {
                        if (followTail && !chat.loading) {
                            val total = list.layoutInfo.totalItemsCount
                            // One clamped jump to the very end. Scrolling to the item's top and then
                            // down by its height showed an intermediate frame on every growth (jitter).
                            if (total > 0) list.scrollToItem(total - 1, Int.MAX_VALUE)
                        }
                    }
                }
                LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth().clearSelectionOnTap(focus), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
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
                        MessageView(message, modelLabel = message.model?.let { model -> state.agents.find { model.removeSuffix("[1m]") in it.models }?.label(model) ?: readableModelId(model) },
                            canFork = chat.canFork,
                            onRetry = if (message.role == "assistant" && message.id == chat.messages.lastOrNull { it.role == "assistant" }?.id) ({ vm.retry(message.id) }) else null,
                            onEdit = if (message.role == "user" && message.id == chat.messages.lastOrNull { it.role == "user" }?.id) ({ text -> vm.edit(message.id, text) }) else null)
                    }
                    if (chat.live != null) item("live-${chat.live.runId}") {
                        LiveMessage(vm, (chat.liveModel ?: chat.conversation.choice.model).takeIf { it.isNotBlank() }?.let { model ->
                            state.agents.find { model in it.models }?.label(model) ?: model
                        }, chat.connection)
                    }
                    if (chat.suggestions.isNotEmpty() && !chat.running && chat.live == null && chat.messages.lastOrNull()?.id == chat.suggestionsFor)
                        item("suggestions-${chat.suggestionsFor}") { QuickReplies(chat.suggestions, enabled = chat.canSend, onPick = vm::sendSuggestion) }
                    if (chat.error != null) item("error") { ErrorBlock(chat.error, vm::reload, tr(S.reconnect)) }
                }
                val lastId = chat.messages.lastOrNull()?.id
                LaunchedEffect(chat.conversation.id, lastId, chat.running, chat.live == null) { vm.loadSuggestions() }
                if (!followTail) TextButton({ followTail = true }, Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp)) {
                    AppIcon(R.drawable.aw_down); Text(tr(S.latest_messages))
                }
                // Sending (a message, a mid-turn message, a queued one) shows the newest, like any messenger.
                LaunchedEffect(chat.sends) { if (chat.sends > 0) followTail = true }
                // A question picked in the outline: its message at the top of the screen.
                LaunchedEffect(jumpTo) {
                    val target = jumpTo ?: return@LaunchedEffect
                    val index = chat.messages.indexOfFirst { it.id == target }
                    if (index >= 0) {
                        followTail = false
                        list.scrollToItem(index + (if (chat.loading) 1 else 0) + (if (chat.nextCursor != null) 1 else 0))
                    }
                    jumpTo = null
                }
                LaunchedEffect(followTail) {
                    if (followTail && list.layoutInfo.totalItemsCount > 0) {
                        list.scrollToItem(list.layoutInfo.totalItemsCount - 1, Int.MAX_VALUE)
                    }
                }
            }
            Composer(chat, state, vm, (availableHeight * .55f).coerceAtLeast(100.dp), onModels)
        }
    }
}

/** The files the open chat changed, the running turn's included. */
@Composable
private fun ChatFilesHost(vm: AgentViewModel, chat: ChatState, onOpen: (String) -> Unit, onClose: () -> Unit) {
    val turn by vm.liveTurn.collectAsStateWithLifecycle()
    // Keyed on the running turn's steps, not its text: a new word must not redo every diff.
    val liveSteps = turn?.blocks?.filterIsInstance<ChatBlock.Tool>().orEmpty()
    val files = remember(chat.messages, liveSteps) { changedFiles(chat.messages.map { it.blocks } + listOf(liveSteps)) }
    ChatFilesSheet(files, onOpen, onClose)
}

/** The task list for the open conversation: the running turn's tasks, or those of the saved turn a notice is in. */
@Composable
private fun TaskSheetHost(vm: AgentViewModel, chat: ChatState, request: String, onClose: () -> Unit) {
    val turn by vm.liveTurn.collectAsStateWithLifecycle()
    val liveTasks = turn?.let { backgroundTasks(it.blocks) }.orEmpty()
    val saved = if (request.isEmpty() || liveTasks.any { it.second.id == request }) null
        else chat.messages.firstNotNullOfOrNull { message -> backgroundTasks(message.blocks).takeIf { tasks -> tasks.any { it.second.id == request } } }
    BackgroundTasksSheet(saved ?: liveTasks, live = saved == null && turn?.done == false, selected = request.ifEmpty { null }, onClose)
}

@Composable
private fun LiveMessage(vm: AgentViewModel, label: String?, connection: Connection?) {
    val turn by vm.liveTurn.collectAsStateWithLifecycle()
    turn?.let { MessageView(ChatMessage("live-${it.runId}", "assistant", it.blocks, usage = it.usage),
        live = !it.done || it.revealing, modelLabel = label, turn = it, connection = connection,
        onAnswer = if (it.done) null else { id, text -> vm.answer(id, text) }) }
}

@Composable
private fun Composer(chat: ChatState, state: ClientState, vm: AgentViewModel, maxHeight: Dp, onModels: () -> Unit) {
    val agent = state.agents.find { it.id == state.choice.agent }
    var projectsOpen by remember { mutableStateOf(false) }
    if (projectsOpen) ProjectSheet(state, vm) { projectsOpen = false }
    ComposerSurface(maxHeight, actions = {
        if (!chat.conversation.nativeControl) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                AttachmentPicker(chat, agent?.readsImages(state.choice.model) == true, vm)
                ComposerChip(agent?.label(state.choice.model)?.let { label -> composerModelLabel(compactModelLabel(label),
                    state.choice.effort?.takeIf { agent.supportsEffort }?.let { effortLabel(it) },
                    state.choice.permission.takeIf { it != "auto" }?.let { permissionLabel(it) }) } ?: tr(S.choose_model),
                    onModels, Modifier.weight(1f), selected = true)
                if (chat.queueUncertain || (chat.running && chat.hasDraft)) ActionIcon(
                    if (chat.queueUncertain) R.drawable.aw_refresh else R.drawable.aw_queue,
                    tr(if (chat.queueUncertain) S.check_queue else S.queue), !chat.controlBusy && !chat.attachmentLoading, vm::queue)
                // While the agent works, text in the box is sent into the running turn (it reads it at
                // its next step); an empty box keeps the button as Stop.
                val steering = chat.running && chat.hasDraft && !chat.conversation.nativeControl
                FilledIconButton(onClick = if (steering) vm::steer else if (chat.running) vm::stop else vm::send,
                    // Steering without a known run queues instead of greying out (vm.steer falls back).
                    enabled = if (steering) !chat.controlBusy && !chat.attachmentLoading
                        else if (chat.running) !chat.controlBusy && !chat.stopRequested && chat.controlId != null
                        else chat.canSend && chat.hasDraft && agent?.available == true && !state.creating, modifier = Modifier.size(48.dp)) {
                    if (chat.controlBusy && (steering || chat.running)) CircularProgressIndicator(
                        modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    else AppIcon(if (chat.running && !steering) R.drawable.aw_stop else R.drawable.aw_send,
                        tr(if (steering) S.steer_send else if (chat.running) S.stop else S.send))
                }
            }
        }
    }) {
        if (chat.conversation.nativeControl) { StatusNotice(tr(S.desktop_controlled)); return@ComposerSurface }
        // Only the count is watched here, so a streaming answer does not redraw the composer.
        val runningTasks by remember(vm) { vm.liveTurn.map { turn -> turn?.takeUnless { it.done }
            ?.let { live -> backgroundTasks(live.blocks).count { it.second.running } } ?: 0 }.distinctUntilChanged() }
            .collectAsStateWithLifecycle(0)
        if (runningTasks > 0) LocalTaskControls.current?.let { controls -> BackgroundTasksPill(runningTasks) { controls.open(null) } }
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
        // The chosen subscription is used up or nearly: say so before the message fails on it.
        agent?.limits?.takeIf { it.exhausted || it.highest >= .9 }?.let { limits -> StatusNotice(usageLimitsLabel(limits)) }
        // Connecting shows in the turn's own status line; a notice here made the box grow and shrink at every send.
        if (chat.connection == Connection.RECONNECTING || chat.stopRequested) StatusNotice(tr(
            if (chat.stopRequested) S.stop_requested else S.reconnecting), busy = true)
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
    AppModalBottomSheet(onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = groupedPageColor()) {
        BoxWithConstraints(Modifier.fillMaxWidth().fillMaxHeight(.88f)) {
            val pinControls = maxHeight >= 520.dp * LocalDensity.current.fontScale
            val visibleAgents = agents.filter { it.enabled }
            // Start at the current model's group in the very first frame. Scrolling after the sheet
            // appeared moved rows under a finger mid-tap and picked the wrong model.
            // Compact layouts stay at the controls.
            val initialIndex = remember { if (!pinControls) 0 else visibleAgents.indexOfFirst { it.id == choice.agent }.coerceAtLeast(0) }
            val modelListState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
            Column(Modifier.fillMaxSize()) {
                SheetHeader(tr(S.model_settings), onClose)
                val agent = agents.find { it.id == choice.agent }
                if (pinControls) ModelChoiceControls(agent, choice, onChoice, Modifier.padding(horizontal = 16.dp))
                LazyColumn(state = modelListState, modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp)) {
                    if (!pinControls) item("controls") { ModelChoiceControls(agent, choice, onChoice) }
                    if (agents.isEmpty()) item { TextButton(onRefresh, Modifier.heightIn(min = 48.dp)) { Text(tr(S.refresh)) } }
                    items(visibleAgents, key = { "agent-${it.id}" }) { agent ->
                        // Claude: how much of the subscription's usage windows is used, from the last turn's report.
                        GroupedSection(agent.name, footer = agent.limits?.let { usageLimitsLabel(it) }) {
                            agent.models.forEachIndexed { index, model ->
                                if (index > 0) RowDivider()
                                CheckRow(agent.label(model), choice.agent == agent.id && choice.model == model, agent.available,
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
}

@Composable
private fun ModelChoiceControls(agent: Agent?, choice: ModelChoice, onChoice: (ModelChoice) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        if (agent?.supportsEffort == true) {
            val efforts = listOf("default") + (agent.effortLevels[choice.model] ?: listOf("low", "medium", "high", "xhigh", "max"))
            val pick = { value: String -> onChoice(choice.copy(effort = value.takeUnless { it == "default" })) }
            GroupLabel(tr(S.effort))
            // Up to six short choices fit a segmented control; more read better as a menu.
            if (efforts.size <= 6) SegmentedControl(efforts.map { it to effortLabel(it) }, choice.effort ?: "default", onSelect = pick)
            else GroupedSection(null, Modifier.padding(bottom = 0.dp)) {
                MenuValueRow(tr(S.effort), choice.effort ?: "default", efforts.map { it to effortLabel(it) }, onChange = pick)
            }
            Spacer(Modifier.height(16.dp))
        }
        GroupLabel(tr(S.permission))
        SegmentedControl(listOf("auto", "plan", "full").map { it to permissionLabel(it) }, choice.permission) {
            onChoice(choice.copy(permission = it))
        }
        Text(tr(when { choice.permission == "full" -> S.permission_full_hint; choice.permission == "plan" -> S.permission_plan_hint
            choice.agent == "grok" -> S.permission_grok_hint; else -> S.permission_auto_hint }), Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ChoiceRow(title: String, selected: Boolean, enabled: Boolean = true, subtitle: String? = null, onClick: () -> Unit) {
    // Plain row with a trailing check, like Apple's pickers.
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(10.dp))
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.SemiBold else null,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) Box(Modifier.size(22.dp)) { CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) { AppIcon(R.drawable.aw_check) } }
        }
        HorizontalDivider(Modifier.padding(start = 8.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** Likely next messages under the latest answer; tapping one sends it. */
@Composable
private fun QuickReplies(items: List<String>, enabled: Boolean, onPick: (String) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { text ->
            SuggestionChip(onClick = { onPick(text) }, enabled = enabled, label = { Text(text, maxLines = 2) },
                shape = MaterialTheme.shapes.large)
        }
    }
}

/** A short tap anywhere in the list (seen before children handle it) drops the current text selection. */
private fun Modifier.clearSelectionOnTap(focus: androidx.compose.ui.focus.FocusManager): Modifier = pointerInput(focus) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation(PointerEventPass.Initial) }
        if (up != null && (up.position - down.position).getDistance() < viewConfiguration.touchSlop) focus.clearFocus(force = true)
    }
}
