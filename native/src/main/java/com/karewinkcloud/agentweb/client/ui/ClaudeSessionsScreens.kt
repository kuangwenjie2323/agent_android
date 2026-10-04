package com.karewinkcloud.agentweb.client.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.karewinkcloud.agentweb.client.core.ClaudeSession
import com.karewinkcloud.agentweb.client.core.readableModelId
import com.karewinkcloud.agentweb.client.R.string as S
import com.karewinkcloud.agentweb.client.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun sessionTime(seconds: Long): String = runCatching {
    DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(seconds))
}.getOrDefault("")

@Composable
internal fun ColumnScope.ClaudeSessionsScreen(state: ClaudeListState, vm: AgentViewModel) {
    ScreenTitle("Claude Code") {
        ActionIcon(R.drawable.aw_refresh, tr(S.refresh), !state.loading) { vm.refreshClaudeSessions() }
    }
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (state.loading) item("loading") { LoadingState(tr(S.loading)) }
        if (state.error != null) item("error") { ErrorBlock(state.error, { vm.refreshClaudeSessions() }, tr(S.retry)) }
        if (!state.loading && state.error == null && state.sessions.isEmpty()) item("empty") {
            EmptyState(R.drawable.aw_terminal, tr(S.claude_empty), tr(S.claude_empty_hint))
        }
        state.sessions.groupBy { it.cwd }.forEach { (cwd, sessions) ->
            item("project-$cwd") {
                Column(Modifier.padding(start = 4.dp, top = 12.dp, bottom = 2.dp)) {
                    Text(sessions.first().projectName, Modifier.semantics { heading() }, style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(cwd, maxLines = 1, overflow = TextOverflow.StartEllipsis, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(sessions, key = { it.id }) { session ->
                Surface(onClick = { vm.openClaudeSession(session) }, shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(session.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${sessionTime(session.updatedAt)} · ${readableModelId(session.model)}", Modifier.weight(1f, false),
                                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (session.maybeActive) ActiveBadge()
                        }
                        if (session.linkedConversationId != null) Text(tr(S.linked_chat), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        if (state.nextCursor != null) item("more") {
            TextButton(onClick = { vm.refreshClaudeSessions(older = true) }, enabled = !state.loading,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(tr(S.load_more)) }
        }
    }
}

@Composable
private fun ActiveBadge() {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(8.dp)) {
        Text(tr(S.pc_active), Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
internal fun ColumnScope.ClaudeHistoryScreen(preview: ClaudePreviewState, vm: AgentViewModel) {
    val session = preview.session
    Toolbar(session.title, tr(S.readonly_preview, session.projectName), vm::back)
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    val scroll = rememberLazyListState()
    var landed by remember(session.id) { mutableStateOf(false) }
    LaunchedEffect(preview.loading, session.id) {
        if (!preview.loading && !landed && preview.messages.isNotEmpty()) {
            withFrameNanos { }
            landed = true
            scroll.scrollToItem((scroll.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
        }
    }
    LazyColumn(state = scroll, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item("metadata") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(session.cwd, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(listOfNotNull(session.model, session.entrypoint, session.gitBranch).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (preview.loading) item("loading") {
            LoadingState(tr(S.loading_chat))
        }
        if (preview.nextCursor != null) item("older") {
            TextButton(onClick = vm::olderClaudeHistory, enabled = !preview.loadingOlder && !preview.adopting,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (preview.loadingOlder) tr(S.loading) else countLabel(R.plurals.load_older, preview.olderCount))
            }
        }
        if (!preview.loading && preview.messages.isEmpty() && preview.error == null) item("empty") { EmptyState(R.drawable.aw_chat, tr(S.no_messages)) }
        items(preview.messages, key = { it.id }, contentType = { it.role }) { MessageView(it, modelLabel = (it.model ?: session.model.takeIf { model -> model.isNotBlank() })?.let(::readableModelId)) }
        if (preview.error != null) item("error") { ErrorBlock(preview.error, vm::refreshClaudePreview, tr(S.refresh)) }
    }
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (session.maybeActive) {
                ActiveBadge()
                Text(tr(S.close_pc_first), style = MaterialTheme.typography.bodySmall)
            }
            if (session.historyLimited) Text(tr(S.history_limited), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(onClick = vm::refreshClaudePreview, enabled = !preview.loading && !preview.adopting,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(tr(S.refresh)) }
                Button(onClick = vm::continueClaudeSession,
                    enabled = preview.canAdopt || (!preview.loading && !preview.adopting && session.linkedConversationId != null),
                    modifier = Modifier.weight(2f).heightIn(min = 48.dp), shape = RoundedCornerShape(16.dp)) {
                    Text(if (preview.adopting) tr(S.linking) else if (session.linkedConversationId != null) tr(S.open_existing) else tr(S.continue_here))
                }
            }
        }
    }
}
