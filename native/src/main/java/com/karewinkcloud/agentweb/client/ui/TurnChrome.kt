package com.karewinkcloud.agentweb.client.ui

import android.animation.ValueAnimator
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.karewinkcloud.agentweb.client.core.*
import com.karewinkcloud.agentweb.client.data.Connection
import com.karewinkcloud.agentweb.client.R.string as S
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

enum class WorkingPhase { THINKING, TOOL, WRITING, WAITING, RECONNECTING }
data class WorkingStatus(val phase: WorkingPhase, val toolName: String? = null)

/** Terminal truth wins over the display pacer's remaining text. */
fun workingStatus(turn: TurnState, connection: Connection?): WorkingStatus? {
    if (turn.done) return null
    if (connection == Connection.RECONNECTING) return WorkingStatus(WorkingPhase.RECONNECTING)
    if (connection == Connection.CONNECTING) return WorkingStatus(WorkingPhase.WAITING)
    turn.blocks.filterIsInstance<ChatBlock.Tool>().lastOrNull { it.status == StepStatus.RUNNING }?.let {
        return WorkingStatus(WorkingPhase.TOOL, shortToolName(it.name))
    }
    return WorkingStatus(when (val last = turn.blocks.lastOrNull { it is ChatBlock.Thought || it is ChatBlock.Text || it is ChatBlock.Tool }) {
        is ChatBlock.Thought -> if (last.status == StepStatus.RUNNING) WorkingPhase.THINKING else WorkingPhase.WAITING
        is ChatBlock.Text -> if (last.content.isNotBlank()) WorkingPhase.WRITING else WorkingPhase.WAITING
        // After a finished tool the model is reasoning over its result (Codex does not stream that).
        is ChatBlock.Tool -> WorkingPhase.THINKING
        else -> WorkingPhase.WAITING
    })
}

internal fun shortToolName(name: String): String = name.substringAfterLast("__").substringAfterLast('/').substringAfterLast('.')
    .trim().take(48)

fun elapsedLabel(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return if (seconds < 60) "${seconds}s" else "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

fun compactTokenCount(count: Long): String = if (count < 1000) count.toString() else
    java.math.BigDecimal.valueOf(count).divide(java.math.BigDecimal(1000), 1, java.math.RoundingMode.HALF_UP)
        .stripTrailingZeros().toPlainString() + "k"

data class FooterLabels(val input: String, val output: String, val steps: String)

/** Templates come from Android resources; the formatting is also testable without Android. */
fun turnFooter(usage: TurnUsage?, traceDurationMs: Long?, steps: Int?, labels: FooterLabels): String = buildList {
    (usage?.durationMs ?: traceDurationMs)?.takeIf { it >= 0 }?.let { add(elapsedLabel(it)) }
    usage?.inputTokens?.takeIf { it >= 0 }?.let { add(String.format(Locale.ROOT, labels.input, compactTokenCount(it))) }
    usage?.outputTokens?.takeIf { it >= 0 }?.let { add(String.format(Locale.ROOT, labels.output, compactTokenCount(it))) }
    steps?.takeIf { it > 0 }?.let { add(String.format(Locale.ROOT, labels.steps, it)) }
}.joinToString(" · ")

fun traceDurationMs(blocks: List<ChatBlock>): Long? {
    val timings = blocks.mapNotNull { when (it) {
        is ChatBlock.Tool -> it.startedAt.takeIf { start -> start > 0 }?.let { start -> start to it.durationMs }
        is ChatBlock.Thought -> it.startedAt.takeIf { start -> start > 0 }?.let { start -> start to it.durationMs }
        else -> null
    } }
    if (timings.isNotEmpty() && timings.all { it.second != null }) {
        return (timings.maxOf { it.first + it.second!!.coerceAtLeast(0) } - timings.minOf { it.first }).coerceAtLeast(0)
    }
    val historyDurations = blocks.filterIsInstance<ChatBlock.Thought>().mapNotNull { it.durationMs?.takeIf { duration -> duration >= 0 } }
    return historyDurations.takeIf { it.isNotEmpty() }?.sum()
}

@Composable
internal fun TurnFooter(message: ChatMessage) {
    val steps = message.blocks.count { it is ChatBlock.Tool || it is ChatBlock.Thought }
    val footer = turnFooter(message.usage, traceDurationMs(message.blocks), steps,
        FooterLabels(tr(S.turn_input), tr(S.turn_output), tr(if (steps == 1) S.turn_one_step else S.turn_steps)))
    if (footer.isNotEmpty()) Text(footer, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun WorkingIndicator(turn: TurnState, connection: Connection?) {
    val status = workingStatus(turn, connection) ?: return
    val origin = remember(turn.runId) { turn.startedAt ?: System.nanoTime() / 1_000_000 }
    val motionScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]
    var now by remember(turn.runId) { mutableLongStateOf(System.nanoTime() / 1_000_000) }
    var animatorsEnabled by remember { mutableStateOf(ValueAnimator.areAnimatorsEnabled()) }
    LaunchedEffect(turn.runId) {
        while (true) {
            now = System.nanoTime() / 1_000_000
            animatorsEnabled = ValueAnimator.areAnimatorsEnabled()
            delay(1000)
        }
    }
    val label = when (status.phase) {
        WorkingPhase.THINKING -> tr(S.turn_thinking)
        WorkingPhase.TOOL -> tr(S.turn_tool, status.toolName?.takeIf { it.isNotBlank() } ?: tr(S.turn_tool_fallback))
        WorkingPhase.WRITING -> tr(S.turn_writing)
        WorkingPhase.WAITING -> tr(S.turn_waiting)
        WorkingPhase.RECONNECTING -> tr(S.turn_reconnecting)
    }
    // Geometry depends only on typography/font scale, never on the current phase or timer text.
    val density = LocalDensity.current
    val lineHeight = MaterialTheme.typography.bodySmall.lineHeight
    val slotHeight = with(density) { maxOf(32.dp, lineHeight.toDp() + 8.dp) }
    val timerWidth = 64.dp * density.fontScale.coerceAtLeast(1f)
    Row(Modifier.fillMaxWidth().height(slotHeight), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WorkingSpark(animatorsEnabled && motionScale?.scaleFactor != 0f)
        Text(label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(elapsedLabel(now - (turn.startedAt ?: origin)), Modifier.width(timerWidth), textAlign = TextAlign.End,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WorkingSpark(animate: Boolean) {
    // Animated values are read only inside the draw lambda, so frames redraw without recomposing.
    var rotation: State<Float> = remember { mutableFloatStateOf(0f) }
    var breathing: State<Float> = remember { mutableFloatStateOf(1f) }
    if (animate) {
        val transition = rememberInfiniteTransition(label = "working spark")
        rotation = transition.animateFloat(0f, 360f,
            infiniteRepeatable(tween(9600, easing = LinearEasing)), label = "rotation")
        breathing = transition.animateFloat(.72f, 1f,
            infiniteRepeatable(tween(600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathing")
    }
    val accent = workingAccent
    Canvas(Modifier.size(20.dp).clearAndSetSemantics { }) {
        val breath = breathing.value
        rotate(rotation.value) {
            repeat(8) { ray ->
                val angle = ray * Math.PI / 4
                val inner = size.minDimension * .21f * breath
                val outer = size.minDimension * .44f * breath
                drawLine(accent.copy(alpha = .55f + .45f * breath),
                    Offset(center.x + cos(angle).toFloat() * inner, center.y + sin(angle).toFloat() * inner),
                    Offset(center.x + cos(angle).toFloat() * outer, center.y + sin(angle).toFloat() * outer),
                    strokeWidth = 1.7.dp.toPx(), cap = StrokeCap.Round)
            }
        }
    }
}
