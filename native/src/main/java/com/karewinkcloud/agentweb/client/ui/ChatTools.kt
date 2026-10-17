package com.karewinkcloud.agentweb.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.karewinkcloud.agentweb.client.R
import com.karewinkcloud.agentweb.client.R.string as S
import com.karewinkcloud.agentweb.client.core.*

/*
 * Two tools from Claude's desktop app, made for the phone: the chapters list of a long session (here: every
 * question the user asked, tap to jump there) and side chats (ask something about the chat without steering
 * or interrupting its agent; the answer is not added to the chat).
 */

/** The user's questions in this chat, newest last; tapping one scrolls the chat to it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OutlineSheet(messages: List<ChatMessage>, moreAbove: Boolean, onLoadOlder: () -> Unit, onJump: (String) -> Unit,
    onClose: () -> Unit) {
    val asked = remember(messages) { messages.filter { it.role == "user" && grokBotMessage(it.text) == null } }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = asked.lastIndex.coerceAtLeast(0))
    AppModalBottomSheet(onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = groupedPageColor()) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.8f)) {
            SheetHeader(tr(S.outline_title), onClose)
            LazyColumn(Modifier.weight(1f), state = state, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                if (moreAbove) item("older") {
                    TextButton(onLoadOlder, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(tr(S.outline_load_older)) }
                }
                if (asked.isEmpty()) item("none") {
                    Text(tr(S.outline_empty), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                itemsIndexed(asked, key = { _, message -> message.id }) { index, message ->
                    val first = index == 0; val last = index == asked.lastIndex
                    Surface(color = groupedCardColor(), shape = RoundedCornerShape(if (first) 12.dp else 0.dp, if (first) 12.dp else 0.dp,
                        if (last) 12.dp else 0.dp, if (last) 12.dp else 0.dp)) {
                        Column {
                            if (!first) RowDivider(52.dp)
                            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button) { onJump(message.id) }
                                .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("${index + 1}", Modifier.widthIn(min = 24.dp), style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(message.text.lineSequence().map(String::trim).firstOrNull { it.isNotEmpty() }
                                    ?: tr(S.outline_picture), Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Ask about the chat without touching it: answered from its transcript, the running turn included. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SideChatSheet(questions: List<SideQuestion>, onAsk: (String) -> Unit, onRetry: (SideQuestion) -> Unit, onClose: () -> Unit) {
    var draft by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(questions.size, questions.lastOrNull()?.answer) { if (questions.isNotEmpty()) list.animateScrollToItem(questions.lastIndex + 1) }
    val busy = questions.any { it.answer == null && !it.failed }
    AppModalBottomSheet(onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = groupedPageColor()) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.85f).imePadding()) {
            SheetHeader(tr(S.side_title), onClose)
            LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item("hint") {
                    Text(tr(S.side_hint), Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                itemsIndexed(questions) { _, item ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp),
                                modifier = Modifier.widthIn(max = 300.dp)) {
                                Text(item.question, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        when {
                            item.answer != null -> Surface(color = groupedCardColor(), shape = RoundedCornerShape(14.dp)) {
                                Box(Modifier.fillMaxWidth().padding(14.dp)) { SelectionContainer { Markdown(item.answer) } }
                            }
                            item.failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(tr(S.side_failed), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error)
                                TextButton({ onRetry(item) }, Modifier.heightIn(min = 40.dp)) { Text(tr(S.retry)) }
                            }
                            else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.75.dp, color = MaterialTheme.colorScheme.primary)
                                Text(tr(S.side_thinking), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            Surface(color = groupedCardColor(), shape = RoundedCornerShape(22.dp), modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextField(draft, { draft = it }, Modifier.weight(1f).heightIn(min = 48.dp, max = 120.dp), maxLines = 4,
                        placeholder = { Text(tr(S.side_placeholder)) },
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
                    FilledIconButton({ onAsk(draft); draft = "" }, Modifier.size(40.dp), enabled = draft.isNotBlank() && !busy) {
                        AppIcon(R.drawable.aw_send, tr(S.send))
                    }
                }
            }
        }
    }
}

/** "Waiting for you" / "Running" / a dot for a reply the user has not read, at the end of a chat row. */
@Composable
internal fun ConversationStatus(conversation: Conversation, unread: Boolean, time: String?) {
    when {
        conversation.waitingInput -> Text("● ${tr(S.waiting_for_you)}", style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold, color = Color(0xffff9f0a))
        conversation.running -> Text("● ${tr(S.running)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (time != null) Text(time, style = MaterialTheme.typography.labelSmall,
                color = if (unread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            if (unread) Box(Modifier.size(9.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        }
    }
}
