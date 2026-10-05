package com.karewinkcloud.agentweb.client.ui

import androidx.compose.ui.graphics.graphicsLayer

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.karewinkcloud.agentweb.client.core.*
import com.karewinkcloud.agentweb.client.data.Connection
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.*
import com.karewinkcloud.agentweb.client.R
import com.karewinkcloud.agentweb.client.R.string as S
import kotlinx.coroutines.delay

@Composable
fun ErrorBlock(error: ClientError, onRetry: (() -> Unit)? = null, action: String? = null) {
    // A grey card with a red mark, like Apple's inline alerts; the text stays readable.
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.fillMaxWidth().padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(20.dp)) { CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.error) { AppIcon(R.drawable.aw_error) } }
                Text(tr(S.error_title), style = MaterialTheme.typography.titleSmall)
            }
            Text(when (error.code) {
                "stream_mismatch" -> tr(S.chat_stream_error)
                "fork_project_required", "project_not_found" -> tr(S.fork_project_required)
                "fork_source_busy" -> tr(S.fork_source_busy)
                "fork_invalid_anchor" -> tr(S.fork_invalid_anchor)
                "fork_attachment_unavailable" -> tr(S.fork_attachment_unavailable)
                "fork_checkpoint_unavailable", "workspace_unavailable", "fork_workspace_failed" -> tr(S.fork_checkpoint_unavailable)
                "fork_idempotency_conflict" -> tr(S.fork_idempotency_conflict)
                "fork_cleanup_failed", "fork_state_unavailable", "fork_request_failed", "fork_persist_failed" -> tr(S.fork_request_failed)
                "invalid_protocol_range", "protocol_major_mismatch", "protocol_version_mismatch" -> tr(S.chat_protocol_error)
                else -> localMessage(error.message)
            }, style = MaterialTheme.typography.bodyMedium)
            if (error.retryable == false) Text(tr(S.not_retried), style = MaterialTheme.typography.bodySmall)
            if (onRetry != null) TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 40.dp),
                contentPadding = PaddingValues(horizontal = 0.dp)) { Text(action ?: tr(S.reconnect), fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) }
        }
    }
}

/** Grey text buttons for secondary message actions; the accent stays for primary actions. */
@Composable
internal fun quietButton() = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)

/** Observe the initial pass so nested selectable code cannot swallow whole-message copy. */
private fun Modifier.messageLongPress(onLongPress: () -> Unit): Modifier = pointerInput(onLongPress) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val releasedOrDragged = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            var canceled = false
            while (!canceled) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                canceled = event.changes.none { it.pressed } || event.changes.any {
                    (it.position - down.position).getDistance() > viewConfiguration.touchSlop
                }
            }
            true
        }
        if (releasedOrDragged == null) {
            onLongPress()
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }
}

@Suppress("DEPRECATION")
@Composable
fun MessageView(message: ChatMessage, live: Boolean = false, modelLabel: String? = null,
    turn: TurnState? = null, connection: Connection? = null, canFork: Boolean = false,
    onRetry: (() -> Unit)? = null, onEdit: ((String) -> Unit)? = null) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(message.id) { mutableStateOf(false) }
    var menu by remember(message.id) { mutableStateOf(false) }
    var editing by rememberSaveable(message.id) { mutableStateOf(false) }
    var editText by rememberSaveable(message.id) { mutableStateOf(message.text) }
    val copyLabel = tr(S.copy_message)
    val menuLabel = tr(S.message_actions)
    val copy = { clipboard.setText(AnnotatedString(message.text)); copied = true; menu = false }
    val openMenu = remember(message.id) { { menu = true } }
    Box(Modifier.fillMaxWidth().messageLongPress(openMenu).semantics {
        onLongClick(menuLabel) { menu = true; true }
        customActions = listOf(CustomAccessibilityAction(copyLabel) { copy(); true })
    }) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (message.role == "user") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Spacer(Modifier.weight(.15f))
                    Surface(modifier = Modifier.weight(.85f, fill = false).widthIn(max = 560.dp), color = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface, shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) { BlockContent(message.blocks, live) }
                    }
                }
                if (onEdit != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { editText = message.text; editing = true }, enabled = canFork,
                        modifier = Modifier.heightIn(min = 40.dp), colors = quietButton()) {
                        Box(Modifier.size(18.dp)) { AppIcon(R.drawable.aw_edit) }; Spacer(Modifier.width(4.dp))
                        Text(tr(S.edit_message), style = MaterialTheme.typography.labelLarge)
                    }
                }
            } else {
                (modelLabel ?: message.model)?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                BlockContent(message.blocks, live)
                if (turn != null && !turn.done) WorkingIndicator(turn, connection)
                else if (!live || turn?.done == true) TurnFooter(message)
                if (!live) Row(verticalAlignment = Alignment.CenterVertically) {
                    if (message.text.isNotBlank()) ActionIcon(if (copied) R.drawable.aw_check else R.drawable.aw_copy,
                        tr(if (copied) S.copied else S.copy_response), onClick = copy)
                    if (onRetry != null) TextButton(onClick = onRetry, enabled = canFork, modifier = Modifier.heightIn(min = 40.dp), colors = quietButton()) {
                        Box(Modifier.size(18.dp)) { AppIcon(R.drawable.aw_refresh) }; Spacer(Modifier.width(4.dp))
                        Text(tr(S.retry), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text(tr(S.copy_message)) }, leadingIcon = { AppIcon(R.drawable.aw_copy) }, onClick = copy)
            if (onEdit != null) DropdownMenuItem(text = { Text(tr(S.edit_message)) }, enabled = canFork,
                leadingIcon = { AppIcon(R.drawable.aw_edit) }, onClick = { menu = false; editText = message.text; editing = true })
        }
    }
    if (editing) AlertDialog(onDismissRequest = { editing = false }, title = { Text(tr(S.edit_message)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tr(S.edit_variant_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(editText, { editText = it }, Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    label = { Text(tr(S.message)) }, minLines = 3, maxLines = 10)
            }
        }, confirmButton = {
            TextButton(onClick = { onEdit?.invoke(editText); editing = false }, enabled = canFork && editText.isNotBlank()) { Text(tr(S.edit_submit)) }
        }, dismissButton = { TextButton(onClick = { editing = false }) { Text(tr(S.cancel)) } })
}

@Composable
private fun BlockContent(blocks: List<ChatBlock>, live: Boolean) {
    val workBlocks = blocks.filter { it is ChatBlock.Tool || it is ChatBlock.Thought }
    val work = remember(workBlocks) { workBlocks }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (work.isNotEmpty()) WorkTrace(work, live, answerStarted = blocks.any { it is ChatBlock.Text && it.content.isNotBlank() })
        blocks.forEachIndexed { index, block -> key(index) {
            when (block) {
                is ChatBlock.Text -> Markdown(block.content, streaming = live && index == blocks.indexOfLast { it is ChatBlock.Text })
                is ChatBlock.Error -> ErrorBlock(block.problem)
                is ChatBlock.Notice -> Text(localMessage(block.content), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                is ChatBlock.Media -> block.items.forEach { ChatMediaCard(it) }
                else -> Unit
            }
        } }
    }
}

@Composable
private fun WorkTrace(blocks: List<ChatBlock>, live: Boolean, answerStarted: Boolean = false) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    // Like leading chat apps: once the answer starts streaming, fold the reasoning away.
    LaunchedEffect(live, answerStarted) { if (live && answerStarted) expanded = false }
    // Label stays "running" for the whole live turn; per-step status flipped it between steps.
    val running = live
    val now by produceState(System.nanoTime() / 1_000_000, running) {
        while (running) { value = System.nanoTime() / 1_000_000; delay(1000) }
    }
    val timedStarts = blocks.mapNotNull { when (it) {
        is ChatBlock.Tool -> it.startedAt.takeIf { t -> t > 0 }
        is ChatBlock.Thought -> it.startedAt.takeIf { t -> t > 0 }
        else -> null
    } }
    val ends = blocks.mapNotNull { when (it) {
        is ChatBlock.Tool -> it.startedAt.takeIf { t -> t > 0 }?.let { start -> start + (it.durationMs ?: (now - start)) }
        is ChatBlock.Thought -> it.startedAt.takeIf { t -> t > 0 }?.let { start -> start + (it.durationMs ?: (now - start)) }
        else -> null
    } }
    val reasoningDuration = blocks.sumOf {
        when (it) {
            is ChatBlock.Thought -> it.durationMs ?: 0L
            else -> 0L
        }.coerceAtLeast(0)
    }
    val duration = if (timedStarts.isEmpty()) null else (ends.maxOrNull()!! - timedStarts.min()).coerceAtLeast(0)
    val failures = blocks.count { it is ChatBlock.Tool && it.status == StepStatus.ERROR }
    val expansion = tr(if (expanded) S.expanded else S.collapsed)
    Surface(color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp)) {
        Column {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
                .semantics { stateDescription = expansion },
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp), colors = quietButton()) {
                Box(Modifier.size(18.dp).graphicsLayer { rotationZ = if (expanded) 90f else 0f }) { AppIcon(R.drawable.aw_chevron) }
                Spacer(Modifier.width(6.dp))
                Text(countLabel(if (running) R.plurals.working else R.plurals.trace, blocks.size) +
                    if (failures > 0) " · " + countLabel(R.plurals.failed_steps, failures) else "", Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text(when { duration != null -> tr(S.seconds, duration / 1000)
                    reasoningDuration > 0 -> tr(S.reasoning_seconds, reasoningDuration / 1000)
                    else -> tr(S.details) }, style = MaterialTheme.typography.bodySmall)
            }
            // Bounded, independently scrolling body so a long reasoning trace never
            // forces a long scroll back to the header; a footer button collapses it.
            if (expanded) Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 12.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                blocks.forEach { block ->
                    when (block) {
                        is ChatBlock.Tool -> {
                            TraceStep(toolCommand(block), block.status, block.durationMs ?: if (running && block.status == StepStatus.RUNNING && block.startedAt > 0) now - block.startedAt else null)
                            compactToolDiff(block)?.let { diff ->
                                CodeBlock(diff, "diff")
                            }
                            if (block.status == StepStatus.ERROR && !block.result.isNullOrBlank()) ErrorBlock(ClientError(block.result.take(2000)))
                        }
                        is ChatBlock.Thought -> {
                            TraceStep(tr(S.reasoning_summary), block.status, block.durationMs ?: if (running && block.startedAt > 0) now - block.startedAt else null)
                            if (block.content.isNotBlank()) Text(block.content, style = MaterialTheme.typography.bodySmall)
                        }
                        else -> Unit
                    }
                }
            }
            if (expanded) TextButton(onClick = { expanded = false },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(tr(S.collapse)) }
        }
    }
}

@Composable private fun stepLabel(status: StepStatus) = tr(when(status) {
    StepStatus.RUNNING -> S.step_running; StepStatus.COMPLETE -> S.step_complete; StepStatus.ERROR -> S.step_error; StepStatus.INTERRUPTED -> S.step_interrupted
})

@Composable
private fun TraceStep(title: String, status: StepStatus, duration: Long?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Text(when (status) { StepStatus.COMPLETE -> "✓"; StepStatus.RUNNING -> "●"; StepStatus.ERROR -> "!"; StepStatus.INTERRUPTED -> "–" },
            color = if (status == StepStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(stepLabel(status), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (duration != null) Text(tr(S.seconds, duration.coerceAtLeast(0) / 1000), style = MaterialTheme.typography.bodySmall)
    }
}
