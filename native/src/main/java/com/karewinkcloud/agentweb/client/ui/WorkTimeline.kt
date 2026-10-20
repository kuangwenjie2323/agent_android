package com.karewinkcloud.agentweb.client.ui

import android.animation.ValueAnimator
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.karewinkcloud.agentweb.client.R
import com.karewinkcloud.agentweb.client.R.string as S
import com.karewinkcloud.agentweb.client.core.*
import kotlinx.coroutines.delay

/** While a long section runs, only its latest steps are shown; the rest fold into one line. */
private const val LIVE_TAIL = 4
private const val OUTPUT_PREVIEW_LINES = 10
private const val DIFF_PREVIEW_ROWS = 14

/**
 * One run of thinking and tool steps between pieces of the answer, drawn the way Claude does: a summary
 * line ("Thought for 12 s · Ran 3 commands · Edited 1 file +12 −3") that opens into a timeline. While the
 * section is current it starts open, so what is happening right now is visible, but it can still be
 * folded; once the turn has moved past it, it starts folded. File edits show their diff.
 */
@Composable
internal fun WorkSection(steps: List<ChatBlock>, live: Boolean, current: Boolean, key: Int) {
    var open by rememberSaveable(key) { mutableStateOf(current) }
    var earlier by rememberSaveable(key) { mutableStateOf(false) }
    // The section folds away once the turn moves past it (the answer started, or the turn ended).
    LaunchedEffect(current) { open = current; earlier = false }
    val ticking = live && steps.any { it.isRunning() }
    val now by produceState(System.nanoTime() / 1_000_000, ticking) {
        while (ticking) { value = System.nanoTime() / 1_000_000; delay(1000) }
    }
    val state = tr(if (open) S.expanded else S.collapsed)
    // Steps arrive, finish and fold away while the answer streams below: sizes change smoothly, never in jumps.
    Column(Modifier.fillMaxWidth().animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))) {
        if (steps.size == 1 && !current) {
            // A lone step reads as itself rather than as "1 step".
            StepItem(steps[0], live, now, last = true, rail = false, key = key)
            return@Column
        }
        // The running section gets the same fold control; its WorkingIndicator still reports activity.
        SummaryRow(steps, open, live, Modifier.semantics { stateDescription = state }) { open = !open }
        if (open) {
            val hidden = if (current && !earlier && steps.size > LIVE_TAIL) steps.size - LIVE_TAIL else 0
            Column(Modifier.padding(top = if (current) 0.dp else 8.dp)) {
                // "Earlier steps" used to be one-way: once opened, the row disappeared and the
                // whole chain stayed on screen. Show the matching fold control while it is open.
                if (current && earlier && steps.size > LIVE_TAIL) {
                    EarlierRow(steps.size - LIVE_TAIL, expanded = true) { earlier = false }
                }
                if (hidden > 0) EarlierRow(hidden, expanded = false) { earlier = true }
                val shown = steps.drop(hidden)
                shown.forEachIndexed { index, step ->
                    StepItem(step, live, now, last = index == shown.lastIndex, rail = true, key = key * 1000 + hidden + index)
                }
            }
        }
    }
}

private fun ChatBlock.isRunning() = (this is ChatBlock.Tool && (status == StepStatus.RUNNING || task?.running == true)) ||
    (this is ChatBlock.Thought && status == StepStatus.RUNNING)

private fun ChatBlock.duration(now: Long, live: Boolean): Long? = when (this) {
    is ChatBlock.Tool -> durationMs ?: startedAt.takeIf { live && status == StepStatus.RUNNING && it > 0 }?.let { now - it }
    is ChatBlock.Thought -> durationMs ?: startedAt.takeIf { live && status == StepStatus.RUNNING && it > 0 }?.let { now - it }
    else -> null
}?.coerceAtLeast(0)

@Composable
private fun SummaryRow(steps: List<ChatBlock>, open: Boolean, live: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tools = steps.filterIsInstance<ChatBlock.Tool>()
    val kinds = tools.map { stepSummary(it).kind }
    val thinking = steps.filterIsInstance<ChatBlock.Thought>().sumOf { it.durationMs ?: 0L }
    val diffs = tools.flatMap { stepDiffs(it) }
    val failed = tools.count { it.status == StepStatus.ERROR }
    val parts = buildList {
        if (steps.any { it is ChatBlock.Thought }) add(if (thinking >= 1000) tr(S.step_think_done, spanLabel(thinking / 1000)) else tr(S.step_think))
        kinds.count { it == StepKind.COMMAND }.takeIf { it > 0 }?.let { add(countLabel(R.plurals.work_commands, it)) }
        kinds.count { it == StepKind.EDIT || it == StepKind.WRITE }.takeIf { it > 0 }?.let { steps ->
            // Files, not steps: three edits to one file read "Edited 1 file".
            add(countLabel(R.plurals.work_edits, diffs.map { d -> d.path }.filter(String::isNotBlank).distinct().size.takeIf { it > 0 } ?: steps))
        }
        kinds.count { it == StepKind.READ }.takeIf { it > 0 }?.let { add(countLabel(R.plurals.work_reads, it)) }
        kinds.count { it == StepKind.SEARCH || it == StepKind.WEB_SEARCH }.takeIf { it > 0 }?.let { add(countLabel(R.plurals.work_searches, it)) }
        kinds.count { it == StepKind.WEB_FETCH }.takeIf { it > 0 }?.let { add(countLabel(R.plurals.work_pages, it)) }
        kinds.count { it in setOf(StepKind.IMAGE, StepKind.AGENT, StepKind.TODO, StepKind.MCP, StepKind.TASK_OUTPUT,
            StepKind.TASK_STOP, StepKind.OTHER) }
            .takeIf { it > 0 }?.let { add(countLabel(R.plurals.work_other, it)) }
    }
    val lead = kinds.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: StepKind.THINK
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).heightIn(min = 36.dp)
        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        StepIcon(lead, running = false, error = false)
        Spacer(Modifier.width(10.dp))
        Text(parts.take(3).joinToString(" · ") + if (parts.size > 3) " …" else "", Modifier.weight(1f),
            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        val added = diffs.sumOf { it.added }; val removed = diffs.sumOf { it.removed }
        if (added + removed > 0) { Spacer(Modifier.width(8.dp)); DiffStats(added, removed) }
        if (failed > 0) {
            Spacer(Modifier.width(8.dp))
            Text(countLabel(R.plurals.failed_steps, failed), maxLines = 1, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }
        // Folded work still says when something it started runs on in the background.
        val background = if (live) tools.count { it.task?.running == true } else 0
        if (background > 0) {
            Spacer(Modifier.width(8.dp))
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(4.dp))
            Text(countLabel(R.plurals.tasks_running_short, background), maxLines = 1, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(4.dp))
        Chevron(open)
    }
}

@Composable
private fun EarlierRow(hidden: Int, expanded: Boolean, onClick: () -> Unit) {
    val state = tr(if (expanded) S.expanded else S.collapsed)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).heightIn(min = 32.dp)
        .padding(bottom = 6.dp).semantics { stateDescription = state },
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
            Text("⋮", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.width(10.dp))
        Text(tr(if (expanded) S.step_fewer else S.step_earlier, hidden),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Chevron(open: Boolean) {
    Box(Modifier.size(16.dp).graphicsLayer { rotationZ = if (open) 90f else 0f }) {
        Icon(androidx.compose.ui.res.painterResource(R.drawable.aw_chevron), null, Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StepIcon(kind: StepKind, running: Boolean, error: Boolean) {
    val motion = remember { ValueAnimator.areAnimatorsEnabled() }
    Box(Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        when {
            kind == StepKind.THINK -> Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                WorkingSpark(running && motion, WorkingPhase.THINKING)
            }
            running -> CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
            else -> Icon(androidx.compose.ui.res.painterResource(if (error) R.drawable.aw_error else stepIcon(kind)), null,
                Modifier.size(15.dp), tint = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun stepIcon(kind: StepKind) = when (kind) {
    StepKind.COMMAND -> R.drawable.aw_terminal
    StepKind.READ -> R.drawable.aw_file
    StepKind.EDIT, StepKind.WRITE -> R.drawable.aw_edit
    StepKind.SEARCH -> R.drawable.aw_search
    StepKind.WEB_SEARCH, StepKind.WEB_FETCH -> R.drawable.aw_globe
    StepKind.IMAGE -> R.drawable.aw_photos
    StepKind.AGENT -> R.drawable.aw_chat
    StepKind.TODO -> R.drawable.aw_queue
    StepKind.MCP -> R.drawable.aw_plug
    StepKind.TASK_OUTPUT -> R.drawable.aw_terminal
    StepKind.TASK_STOP -> R.drawable.aw_stop
    StepKind.THINK, StepKind.OTHER -> R.drawable.aw_settings
}

@Composable
private fun stepTitle(kind: StepKind, summary: StepSummary?, name: String, background: Boolean) = when (kind) {
    StepKind.THINK -> tr(S.step_think)
    // Claude Code: "Running in the background" instead of the command's usual title.
    StepKind.COMMAND -> tr(if (background) S.step_background else S.step_command)
    StepKind.READ -> tr(S.step_read)
    StepKind.EDIT -> tr(S.step_edit)
    StepKind.WRITE -> tr(S.step_write)
    StepKind.SEARCH -> tr(S.step_search)
    StepKind.WEB_SEARCH -> tr(S.step_web_search)
    StepKind.WEB_FETCH -> tr(S.step_web_fetch)
    StepKind.IMAGE -> tr(S.step_image)
    StepKind.AGENT -> tr(if (background) S.step_background_agent else S.step_agent)
    StepKind.TODO -> tr(S.step_todo)
    StepKind.MCP -> tr(S.step_mcp, summary?.mcpTool.orEmpty())
    StepKind.TASK_OUTPUT -> tr(S.step_task_output)
    StepKind.TASK_STOP -> tr(S.step_task_stop)
    StepKind.OTHER -> name
}

/** One step on the rail: icon, what it did and to what, its time; tap for the command, output or diff. */
@Composable
private fun StepItem(step: ChatBlock, live: Boolean, now: Long, last: Boolean, rail: Boolean, key: Int) {
    val tool = step as? ChatBlock.Tool
    val thought = step as? ChatBlock.Thought
    val summary = remember(tool) { tool?.let(::stepSummary) }
    val kind = summary?.kind ?: StepKind.THINK
    val status = tool?.status ?: thought?.status ?: StepStatus.COMPLETE
    val running = live && status == StepStatus.RUNNING
    val error = status == StepStatus.ERROR
    val diffs = remember(tool) { tool?.let(::stepDiffs).orEmpty() }
    // A file change shows its diff right away; everything else opens on tap.
    var open by rememberSaveable(key) { mutableStateOf(diffs.isNotEmpty()) }
    val duration = step.duration(now, live)
    val inline = kind in setOf(StepKind.READ, StepKind.EDIT, StepKind.WRITE, StepKind.SEARCH, StepKind.WEB_SEARCH,
        StepKind.WEB_FETCH, StepKind.IMAGE)
    val background = remember(tool) { tool?.let(::isBackground) == true }
    val task = tool?.task
    val title = if (kind == StepKind.THINK) when {
        running -> tr(S.step_think_running)
        (duration ?: 0) >= 1000 -> tr(S.step_think_done, spanLabel(duration!! / 1000))
        else -> tr(S.step_think)
    } else stepTitle(kind, summary, tool?.name.orEmpty(), background)
    // A step about a background task names the task rather than its id.
    val target = summary?.taskRef?.let { LocalTaskNames.current[it] } ?: summary?.target
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(Modifier.width(28.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            StepIcon(kind, running, error)
            if (rail && !last) Box(Modifier.padding(vertical = 3.dp).width(1.5.dp).weight(1f)
                .background(MaterialTheme.colorScheme.outlineVariant))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(bottom = if (last) 0.dp else 12.dp)) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { open = !open }.heightIn(min = 28.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (running) ShimmerText(title, MaterialTheme.typography.bodyMedium)
                        else Text(title, maxLines = 1, style = MaterialTheme.typography.bodyMedium,
                            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        if (inline && target != null) {
                            Spacer(Modifier.width(6.dp))
                            Text(target, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (!inline && target != null) Text(target, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // The step returned at once; what it started runs on: say how it is doing.
                    if (task != null) Box(Modifier.padding(top = 2.dp)) { TaskStatusLine(task, live, now) }
                    // While it thinks, its latest thought shows; once done, "Thought for 9 s" is enough (tap to read).
                    if (thought != null && running && !open) ThoughtTicker(thought.content)
                }
                val added = diffs.sumOf { it.added }; val removed = diffs.sumOf { it.removed }
                // An open diff carries its own counts in its header.
                if (added + removed > 0 && !open) { Spacer(Modifier.width(8.dp)); DiffStats(added, removed) }
                if (duration != null && (running || duration >= 2000) && kind != StepKind.THINK && task == null) {
                    Spacer(Modifier.width(8.dp))
                    Text(spanLabel(duration / 1000), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (open) Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    thought != null -> ThoughtText(thought.content)
                    diffs.isNotEmpty() -> diffs.forEach { FileDiffView(it) }
                    tool != null && task != null -> ToolDetail(tool, kind, error, task, live)
                    tool != null -> ToolDetail(tool, kind, error)
                }
            }
        }
    }
}

/**
 * The newest two lines of a thought in progress, like a ticker: a fixed height whatever the text does
 * (a preview that grew and shrank by a line made the whole page jump), newest line at the bottom.
 */
@Composable
private fun ThoughtTicker(content: String) {
    val style = MaterialTheme.typography.bodySmall
    val height = with(LocalDensity.current) { style.lineHeight.toDp() } * 2
    val tail = remember(content) { content.trim().replace(Regex("\\s+"), " ").takeLast(400) }
    Box(Modifier.fillMaxWidth().height(height).clipToBounds(), contentAlignment = Alignment.BottomStart) {
        Text(tail, Modifier.wrapContentHeight(Alignment.Bottom, unbounded = true), style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ThoughtText(content: String) {
    if (content.isBlank()) return
    Row(Modifier.height(IntrinsicSize.Min)) {
        Box(Modifier.width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
        Spacer(Modifier.width(10.dp))
        Text(content.trim(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The command (or other input) and the start of its output; long output opens in full on request. A
 * background step shows its task's own output instead of the "running in background" receipt.
 */
@Composable
private fun ToolDetail(tool: ChatBlock.Tool, kind: StepKind, error: Boolean, task: BackgroundTask? = null, live: Boolean = false) {
    // The full command, address or query; other tools fall back to "name · detail".
    val command = remember(tool) { stepInput(tool) ?: toolCommand(tool) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text((if (kind == StepKind.COMMAND) "$ " else "") + command, fontFamily = CodeFont,
                fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurface)
            if (task == null) tool.result?.trim()?.takeIf { it.isNotEmpty() }?.let { result -> OutputPreview(result, error) }
            // The file a step read opens in full, at the part it read.
            val openFile = LocalOpenFile.current
            val file = remember(tool) { stepFileRef(tool) }
            if (openFile != null && file != null) TextButton({ openFile(file) }, Modifier.heightIn(min = 40.dp),
                contentPadding = PaddingValues(horizontal = 8.dp)) {
                Icon(androidx.compose.ui.res.painterResource(R.drawable.aw_file), null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(tr(S.step_open_file), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
    if (task != null && LocalTaskControls.current != null) TaskOutputView(task.id, task.running && live)
}

@Composable
private fun OutputPreview(result: String, error: Boolean) {
    var all by remember(result) { mutableStateOf(false) }
    val lines = remember(result) { result.lines() }
    val shown = if (all) lines.take(400) else lines.take(OUTPUT_PREVIEW_LINES)
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Text(shown.joinToString("\n"), Modifier.horizontalScroll(rememberScrollState()), fontFamily = CodeFont,
        fontSize = 12.sp, lineHeight = 18.sp, softWrap = false,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    if (lines.size > shown.size) Text(tr(S.step_more_lines, lines.size - shown.size),
        Modifier.clip(RoundedCornerShape(6.dp)).clickable { all = true }.padding(vertical = 2.dp),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
internal fun DiffStats(added: Int, removed: Int) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (added > 0) Text("+$added", style = MaterialTheme.typography.labelMedium, fontFamily = CodeFont,
            color = if (dark) Color(0xff4cd964) else Color(0xff1f8a3b))
        if (removed > 0) Text("−$removed", style = MaterialTheme.typography.labelMedium, fontFamily = CodeFont,
            color = MaterialTheme.colorScheme.error)
    }
}

/** A file's change like Claude Code shows it: file name and +/− counts, numbered red and green lines. */
@Composable
internal fun FileDiffView(diff: FileDiff) {
    var all by remember(diff) { mutableStateOf(false) }
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    val rows = if (all) diff.rows else diff.rows.take(DIFF_PREVIEW_ROWS)
    val numbered = diff.rows.any { it.oldLine != null || it.newLine != null }
    val width = (diff.rows.maxOfOrNull { maxOf(it.oldLine ?: 0, it.newLine ?: 0) } ?: 0).toString().length.coerceAtLeast(2)
    val mono = TextStyle(fontFamily = CodeFont, fontSize = 12.sp, lineHeight = 18.sp)
    val openFile = LocalOpenFile.current?.takeIf { diff.path.isNotBlank() && diff.rows.isNotEmpty() }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth()) {
            // The file's name opens the whole file, at the first line this change added when the diff says where.
            Row(Modifier.fillMaxWidth().then(if (openFile != null) Modifier.clickable {
                    openFile(FileRef(diff.path, diff.rows.firstOrNull { it.kind == DiffKind.ADD }?.newLine?.takeIf { numbered }))
                } else Modifier).heightIn(min = 40.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(fileName(diff.path).ifBlank { tr(S.step_edit) }, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge, fontFamily = CodeFont)
                DiffStats(diff.added, diff.removed)
                if (openFile != null) { Spacer(Modifier.width(8.dp)); OpenFileLabel() }
            }
            if (diff.rows.isEmpty()) {
                Text(tr(S.step_file_removed), Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).width(IntrinsicSize.Max).padding(bottom = 6.dp)) {
                rows.forEach { row ->
                    val (background, sign, ink) = when (row.kind) {
                        DiffKind.ADD -> Triple(if (dark) Color(0xff16341f) else Color(0xffe6f6e9), "+", if (dark) Color(0xff7ee29a) else Color(0xff1f6b34))
                        DiffKind.REMOVE -> Triple(if (dark) Color(0xff3c1618) else Color(0xffffebe9), "−", if (dark) Color(0xffff9c97) else Color(0xffb3261e))
                        DiffKind.HEADER -> Triple(MaterialTheme.colorScheme.surfaceContainerHigh, "", MaterialTheme.colorScheme.onSurfaceVariant)
                        DiffKind.CONTEXT -> Triple(Color.Transparent, " ", MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(Modifier.fillMaxWidth().background(background).padding(horizontal = 8.dp)) {
                        if (numbered && row.kind != DiffKind.HEADER) {
                            Text((row.oldLine?.toString() ?: "").padStart(width), style = mono, color = MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.width(6.dp))
                            Text((row.newLine?.toString() ?: "").padStart(width), style = mono, color = MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(sign, Modifier.width(12.dp), style = mono, color = ink)
                        Text(row.text, style = mono, softWrap = false,
                            color = if (row.kind == DiffKind.CONTEXT) MaterialTheme.colorScheme.onSurface else ink)
                    }
                }
            }
            if (!all && diff.rows.size > rows.size) Text(tr(S.step_more_lines, diff.rows.size - rows.size),
                Modifier.padding(start = 12.dp, bottom = 10.dp).clip(RoundedCornerShape(6.dp)).clickable { all = true },
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** A running step's title with a soft light sweeping across it (still when motion is off). */
@Composable
private fun ShimmerText(text: String, style: TextStyle) {
    val base = MaterialTheme.colorScheme.onSurfaceVariant
    val light = MaterialTheme.colorScheme.onSurface
    if (!remember { ValueAnimator.areAnimatorsEnabled() }) {
        Text(text, maxLines = 1, style = style, color = base); return
    }
    val shift by rememberInfiniteTransition(label = "shimmer").animateFloat(-1f, 2f,
        infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "shimmer shift")
    var width by remember { mutableFloatStateOf(1f) }
    Text(text, Modifier.onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }, maxLines = 1,
        style = style.copy(brush = Brush.linearGradient(listOf(base, light, base),
            start = androidx.compose.ui.geometry.Offset(width * (shift - .5f), 0f),
            end = androidx.compose.ui.geometry.Offset(width * (shift + .5f), 0f))))
}
