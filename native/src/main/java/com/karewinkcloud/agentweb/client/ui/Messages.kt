package com.karewinkcloud.agentweb.client.ui

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.graphics.graphicsLayer

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
                "task_not_running" -> tr(S.task_not_running)
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
            val grokBot = if (message.role == "user") remember(message.text) { grokBotMessage(message.text) } else null
            if (grokBot != null) {
                // Forwarded by the server from a Grok Bot agent: the agent's card, not the user's bubble.
                GrokBotCard(grokBot)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ActionIcon(if (copied) R.drawable.aw_check else R.drawable.aw_copy, tr(if (copied) S.copied else S.copy_message)) {
                        clipboard.setText(AnnotatedString(grokBot.text)); copied = true
                    }
                }
            } else if (message.role == "user") {
                // Like a messenger: pictures and files sit above the bubble on their own, the bubble holds the words.
                val media = message.blocks.filterIsInstance<ChatBlock.Media>().flatMap { it.items }
                val words = message.blocks.filterNot { it is ChatBlock.Media }
                if (media.isNotEmpty()) UserAttachments(media)
                if (words.any { it !is ChatBlock.Text || it.content.isNotBlank() }) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Spacer(Modifier.weight(.15f))
                    Surface(modifier = Modifier.weight(.85f, fill = false).widthIn(max = 560.dp), color = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface, shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) { SelectionContainer { BlockContent(words, live) } }
                    }
                }
                if (onEdit != null || message.text.isNotBlank()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically) {
                    // Copy the whole message (long-press now selects words instead of opening a menu).
                    if (message.text.isNotBlank()) ActionIcon(if (copied) R.drawable.aw_check else R.drawable.aw_copy,
                        tr(if (copied) S.copied else S.copy_message), onClick = copy)
                    if (onEdit != null) TextButton(onClick = { editText = message.text; editing = true }, enabled = canFork,
                        modifier = Modifier.heightIn(min = 40.dp), colors = quietButton()) {
                        Box(Modifier.size(18.dp)) { AppIcon(R.drawable.aw_edit) }; Spacer(Modifier.width(4.dp))
                        Text(tr(S.edit_message), style = MaterialTheme.typography.labelLarge)
                    }
                }
            } else {
                (modelLabel ?: message.model)?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                SelectionContainer { BlockContent(message.blocks, live, onAnswer) }
                // One status line under the turn for as long as it runs, like Claude Code's spinner line.
                // Hiding it while a step ran made it pop in and out between steps, and the page jumped.
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
    // Reading order, like Claude: thinking and tool steps sit between the pieces of the answer they led to.
    val pieces = remember(blocks) { turnPieces(blocks) }
    val streamingText = if (live) blocks.indexOfLast { it is ChatBlock.Text } else -1
    val taskNames = remember(blocks) { backgroundTasks(blocks).associate { (tool, task) -> task.id to taskName(tool, task) } }
    CompositionLocalProvider(LocalTaskNames provides taskNames) { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        pieces.forEachIndexed { position, piece ->
            key(if (piece is TurnPiece.Work) "w${piece.first}" else "b${(piece as TurnPiece.Single).index}") {
                when (piece) {
                    // Only the latest work of a running turn stays open; earlier work folds into one line each.
                    is TurnPiece.Work -> WorkSection(piece.steps, live, current = live && position == pieces.lastIndex, key = piece.first)
                    is TurnPiece.Single -> TurnBlock(piece.block, streaming = piece.index == streamingText, onAnswer)
                }
            }
        }
    } }
}

@Composable
private fun TurnBlock(block: ChatBlock, streaming: Boolean, onAnswer: ((String, String) -> Unit)?) {
    when (block) {
        is ChatBlock.Text -> Markdown(block.content, streaming = streaming)
        is ChatBlock.Error -> ErrorBlock(block.problem)
        is ChatBlock.Notice -> Text(localMessage(block.content), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        is ChatBlock.Media -> block.items.forEach { ChatMediaCard(it) }
        is ChatBlock.Question -> QuestionCard(block, onAnswer)
        is ChatBlock.TaskNotice -> TaskNoticeRow(block)
        is ChatBlock.Steer -> grokBotMessage(block.content)?.let { GrokBotCard(it) } ?: Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large,
                modifier = Modifier.widthIn(max = 300.dp)) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(tr(S.steer_label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (block.items.isNotEmpty()) UserAttachments(block.items)
                    if (block.content.isNotBlank()) Text(block.content, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        else -> Unit
    }
}

/**
 * A message from one of the user's Grok Bot agents, forwarded into this conversation: on the left with the
 * agent's name, like another participant, so it never reads as something the user typed.
 */
@Composable
private fun GrokBotCard(message: GrokBotMessage) {
    Row(Modifier.fillMaxWidth()) {
        Surface(Modifier.weight(.88f, fill = false).widthIn(max = 560.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(22.dp).clip(CircleShape).background(Color(0xff3a3a3c)),
                        contentAlignment = Alignment.Center) {
                        Text("G", color = Color.White, style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold)
                    }
                    Text(message.agent, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(tr(if (message.mention) S.grokbot_mention else S.grokbot_reply), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (message.text.isNotBlank()) SelectionContainer { Markdown(message.text) }
            }
        }
        Spacer(Modifier.weight(.12f))
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
