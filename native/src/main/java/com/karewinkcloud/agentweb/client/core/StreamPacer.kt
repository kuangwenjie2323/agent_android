package com.karewinkcloud.agentweb.client.core

import kotlin.math.max

/** Presentation only. Server sequence, errors and controls never wait for text reveal. */
class StreamPacer {
    private data class Arrival(val end: Int, val at: Long)
    private val arrivals = ArrayDeque<Arrival>()
    private var target = 0
    private var visible = 0
    private var lastFrame: Long? = null
    private var credit = 0.0
    val pending get() = visible < target

    fun offer(length: Int, now: Long) {
        if (length < target) { target = 0; visible = 0; arrivals.clear(); credit = 0.0 }
        if (length > target) arrivals.addLast(Arrival(length, now))
        target = length
    }

    fun frame(now: Long): Int {
        val elapsed = (now - (lastFrame ?: (now - 16))).coerceIn(0, 100)
        lastFrame = now
        // Accelerate on bursts; an age limit also bounds sustained-stream latency.
        credit += max(80.0, (target - visible) / .18) * elapsed / 1000.0
        val step = credit.toInt()
        credit -= step
        visible = (visible + step).coerceAtMost(target)
        while (arrivals.isNotEmpty() && (arrivals.first().end <= visible || now - arrivals.first().at >= 850)) {
            visible = max(visible, arrivals.removeFirst().end)
        }
        if (!pending) credit = 0.0
        return visible
    }

    fun present(turn: TurnState, now: Long): TurnState {
        var remaining = frame(now)
        return turn.copy(revealing = pending, blocks = turn.blocks.map { block ->
            if (block !is ChatBlock.Text) block else {
                var count = remaining.coerceIn(0, block.content.length)
                // Never split a UTF-16 surrogate pair between frames.
                if (count in 1 until block.content.length && block.content[count - 1].isHighSurrogate() && block.content[count].isLowSurrogate()) count--
                remaining = (remaining - block.content.length).coerceAtLeast(0)
                if (count == block.content.length) block else block.copy(content = block.content.take(count))
            }
        })
    }
}
