package com.karewinkcloud.agentweb.client.core

import kotlinx.serialization.json.jsonObject

private fun toolInput(tool: ChatBlock.Tool) = runCatching { wireJson.parseToJsonElement(tool.input).jsonObject }.getOrNull()
fun toolCommand(tool: ChatBlock.Tool): String {
    val input = toolInput(tool)
    val detail = input?.string("command") ?: input?.string("cmd") ?: input?.string("file_path") ?: input?.string("path")
    return if (detail.isNullOrBlank()) tool.name else "${tool.name} · ${detail.lineSequence().first().take(240)}"
}

/** Display only an explicit diff or the before/after edit carried by the tool call. */
fun compactToolDiff(tool: ChatBlock.Tool): String? {
    val input = toolInput(tool)
    val diff = tool.diff ?: input?.string("diff") ?: tool.result?.takeIf {
        it.lineSequence().any { line -> line.startsWith("@@ ") } &&
            it.lineSequence().any { line -> line.startsWith("--- ") || line.startsWith("diff --git ") }
    } ?: run {
        val before = input?.string("old_string") ?: return null
        val after = input.string("new_string") ?: return null
        before.lineSequence().map { "-$it" }.plus(after.lineSequence().map { "+$it" }).take(17).joinToString("\n")
    }
    val lines = diff.lineSequence().take(17).toList()
    return lines.take(16).joinToString("\n") { it.take(240) } + if (lines.size > 16) "\n…" else ""
}
