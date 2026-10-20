package com.karewinkcloud.agentweb.client.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** What one step of a turn does: picks its icon, its title and how its detail is shown. */
enum class StepKind { THINK, COMMAND, READ, EDIT, WRITE, SEARCH, WEB_SEARCH, WEB_FETCH, IMAGE, AGENT, TODO, MCP,
    TASK_OUTPUT, TASK_STOP, OTHER }

/**
 * [target] is the one line under the title: a description, a command, a file name, a query. [taskRef] is
 * the background task a TASK_OUTPUT or TASK_STOP step is about (its id), to show that task's name instead.
 */
data class StepSummary(val kind: StepKind, val target: String?, val mcpTool: String? = null, val taskRef: String? = null)

/** Claude Code writes a background task's output to <tmp>/…/tasks/<id>.output; reading it checks on the task. */
private val TASK_OUTPUT_FILE = Regex("""/tasks/([A-Za-z0-9_-]+)\.output$""")

private fun input(tool: ChatBlock.Tool) = runCatching { wireJson.parseToJsonElement(tool.input).jsonObject }.getOrNull()
private fun JsonObject.text(vararg keys: String) = keys.firstNotNullOfOrNull { key ->
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf(String::isNotBlank)
}
private fun firstLine(value: String?) = value?.lineSequence()?.map(String::trim)?.firstOrNull { it.isNotEmpty() }?.take(200)
fun fileName(path: String) = path.trimEnd('/').substringAfterLast('/').ifBlank { path }

fun stepSummary(tool: ChatBlock.Tool): StepSummary {
    val input = input(tool)
    val name = tool.name.trim()
    val lower = name.lowercase()
    // MCP tools come as mcp__server__tool (Claude Code) or mcp:server/tool (Codex).
    val mcp = when {
        lower.startsWith("mcp__") -> name.removePrefix("mcp__").split("__").let { parts -> parts.lastOrNull() }
        lower.startsWith("mcp:") -> name.substringAfterLast('/')
        else -> null
    }
    val outputFile = input?.text("file_path", "path")?.let { TASK_OUTPUT_FILE.find(it)?.groupValues?.get(1) }
    return when {
        mcp != null -> StepSummary(StepKind.MCP, firstLine(input?.text("query", "url", "prompt", "question", "path", "message")), mcp)
        lower in setOf("taskoutput", "bashoutput", "task_output") || (outputFile != null && lower in setOf("read", "view", "read_file")) -> {
            val ref = outputFile ?: input?.text("task_id", "bash_id", "shell_id")
            StepSummary(StepKind.TASK_OUTPUT, ref, taskRef = ref)
        }
        lower in setOf("taskstop", "killshell", "killbash", "task_stop") -> {
            val ref = input?.text("task_id", "shell_id", "bash_id")
            StepSummary(StepKind.TASK_STOP, ref, taskRef = ref)
        }
        lower in setOf("bash", "shell", "exec", "exec_command", "local_shell", "run_command", "command_execution") ->
            StepSummary(StepKind.COMMAND, firstLine(input?.text("description")) ?: firstLine(input?.text("command", "cmd")))
        lower == "read" || lower == "view" || lower == "read_file" ->
            StepSummary(StepKind.READ, input?.text("file_path", "path")?.let(::fileName))
        lower == "edit" || lower == "multiedit" || lower == "str_replace_editor" || lower == "apply_patch" ->
            StepSummary(StepKind.EDIT, input?.text("file_path", "path")?.let(::fileName))
        lower == "write" || lower == "create_file" -> StepSummary(StepKind.WRITE, input?.text("file_path", "path")?.let(::fileName))
        lower == "file_change" -> {
            val paths = changes(input).map { fileName(it.first) }
            StepSummary(if (changes(input).all { it.second == "add" }) StepKind.WRITE else StepKind.EDIT,
                paths.firstOrNull()?.let { if (paths.size > 1) "$it +${paths.size - 1}" else it })
        }
        lower == "grep" || lower == "glob" || lower == "search" || lower == "find" ->
            StepSummary(StepKind.SEARCH, firstLine(input?.text("pattern", "query")))
        lower == "websearch" || lower == "web_search" -> StepSummary(StepKind.WEB_SEARCH, firstLine(input?.text("query")))
        lower == "webfetch" || lower == "web_fetch" || lower == "fetch" ->
            StepSummary(StepKind.WEB_FETCH, input?.text("url")?.let { url -> url.substringAfter("://").substringBefore('/').ifBlank { url } })
        lower == "view_image" -> StepSummary(StepKind.IMAGE, input?.text("path")?.let(::fileName))
        lower == "agent" || lower == "task" || lower.startsWith("claude team") || lower == "claude teammate" || lower == "wait" ->
            StepSummary(StepKind.AGENT, firstLine(input?.text("description", "subject", "prompt", "role")))
        lower.startsWith("task") || lower == "todowrite" -> StepSummary(StepKind.TODO, firstLine(input?.text("subject", "description")))
        else -> StepSummary(StepKind.OTHER, firstLine(input?.text("description", "query", "url", "command", "path")))
    }
}

/** Whether the step left work running in the background (Bash or Agent with run_in_background). */
fun isBackground(tool: ChatBlock.Tool): Boolean =
    tool.task != null || (input(tool)?.get("run_in_background") as? JsonPrimitive)?.contentOrNull == "true"

/** The background tasks a turn's steps started, in order. */
fun backgroundTasks(blocks: List<ChatBlock>): List<Pair<ChatBlock.Tool, BackgroundTask>> =
    blocks.filterIsInstance<ChatBlock.Tool>().mapNotNull { tool -> tool.task?.let { tool to it } }

/** What to call a background task: its description, else what its step was about. */
fun taskName(tool: ChatBlock.Tool, task: BackgroundTask): String =
    task.description.ifBlank { stepSummary(tool).target ?: tool.name }

/** The full command, address, query or path a step was given, for its detail view. */
fun stepInput(tool: ChatBlock.Tool): String? =
    input(tool)?.text("command", "cmd", "url", "query", "pattern", "file_path", "path", "prompt", "description")

/** (path, kind) for each change of a Codex file_change step. */
private fun changes(input: JsonObject?) = changeTriples(input).map { it.first to it.second }

/** (path, kind) for each change of a Codex file_change step: add, delete or update; empty for other steps. */
fun fileChanges(tool: ChatBlock.Tool): List<Pair<String, String>> =
    if (tool.name.equals("file_change", true)) changes(input(tool)) else emptyList()

/** (path, kind, diff) for each change of a Codex file_change step; an added file's diff is its content. */
private fun changeTriples(input: JsonObject?) = (input?.get("changes") as? JsonArray).orEmpty().mapNotNull { item ->
    val change = item as? JsonObject ?: return@mapNotNull null
    val path = change.text("path") ?: return@mapNotNull null
    val kind = ((change["kind"] as? JsonObject)?.text("type") ?: change.text("kind") ?: "update").lowercase()
    Triple(path, kind, change.text("diff").orEmpty())
}

/** One line of a file diff; numbers are known only when the diff carries hunk headers. */
data class DiffRow(val kind: DiffKind, val text: String, val oldLine: Int? = null, val newLine: Int? = null)
data class FileDiff(val path: String, val rows: List<DiffRow>, val added: Int, val removed: Int, val truncated: Boolean = false)

private const val MAX_DIFF_ROWS = 400

private fun fileDiff(path: String, rows: List<DiffRow>): FileDiff {
    val shown = rows.take(MAX_DIFF_ROWS)
    return FileDiff(path, shown, rows.count { it.kind == DiffKind.ADD }, rows.count { it.kind == DiffKind.REMOVE }, rows.size > shown.size)
}

/** The file changes a step made, in a form a diff view can draw (empty for steps that change nothing). */
fun stepDiffs(tool: ChatBlock.Tool): List<FileDiff> {
    val input = input(tool)
    val lower = tool.name.lowercase()
    tool.diff?.takeIf { it.isNotBlank() }?.let { return listOf(fileDiff(input?.text("file_path", "path") ?: "", unifiedRows(it))) }
    return when (lower) {
        "edit", "str_replace_editor" -> {
            val path = input?.text("file_path", "path") ?: return emptyList()
            val old = input.string("old_string") ?: input.string("old_str") ?: return emptyList()
            val new = input.string("new_string") ?: input.string("new_str") ?: return emptyList()
            listOf(fileDiff(path, lineDiff(old, new)))
        }
        "multiedit" -> {
            val path = input?.text("file_path", "path") ?: return emptyList()
            val edits = (input["edits"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            listOf(fileDiff(path, edits.flatMap { edit -> lineDiff(edit.string("old_string").orEmpty(), edit.string("new_string").orEmpty()) }))
        }
        "write", "create_file" -> {
            val path = input?.text("file_path", "path") ?: return emptyList()
            val content = input.string("content") ?: return emptyList()
            listOf(fileDiff(path, content.trimEnd('\n').lines().mapIndexed { i, line -> DiffRow(DiffKind.ADD, line, newLine = i + 1) }))
        }
        "file_change" -> changeTriples(input).map { (path, kind, diff) ->
            when (kind) {
                "add" -> fileDiff(path, diff.trimEnd('\n').lines().mapIndexed { i, line -> DiffRow(DiffKind.ADD, line, newLine = i + 1) })
                "delete" -> fileDiff(path, emptyList())
                else -> fileDiff(path, unifiedRows(diff))
            }
        }
        else -> emptyList()
    }
}

/**
 * The edits a step made to [path], one hunk per edit (or per @@ section of a patch), unshortened, for marking
 * them on the file. A file written whole has none: every line of it would be new.
 */
fun editHunks(tool: ChatBlock.Tool, path: String): List<List<DiffRow>> {
    val input = input(tool) ?: return emptyList()
    val own = input.text("file_path", "path")
    fun sections(diff: String) = unifiedRows(diff).fold(mutableListOf<MutableList<DiffRow>>()) { all, row ->
        if (row.kind == DiffKind.HEADER || all.isEmpty()) all += mutableListOf<DiffRow>()
        if (row.kind != DiffKind.HEADER) all.last() += row
        all
    }.filter { it.isNotEmpty() }
    tool.diff?.takeIf { it.isNotBlank() && own == path }?.let { return sections(it) }
    return when (tool.name.lowercase()) {
        "edit", "str_replace_editor" -> if (own != path) emptyList() else listOf(
            lineDiff(input.string("old_string") ?: input.string("old_str") ?: return emptyList(),
                input.string("new_string") ?: input.string("new_str") ?: return emptyList()))
        "multiedit" -> if (own != path) emptyList() else (input["edits"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .map { edit -> lineDiff(edit.string("old_string").orEmpty(), edit.string("new_string").orEmpty()) }
        "file_change" -> changeTriples(input).filter { it.first == path && it.second == "update" }.flatMap { sections(it.third) }
        else -> emptyList()
    }
}

private val HUNK = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@""")

/** A unified diff, numbered from its hunk headers; file headers are dropped. */
fun unifiedRows(diff: String): List<DiffRow> {
    var old: Int? = null; var new: Int? = null
    val rows = mutableListOf<DiffRow>()
    for (line in diff.trimEnd('\n').lines()) {
        when {
            line.startsWith("+++") || line.startsWith("---") || line.startsWith("diff ") || line.startsWith("index ") -> Unit
            line.startsWith("@@") -> {
                HUNK.find(line)?.let { match -> old = match.groupValues[1].toInt(); new = match.groupValues[2].toInt() }
                rows += DiffRow(DiffKind.HEADER, line)
            }
            line.startsWith("+") -> { rows += DiffRow(DiffKind.ADD, line.drop(1), newLine = new); new = new?.plus(1) }
            line.startsWith("-") -> { rows += DiffRow(DiffKind.REMOVE, line.drop(1), oldLine = old); old = old?.plus(1) }
            line.startsWith("\\") -> Unit  // "\ No newline at end of file"
            else -> { rows += DiffRow(DiffKind.CONTEXT, line.removePrefix(" "), old, new); old = old?.plus(1); new = new?.plus(1) }
        }
    }
    return rows
}

/** Line diff of two texts (longest common subsequence); unchanged lines stay as context. */
fun lineDiff(old: String, new: String): List<DiffRow> {
    val a = if (old.isEmpty()) emptyList() else old.trimEnd('\n').lines()
    val b = if (new.isEmpty()) emptyList() else new.trimEnd('\n').lines()
    if (a.size.toLong() * b.size > 250_000) return a.map { DiffRow(DiffKind.REMOVE, it) } + b.map { DiffRow(DiffKind.ADD, it) }
    val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
    for (i in a.indices.reversed()) for (j in b.indices.reversed())
        lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
    val rows = mutableListOf<DiffRow>()
    var i = 0; var j = 0
    while (i < a.size || j < b.size) {
        when {
            i < a.size && j < b.size && a[i] == b[j] -> { rows += DiffRow(DiffKind.CONTEXT, a[i]); i++; j++ }
            // Removed lines come before the lines that replace them, as git and Claude Code show them.
            i < a.size && (j == b.size || lcs[i + 1][j] >= lcs[i][j + 1]) -> { rows += DiffRow(DiffKind.REMOVE, a[i]); i++ }
            else -> { rows += DiffRow(DiffKind.ADD, b[j]); j++ }
        }
    }
    return rows
}

/** A turn's blocks in reading order: answer text and other cards, with the work between them grouped. */
sealed interface TurnPiece {
    /** Consecutive thinking and tool steps; [first] is the index of its first block, a stable key. */
    data class Work(val steps: List<ChatBlock>, val first: Int) : TurnPiece
    data class Single(val block: ChatBlock, val index: Int) : TurnPiece
}

fun turnPieces(blocks: List<ChatBlock>): List<TurnPiece> {
    val pieces = mutableListOf<TurnPiece>()
    var work = mutableListOf<ChatBlock>(); var first = -1
    fun flush() { if (work.isNotEmpty()) pieces += TurnPiece.Work(work, first); work = mutableListOf(); first = -1 }
    blocks.forEachIndexed { index, block ->
        val step = block is ChatBlock.Thought || (block is ChatBlock.Tool && !block.name.endsWith("ask_user"))
        when {
            // A finished thought with no words that took under a second tells the reader nothing. An outline
            // placeholder is kept even then: its words were left out, and opening it fetches them.
            block is ChatBlock.Thought && block.status != StepStatus.RUNNING && block.content.isBlank() &&
                (block.durationMs ?: 0) < 1000 && (block.chars ?: 0) == 0 -> Unit
            step -> { if (work.isEmpty()) first = index; work += block }
            // The ask_user call is shown as its question card, never as a step.
            block is ChatBlock.Tool -> Unit
            // An empty text block (a stream that has not started) does not split the work around it.
            block is ChatBlock.Text && block.content.isBlank() -> Unit
            else -> { flush(); pieces += TurnPiece.Single(block, index) }
        }
    }
    flush()
    return pieces
}
