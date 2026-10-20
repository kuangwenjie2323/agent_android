package com.karewinkcloud.agentweb.client.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.karewinkcloud.agentweb.client.R.string as S
import com.karewinkcloud.agentweb.client.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * What the agents remember, as Claude lets you see and manage its memory: the shared notes every chat's agent
 * reads at the start (MEMORY.md and its files), and the nightly tidy that merges and prunes them.
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MemorySheet(vm: AgentViewModel, onClose: () -> Unit) {
    var revision by remember { mutableIntStateOf(0) }
    var overview by remember { mutableStateOf<MemoryOverview?>(null) }
    var failed by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<MemoryEntry?>(null) }
    var text by remember { mutableStateOf<String?>(null) }
    var showSummary by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(revision) {
        try { overview = vm.memory(); failed = false }
        catch (e: CancellationException) { throw e } catch (_: Exception) { failed = true }
        // A tidy that runs is watched until it ends.
        while (overview?.running == true) { delay(5000); runCatching { overview = vm.memory() } }
    }
    LaunchedEffect(open) {
        text = null
        open?.let { entry -> text = try { vm.memoryFile(entry.file) } catch (e: CancellationException) { throw e } catch (_: Exception) { "" } }
    }
    AppModalBottomSheet(onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = groupedPageColor()) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f)) {
            val entry = open
            if (entry != null) {
                SheetHeader(entry.title) { open = null }
                Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    val shown = text
                    if (shown == null) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    else SelectionContainer { Markdown(markdownForPreview(shown)) }
                }
                return@Column
            }
            SheetHeader(tr(S.memory_title), onClose)
            val data = overview
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)) {
                item("intro") {
                    Text(tr(S.memory_intro), Modifier.padding(horizontal = 4.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (failed) item("failed") { Text(tr(S.memory_failed), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
                if (data == null && !failed) item("loading") { LoadingState(tr(S.loading)) }
                if (data != null) item("pass") {
                    Surface(color = groupedCardColor(), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            val pass = data.lastPass
                            Text(when {
                                data.running -> tr(S.memory_running)
                                pass == null -> tr(S.memory_never)
                                else -> tr(S.memory_last_pass, conversationTime(pass.at), pass.changed.size, pass.removed.size)
                            }, style = MaterialTheme.typography.bodyMedium)
                            if (pass != null && pass.summary.isNotBlank()) {
                                Text(tr(if (showSummary) S.memory_hide_summary else S.memory_show_summary),
                                    Modifier.clickable(role = Role.Button) { showSummary = !showSummary }.padding(vertical = 4.dp),
                                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                if (showSummary) SelectionContainer { Markdown(pass.summary) }
                            }
                            TextButton({ scope.launch { runCatching { vm.consolidateMemory() }; delay(1500); revision++ } },
                                enabled = !data.running, modifier = Modifier.heightIn(min = 44.dp)) { Text(tr(S.memory_tidy_now)) }
                        }
                    }
                }
                val rows = data?.entries.orEmpty()
                itemsIndexed(rows, key = { _, it -> it.file }) { index, item ->
                    val first = index == 0; val last = index == rows.lastIndex
                    Surface(color = groupedCardColor(), shape = RoundedCornerShape(if (first) 12.dp else 0.dp, if (first) 12.dp else 0.dp,
                        if (last) 12.dp else 0.dp, if (last) 12.dp else 0.dp)) {
                        Column {
                            if (!first) RowDivider(16.dp)
                            Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { open = item }.heightIn(min = 52.dp)
                                .padding(horizontal = 16.dp, vertical = 10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(item.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                    item.type?.let { kind ->
                                        Text(memoryKind(kind), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                if (item.hook.isNotBlank()) Text(item.hook, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun memoryKind(kind: String) = when (kind) {
    "user" -> tr(S.memory_kind_user)
    "feedback" -> tr(S.memory_kind_feedback)
    "project" -> tr(S.memory_kind_project)
    "reference" -> tr(S.memory_kind_reference)
    else -> kind
}
