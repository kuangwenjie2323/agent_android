package com.karewinkcloud.agentweb.client.core

import kotlinx.serialization.json.*

data class Project(val id: String, val name: String, val rootLabel: String = "", val defaultBranch: String = "") {
    companion object {
        fun from(j: JsonObject): Project? {
            val id = j.string("id")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val name = j.string("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return Project(id.take(200), name.take(120), j.string("rootLabel").orEmpty().take(160), j.string("defaultBranch").orEmpty().take(200))
        }
    }
}
data class ProjectSelection(val projects: List<Project> = emptyList(), val selectedId: String? = null) {
    val selected get() = projects.find { it.id == selectedId }
    fun select(id: String?) = if (id == null || projects.any { it.id == id }) copy(selectedId = id) else this
    fun refreshed(items: List<Project>) = copy(projects = items.distinctBy { it.id }, selectedId = selectedId?.takeIf { id -> items.any { it.id == id } })
}

/** Only reads reconnect. HTTP rejections/protocol errors require reconciliation or user action. */
object ReconnectPolicy {
    fun delayMillis(failure: Int, base: Long = 500) = (base.coerceAtLeast(0).coerceAtMost(30_000) * (1L shl (failure - 1).coerceIn(0, 6))).coerceAtMost(30_000)
    fun retryStatus(status: Int) = status == 408 || status == 429 || status in 500..599
}

fun compactModelLabel(label: String) = label.replace(Regex("DeepSeek", RegexOption.IGNORE_CASE), "DS")

private val claudeModelId = Regex("claude-(opus|sonnet|haiku|fable)-(\\d+)(?:-(\\d))?(?:-\\d{8})?")
/** Concrete Claude ids recorded in usage ("claude-haiku-4-5-20251001") read as "Haiku 4.5". */
fun readableModelId(model: String): String = claudeModelId.matchEntire(model)?.let { m ->
    m.groupValues[1].replaceFirstChar { it.uppercase() } + " " + m.groupValues[2] + (m.groupValues[3].takeIf { it.isNotEmpty() }?.let { ".$it" } ?: "")
} ?: model
    .replace(Regex("\\s*\\([^)]*\\)"), "").replace("Claude ", "").replace("deepseek-", "DS ", true)
    .replace(Regex("(?i)v(\\d+(?:\\.\\d+)?)-"), "V$1 ").trim()
