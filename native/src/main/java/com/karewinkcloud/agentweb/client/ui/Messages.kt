package com.karewinkcloud.agentweb.client.ui

import androidx.compose.foundation.text.selection.SelectionContainer
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
                // The server notes whether follow-ups were waiting; say what to tap in either case.
                "server_restarted" -> tr(if ("Queued" in error.message) S.server_restarted_queue else S.server_restarted)
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


@Suppress("DEPRECATION")
@Composable
fun MessageView(message: ChatMessage, live: Boolean = false, modelLabel: String? = null,
    turn: TurnState? = null, connection: Connection? = null, canFork: Boolean = false,
    onRetry: (() -> Unit)? = null, onEdit: ((String) -> Unit)? = null, onAnswer: ((String, String) -> Unit)? = null) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(message.id) { mutableStateOf(false) }
    var menu by remember(message.id) { mutableStateOf(false) }
    var editing by rememberSaveable(message.id) { mutableStateOf(false) }
    var editText by rememberSaveable(message.id) { mutableStateOf(message.text) }
    val copyLabel = tr(S.copy_message)
    val menuLabel = tr(S.message_actions)
    val copy = { clipboard.setText(AnnotatedString(message.text)); copied = true; menu = false }
    // Long-press selects words like any text on Android (drag the handles to widen it); the copy and
    // edit buttons stay below the message, and accessibility services still reach the menu.
    Box(Modifier.fillMaxWidth().semantics {
        onLongClick(menuLabel) { menu = true; true }
        customActions = listOf(CustomAccessibilityAction(copyLabel) { copy(); true })
    }) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (message.role == "user") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Spacer(Modifier.weight(.15f))
                    Surface(modifier = Modifier.weight(.85f, fill = false).widthIn(max = 560.dp), color = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface, shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) { SelectionContainer { BlockContent(message.blocks, live) } }
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
                SelectionContainer { BlockContent(message.blocks, live, onAnswer) }
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
private fun BlockContent(blocks: List<ChatBlock>, live: Boolean, onAnswer: ((String, String) -> Unit)? = null) {
    // The ask_user call is shown as its question card, not again as a work step.
    val workBlocks = blocks.filter { (it is ChatBlock.Tool && !it.name.endsWith("ask_user")) || it is ChatBlock.Thought }
    val work = remember(workBlocks) { workBlocks }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (work.isNotEmpty()) WorkTrace(work, live, answerStarted = blocks.any { it is ChatBlock.Text && it.content.isNotBlank() })
        blocks.forEachIndexed { index, block -> key(index) {
            when (block) {
                is ChatBlock.Text -> Markdown(block.content, streaming = live && index == blocks.indexOfLast { it is ChatBlock.Text })
                is ChatBlock.Error -> ErrorBlock(block.problem)
                is ChatBlock.Notice -> Text(localMessage(block.content), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                is ChatBlock.Media -> block.items.forEach { ChatMediaCard(it) }
                is ChatBlock.Question -> QuestionCard(block, onAnswer)
                is ChatBlock.Steer -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large,
                        modifier = Modifier.widthIn(max = 300.dp)) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(tr(S.steer_label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(block.content, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
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

/** A choice the agent asked for: tap an option (or type your own answer in the box) and it continues. */
@Composable
private fun QuestionCard(block: ChatBlock.Question, onAnswer: ((String, String) -> Unit)?) {
    val open = block.answer == null && onAnswer != null
    val picked = remember(block.id) { mutableStateListOf<String>() }
    val chosen = block.answer?.split("、")?.map { it.trim() }.orEmpty()
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, if (open) MaterialTheme.colorScheme.primary.copy(alpha = .6f) else MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(block.question, style = MaterialTheme.typography.titleSmall)
            block.options.forEach { option ->
                val selected = option in chosen || option in picked
                OutlinedButton(onClick = {
                    if (block.multi) { if (option in picked) picked.remove(option) else picked.add(option) }
                    else onAnswer?.invoke(block.id, option)
                }, enabled = open, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                    border = BorderStroke(if (selected) 2.dp else 1.dp,
                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    colors = ButtonDefaults.outlinedButtonColors(disabledContentColor = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant)) {
                    if (selected) { Box(Modifier.size(18.dp)) { AppIcon(R.drawable.aw_check) }; Spacer(Modifier.width(6.dp)) }
                    Text(option, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (open && block.multi) Button(onClick = { onAnswer?.invoke(block.id, picked.joinToString("、")) }, enabled = picked.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()) { Text(tr(S.question_confirm)) }
            Text(tr(when {
                block.answer != null && block.answer !in block.options && chosen.none { it in block.options } -> S.question_answered_custom
                block.answer != null -> S.question_answered
                open -> S.question_hint
                else -> S.question_unanswered
            }, block.answer.orEmpty()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
