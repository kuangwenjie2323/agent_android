package com.karewinkcloud.agentweb.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.karewinkcloud.agentweb.client.R
import com.karewinkcloud.agentweb.client.R.string as S
import com.karewinkcloud.agentweb.client.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/*
 * Background tasks, the way Claude Code shows them: the step that started one says it runs in the
 * background and how it is doing, its end is announced in the turn, and a "1 background task" pill by the
 * composer opens the list, where each task's output can be read and the task stopped.
 */

/** What the chat screen lends steps, notices and the pill: a task's output, stopping it, opening the list. */
internal class TaskControls(
    val output: suspend (String) -> TaskOutput?,
    val stop: (String) -> Unit,
    /** Opens the task list, on one task's output when an id is given. */
    val open: (String?) -> Unit,
)

internal val LocalTaskControls = staticCompositionLocalOf<TaskControls?> { null }

/** Names of the current message's background tasks, for steps and notices that refer to a task by id. */
internal val LocalTaskNames = staticCompositionLocalOf<Map<String, String>> { emptyMap() }

private const val OUTPUT_REFRESH_MS = 2000L

/**
 * How a task is doing, in a few words: "Running · 1 min 5 s", "Done · 45 s", "Failed · exit code 1".
 * [now] is a ticking clock that only makes this redraw; the running time is reckoned on the server's clock.
 */
@Composable
internal fun taskStatusLabel(task: BackgroundTask, @Suppress("UNUSED_PARAMETER") now: Long, live: Boolean): String {
    val failedCode = task.exitCode?.takeIf { it != 0 }
    return when {
        task.running && live && task.startedAt > 0 ->
            tr(S.task_running_for, spanLabel(((System.currentTimeMillis() - task.startedAt) / 1000).coerceAtLeast(0)))
        task.running -> tr(S.task_running)
        task.status == "stopped" -> tr(S.task_stopped)
        failedCode != null -> tr(S.task_failed_code, failedCode)
        task.status == "failed" -> tr(S.task_failed)
        else -> task.durationMs?.takeIf { it >= 1000 }?.let { tr(S.task_done_in, spanLabel(it / 1000)) } ?: tr(S.task_done)
    }
}

private fun BackgroundTask.failed() = status == "failed" || (exitCode ?: 0) != 0

/** A small status mark: a spinner while it runs, then a check, a red cross or a stop square. */
@Composable
internal fun TaskStatusMark(task: BackgroundTask, live: Boolean, size: Dp = 14.dp) {
    when {
        task.running && live -> CircularProgressIndicator(Modifier.size(size), strokeWidth = 1.75.dp, color = MaterialTheme.colorScheme.primary)
        else -> Icon(painterResource(when {
            task.running || task.status == "stopped" -> R.drawable.aw_stop
            task.failed() -> R.drawable.aw_error
            else -> R.drawable.aw_check
        }), null, Modifier.size(size), tint = if (task.failed()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The status line a background step carries after its title. */
@Composable
internal fun TaskStatusLine(task: BackgroundTask, live: Boolean, now: Long) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TaskStatusMark(task, live, 12.dp)
        Text(taskStatusLabel(task, now, live), maxLines = 1, style = MaterialTheme.typography.bodySmall,
            color = if (task.failed()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * A task's output as its terminal shows it, read from the server: refreshed every two seconds while the task
 * runs, kept scrolled to the newest line.
 */
@Composable
internal fun TaskOutputView(taskId: String, running: Boolean, modifier: Modifier = Modifier, maxHeight: Dp = 240.dp) {
    val controls = LocalTaskControls.current ?: return
    var output by remember(taskId) { mutableStateOf<TaskOutput?>(null) }
    // null while loading; then "ready", "gone" (the server no longer has it) or "failed" (could not ask).
    var outcome by remember(taskId) { mutableStateOf<String?>(null) }
    LaunchedEffect(taskId, running) {
        while (true) {
            try {
                val latest = controls.output(taskId)
                if (latest == null) outcome = "gone" else { output = latest; outcome = "ready" }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (output == null) outcome = "failed" }
            if (!running) break
            delay(OUTPUT_REFRESH_MS)
        }
    }
    val scroll = rememberScrollState()
    LaunchedEffect(output?.text) { if (!scroll.isScrollInProgress) scroll.scrollTo(scroll.maxValue) }
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val text = output?.text?.trimEnd().orEmpty()
            when {
                outcome == null -> Text(tr(S.task_output_loading), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                outcome == "gone" && text.isEmpty() -> Text(tr(S.task_output_gone), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                outcome == "failed" -> Text(tr(S.task_output_failed), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                text.isEmpty() -> Text(tr(if (running) S.task_output_waiting else S.task_output_empty), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    if (output?.truncated == true) Text(tr(S.task_output_truncated), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SelectionContainer {
                        Text(text, Modifier.heightIn(max = maxHeight).verticalScroll(scroll), fontFamily = CodeFont,
                            fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

/** Where a background task ended in the turn: "Background task “Run tests” finished · exit code 1". */
@Composable
internal fun TaskNoticeRow(notice: ChatBlock.TaskNotice) {
    val controls = LocalTaskControls.current
    val name = notice.description.ifBlank { LocalTaskNames.current[notice.taskId].orEmpty() }.ifBlank { tr(S.task_unnamed) }
    val task = BackgroundTask(notice.taskId, notice.status, exitCode = notice.exitCode)
    val label = when {
        notice.status == "stopped" -> tr(S.task_notice_stopped, name)
        task.failed() -> tr(S.task_notice_failed, name) + (notice.exitCode?.takeIf { it != 0 }?.let { " · " + tr(S.task_exit_code, it) } ?: "")
        else -> tr(S.task_notice_done, name)
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
        .then(if (controls != null) Modifier.clickable(role = Role.Button) { controls.open(notice.taskId) } else Modifier)
        .heightIn(min = 32.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            TaskStatusMark(task, live = false)
        }
        Text(label, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
            color = if (task.failed()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "● 2 background tasks ›" above the message box while a turn's tasks run, like Claude Code's footer. */
@Composable
internal fun BackgroundTasksPill(running: Int, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.heightIn(min = 36.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.75.dp, color = MaterialTheme.colorScheme.primary)
            Text(countLabel(R.plurals.tasks_running, running), style = MaterialTheme.typography.labelLarge)
            Icon(painterResource(R.drawable.aw_chevron), null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The task list (Apple grouped style): running tasks first with a Stop button, then the ended ones; a task
 * opens on its command and live output. [selected] opens on that task directly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BackgroundTasksSheet(tasks: List<Pair<ChatBlock.Tool, BackgroundTask>>, live: Boolean, selected: String?, onClose: () -> Unit) {
    val controls = LocalTaskControls.current
    var open by remember { mutableStateOf(selected?.takeIf { id -> tasks.any { it.second.id == id } }) }
    val ticking = live && tasks.any { it.second.running }
    val now by produceState(System.nanoTime() / 1_000_000, ticking) {
        while (ticking) { value = System.nanoTime() / 1_000_000; delay(1000) }
    }
    AppModalBottomSheet(onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = groupedPageColor()) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.88f)) {
            val current = tasks.find { it.second.id == open }
            if (current == null) {
                SheetHeader(tr(S.tasks_title), onClose)
                val (running, ended) = tasks.partition { it.second.running && live }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp)) {
                    if (tasks.isEmpty()) item { Text(tr(S.tasks_none), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (running.isNotEmpty()) item("running") {
                        GroupedSection(tr(S.tasks_running_group)) {
                            running.forEachIndexed { index, (tool, task) ->
                                if (index > 0) RowDivider(56.dp)
                                TaskRow(tool, task, live, now, onStop = controls?.let { { it.stop(task.id) } }) { open = task.id }
                            }
                        }
                    }
                    if (ended.isNotEmpty()) item("ended") {
                        GroupedSection(tr(S.tasks_ended_group)) {
                            ended.forEachIndexed { index, (tool, task) ->
                                if (index > 0) RowDivider(56.dp)
                                TaskRow(tool, task, live, now, onStop = null) { open = task.id }
                            }
                        }
                    }
                }
            } else {
                val (tool, task) = current
                DetailHeader(taskName(tool, task), onBack = { open = null }, onDone = onClose)
                Column(Modifier.weight(1f).padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                    GroupedSection(null) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { TaskStatusLine(task, live, now) }
                            if (task.running && live && controls != null) TextButton({ controls.stop(task.id) }, Modifier.heightIn(min = 40.dp)) {
                                Text(tr(S.task_stop), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        stepInput(tool)?.let { command ->
                            RowDivider()
                            Text((if (task.agent) "" else "$ ") + command, Modifier.fillMaxWidth().heightIn(max = 120.dp)
                                .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
                                fontFamily = CodeFont, fontSize = 12.sp, lineHeight = 18.sp,
                                color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    GroupLabel(tr(S.task_output))
                    TaskOutputView(task.id, task.running && live, Modifier.weight(1f, fill = false), maxHeight = 4000.dp)
                }
            }
        }
    }
}

@Composable
private fun TaskRow(tool: ChatBlock.Tool, task: BackgroundTask, live: Boolean, now: Long, onStop: (() -> Unit)?, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onOpen)
        .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Icon(painterResource(if (task.agent) R.drawable.aw_chat else R.drawable.aw_terminal), null, Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f)) {
            Text(taskName(tool, task), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface)
            TaskStatusLine(task, live, now)
        }
        if (onStop != null) TextButton(onStop, Modifier.heightIn(min = 40.dp)) {
            Text(tr(S.task_stop), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
        }
        Icon(painterResource(R.drawable.aw_chevron), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
    }
}

/** A pushed page's bar: "‹ Back" on the left, the title centred, a red "Done" on the right. */
@Composable
private fun DetailHeader(title: String, onBack: () -> Unit, onDone: () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 4.dp)) {
        TextButton(onBack, Modifier.align(Alignment.CenterStart).heightIn(min = 48.dp)) {
            Icon(painterResource(R.drawable.aw_back), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Text(tr(S.tasks_title), color = MaterialTheme.colorScheme.primary, fontSize = 17.sp)
        }
        Text(title, Modifier.align(Alignment.Center).padding(horizontal = 112.dp).semantics { heading() }, maxLines = 1,
            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        TextButton(onDone, Modifier.align(Alignment.CenterEnd).heightIn(min = 48.dp)) {
            Text(tr(S.done), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
    }
}
