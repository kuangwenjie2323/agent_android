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
import androidx.compose.ui.graphics.lerp
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

enum class WorkingPhase { THINKING, TOOL, WRITING, WAITING, RECONNECTING, CHOOSING }
data class WorkingStatus(val phase: WorkingPhase, val toolName: String? = null)

/** Terminal truth wins over the display pacer's remaining text. */
fun workingStatus(turn: TurnState, connection: Connection?): WorkingStatus? {
    if (turn.done) return null
    if (connection == Connection.RECONNECTING) return WorkingStatus(WorkingPhase.RECONNECTING)
    if (connection == Connection.CONNECTING) return WorkingStatus(WorkingPhase.WAITING)
    // The agent asked a question and waits for the user's pick.
    if (turn.blocks.any { it is ChatBlock.Question && it.answer == null }) return WorkingStatus(WorkingPhase.CHOOSING)
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
    usage?.promptTokens?.takeIf { it >= 0 }?.let { add(String.format(Locale.ROOT, labels.input, compactTokenCount(it))) }
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
        WorkingPhase.CHOOSING -> tr(S.turn_choosing)
    }
    // Geometry depends only on typography/font scale, never on the current phase or timer text.
    val density = LocalDensity.current
    val lineHeight = MaterialTheme.typography.bodySmall.lineHeight
    val slotHeight = with(density) { maxOf(32.dp, lineHeight.toDp() + 8.dp) }
    val timerWidth = 64.dp * density.fontScale.coerceAtLeast(1f)
    Row(Modifier.fillMaxWidth().height(slotHeight), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WorkingSpark(animatorsEnabled && motionScale?.scaleFactor != 0f, status.phase)
        Text(label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(elapsedLabel(now - (turn.startedAt ?: origin)), Modifier.width(timerWidth), textAlign = TextAlign.End,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** How the spark moves in each phase; values blend when the phase changes. */
private data class SparkStyle(val spin: Float, val breathDepth: Float, val breathSeconds: Float, val waveDepth: Float,
    val gear: Float, val chase: Float, val blink: Float, val alphaBase: Float)

private fun sparkStyle(phase: WorkingPhase) = when (phase) {
    // Pondering: slow turn, rays rise and fall one after another, deep breath.
    WorkingPhase.THINKING -> SparkStyle(30f, .26f, 1.6f, .28f, 0f, 0f, 0f, .55f)
    // Running a tool: quick turn, alternating short and long rays like a gear.
    WorkingPhase.TOOL -> SparkStyle(150f, .08f, .9f, 0f, .32f, 0f, 0f, .75f)
    // Writing the answer: a bright point runs round the rays, like typing.
    WorkingPhase.WRITING -> SparkStyle(40f, .06f, 1.2f, .1f, 0f, 1f, 0f, .3f)
    // Waiting for the provider: nearly still, dim, slow breath.
    WorkingPhase.WAITING -> SparkStyle(8f, .34f, 2.6f, 0f, 0f, 0f, 0f, .3f)
    // Reconnecting: stops turning and blinks.
    WorkingPhase.RECONNECTING -> SparkStyle(0f, .1f, 2f, 0f, 0f, 0f, 1f, .2f)
    // Waiting for the user's choice: still, with a calm, bright breath that invites a tap.
    WorkingPhase.CHOOSING -> SparkStyle(0f, .3f, 1.4f, 0f, 0f, 0f, 0f, .5f)
}

@Composable
internal fun WorkingSpark(animate: Boolean, phase: WorkingPhase = WorkingPhase.THINKING) {
    val target = sparkStyle(phase)
    val blend = tween<Float>(450, easing = FastOutSlowInEasing)
    val spin by animateFloatAsState(target.spin, blend, label = "spin")
    val breathDepth by animateFloatAsState(target.breathDepth, blend, label = "breath depth")
    val breathSeconds by animateFloatAsState(target.breathSeconds, blend, label = "breath period")
    val waveDepth by animateFloatAsState(target.waveDepth, blend, label = "wave")
    val gear by animateFloatAsState(target.gear, blend, label = "gear")
    val chase by animateFloatAsState(target.chase, blend, label = "chase")
    val blink by animateFloatAsState(target.blink, blend, label = "blink")
    val alphaBase by animateFloatAsState(target.alphaBase, blend, label = "alpha")
    // Time and turn are read only inside the draw lambda, so frames redraw without recomposing.
    val seconds = remember { mutableFloatStateOf(0f) }
    val turn = remember { mutableFloatStateOf(0f) }
    if (animate) LaunchedEffect(Unit) {
        var last = -1L
        while (true) withFrameMillis { frame ->
            if (last >= 0) {
                val dt = (frame - last) / 1000f
                seconds.floatValue += dt
                turn.floatValue = (turn.floatValue + spin * dt) % 360f
            }
            last = frame
        }
    }
    val accent = workingAccent
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(20.dp).clearAndSetSemantics { }) {
        val t = seconds.floatValue
        val tau = (2 * Math.PI).toFloat()
        val breath = if (animate) .5f + .5f * sin(tau * t / breathSeconds) else 1f
        val blinkLevel = if (animate) (.5f + .5f * sin(tau * t / 1.1f)).let { it * it } else 1f
        val color = lerp(accent, muted, blink)
        rotate(turn.floatValue) {
            repeat(8) { ray ->
                val angle = ray * Math.PI / 4
                val wave = if (animate) .5f + .5f * sin(tau * (t / 1.8f - ray / 8f)) else 1f
                val chaseLevel = if (animate) maxOf(0f, cos(tau * (t / .9f - ray / 8f))).let { it * it * it * it } else 0f
                val length = (1f - breathDepth * (1f - breath) - waveDepth * (1f - wave) - (if (ray % 2 == 0) gear else 0f))
                    .coerceIn(.35f, 1f)
                val lit = (1f - chase) * breath + chase * chaseLevel
                val alpha = (alphaBase + (1f - alphaBase) * lit) * (1f - blink * (1f - blinkLevel))
                val inner = size.minDimension * .2f
                val outer = inner + size.minDimension * .24f * length
                drawLine(color.copy(alpha = alpha.coerceIn(.08f, 1f)),
                    Offset(center.x + cos(angle).toFloat() * inner, center.y + sin(angle).toFloat() * inner),
                    Offset(center.x + cos(angle).toFloat() * outer, center.y + sin(angle).toFloat() * outer),
                    strokeWidth = 1.7.dp.toPx(), cap = StrokeCap.Round)
            }
        }
    }
}
