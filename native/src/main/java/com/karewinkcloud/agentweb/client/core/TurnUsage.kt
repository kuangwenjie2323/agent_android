package com.karewinkcloud.agentweb.client.core

import kotlinx.serialization.json.*

/** Missing counters stay unknown; zero is a valid, known counter. */
data class TurnUsage(
    val inputTokens: Long? = null, val outputTokens: Long? = null,
    val cacheReadInputTokens: Long? = null, val reasoningTokens: Long? = null,
    val durationMs: Long? = null,
) {
    fun merge(newer: TurnUsage?) = if (newer == null) this else TurnUsage(
        newer.inputTokens ?: inputTokens, newer.outputTokens ?: outputTokens,
        newer.cacheReadInputTokens ?: cacheReadInputTokens, newer.reasoningTokens ?: reasoningTokens,
        newer.durationMs ?: durationMs,
    )
    companion object {
        fun from(j: JsonObject?): TurnUsage? {
            if (j == null) return null
            fun counter(key: String) = (j[key] as? JsonPrimitive)?.takeUnless { it.isString }
                ?.longOrNull?.takeIf { it >= 0 }
            return TurnUsage(counter("input_tokens"), counter("output_tokens"), counter("cache_read_input_tokens"),
                counter("reasoning_tokens"), counter("duration_ms")).takeUnless { it == TurnUsage() }
        }
    }
}
