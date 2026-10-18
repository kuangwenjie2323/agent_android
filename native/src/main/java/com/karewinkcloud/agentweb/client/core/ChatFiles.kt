package com.karewinkcloud.agentweb.client.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/*
 * Files a chat names, opened on the phone the way Claude's desktop app opens them in its file pane: a step's
 * file, a path in the answer (`src/app.kt:42` opens at line 42), the files a chat changed.
 */

/** A file to open, and the line to show first (from "path:42", "path:42:7" or "path#L42"). */
data class FileRef(val path: String, val line: Int? = null)

/** A file as the server sent it for viewing: text (perhaps cut short), or a picture or PDF. */
class FileContent(val path: String, val kind: String, val mime: String, val bytes: ByteArray, val size: Long,
    val modifiedAt: Long, val truncated: Boolean) {
    val name: String get() = fileName(path)
    val text: String by lazy { bytes.toString(Charsets.UTF_8) }
}

/** How a file is best shown: Markdown and web pages rendered (their source a tap away), the rest as text. */
enum class FilePreview { MARKDOWN, WEB, IMAGE, PDF, TEXT }

fun filePreview(content: FileContent): FilePreview = when {
    content.kind == "image" -> FilePreview.IMAGE
    content.kind == "pdf" -> FilePreview.PDF
    else -> when (content.name.substringAfterLast('.', "").lowercase()) {
        "md", "markdown", "mdx" -> FilePreview.MARKDOWN
        "html", "htm", "svg" -> FilePreview.WEB
        else -> FilePreview.TEXT
    }
}

private val FRONT_MATTER = Regex("""\A---\r?\n(.*?)\r?\n---[ \t]*(?:\r?\n|\z)""", RegexOption.DOT_MATCHES_ALL)

/** Markdown to render: YAML front matter (--- name: … ---) becomes a block of code instead of a huge heading. */
fun markdownForPreview(text: String): String {
    val match = FRONT_MATTER.find(text) ?: return text
    return "```yaml\n" + match.groupValues[1] + "\n```\n\n" + text.substring(match.range.last + 1)
}

private val LINE_SUFFIX = Regex("""^(.+?)(?::(\d+)(?::\d+)?|#L(\d+)(?:-L?\d+)?)$""")

fun fileRef(value: String): FileRef? {
    val text = value.trim()
    if (text.isEmpty() || text.length > 4096) return null
    val match = LINE_SUFFIX.find(text) ?: return FileRef(text)
    return FileRef(match.groupValues[1], (match.groupValues[2].ifEmpty { match.groupValues[3] }).toIntOrNull())
}

/** File kinds an answer may name without a folder, like README.md or build.gradle.kts. */
private val FILE_EXTENSIONS = setOf(
    "md", "markdown", "mdx", "txt", "log", "csv", "tsv", "json", "jsonl", "yaml", "yml", "toml", "ini", "cfg", "conf",
    "xml", "html", "htm", "css", "scss", "svg", "js", "mjs", "cjs", "ts", "tsx", "jsx", "vue", "py", "ipynb", "kt", "kts",
    "java", "gradle", "properties", "go", "rs", "c", "h", "cc", "cpp", "hpp", "swift", "rb", "php", "lua", "sh", "bash",
    "zsh", "sql", "proto", "dart", "patch", "diff", "png", "jpg", "jpeg", "gif", "webp", "bmp", "pdf")

private val PATH_TOKEN = Regex("""^(?:file://)?[^\s*?"'<>|`$\\]*?\.([A-Za-z][A-Za-z0-9]{0,9})$""")

private fun absolute(path: String) = path.startsWith("/") || path.startsWith("~/") || path.startsWith("file://", true)

/**
 * The file a piece of `code` in an answer names, if it reads as one: a known kind of file (README.md,
 * src/app.kt:42), or any file given by its full path. Versions, domains and globs are not files.
 */
fun namedFile(code: String): FileRef? {
    val ref = fileRef(code) ?: return null
    if (ref.path.contains("://") && !ref.path.startsWith("file://", true)) return null
    val extension = PATH_TOKEN.find(ref.path)?.groupValues?.get(1)?.lowercase() ?: return null
    if (ref.path.substringAfterLast('/') == ".$extension") return null
    return ref.takeIf { extension in FILE_EXTENSIONS || absolute(ref.path) }
}

private val SCHEME = Regex("""^[A-Za-z][A-Za-z0-9+-]*:(?!\d)""")

/** The file a Markdown link points at ([report](docs/report.md)); null for web addresses and anchors. */
fun linkedFile(destination: String): FileRef? {
    val target = destination.trim()
    if (target.isEmpty() || target.startsWith("#") || target.startsWith("//")) return null
    val scheme = SCHEME.find(target)?.value
    if (scheme != null && !scheme.equals("file:", true)) return null
    val decoded = runCatching { java.net.URLDecoder.decode(target.replace("+", "%2B"), "UTF-8") }.getOrDefault(target)
    return fileRef(decoded)
}

private const val NOT_IN_NAME = """\s*?"'<>|`$\\，。；：！？、（）()\[\]{}"""

/** A full path in running text ("saved to /home/me/report.md"), ending where its file name's extension does. */
val PLAIN_PATH = Regex("""(?<![\w/.~:-])(?:~/|/)(?:[^/$NOT_IN_NAME]+/)*[^/$NOT_IN_NAME]+?\.(?:${FILE_EXTENSIONS.joinToString("|")})(?::\d+(?::\d+)?)?(?![A-Za-z0-9])""")

/** A file the chat's steps created or changed; [added] and [removed] add up all its edits. */
data class ChangedFile(val path: String, val added: Int, val removed: Int, val created: Boolean, val deleted: Boolean)

/** The files the steps of these turns changed (oldest turn first), the most recently changed first. */
fun changedFiles(turns: List<List<ChatBlock>>): List<ChangedFile> {
    val files = LinkedHashMap<String, ChangedFile>()
    turns.flatten().filterIsInstance<ChatBlock.Tool>().forEach { tool ->
        val kind = stepSummary(tool).kind
        if (kind != StepKind.EDIT && kind != StepKind.WRITE) return@forEach
        val removedHere = fileChanges(tool).filter { it.second == "delete" }.map { it.first }.toSet()
        stepDiffs(tool).filter { it.path.isNotBlank() }.forEach { diff ->
            val before = files.remove(diff.path)
            files[diff.path] = ChangedFile(diff.path, (before?.added ?: 0) + diff.added, (before?.removed ?: 0) + diff.removed,
                created = before?.created ?: (kind == StepKind.WRITE), deleted = diff.path in removedHere)
        }
    }
    return files.values.reversed()
}

/** The file a Read or image step worked on, by its full path; a read from a line opens at that line. */
fun stepFileRef(tool: ChatBlock.Tool): FileRef? {
    if (stepSummary(tool).kind !in setOf(StepKind.READ, StepKind.IMAGE)) return null
    val input = runCatching { wireJson.parseToJsonElement(tool.input) as? JsonObject }.getOrNull() ?: return null
    val path = listOf("file_path", "path").firstNotNullOfOrNull { key -> (input[key] as? JsonPrimitive)?.takeIf { it.isString }?.content }
        ?.takeIf { absolute(it) } ?: return null
    val line = (input["offset"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 1 }
    return FileRef(path, line)
}

/** What a chat changed in a file, placed on the file as it is now: the lines it added or rewrote, and how
 *  many lines it removed just before a line (1-based; one past the last line for the end). */
data class FileChanges(val added: Set<Int> = emptySet(), val removedBefore: Map<Int, Int> = emptyMap()) {
    val any get() = added.isNotEmpty() || removedBefore.isNotEmpty()
    /** First lines of each change, in order: where "next change" goes. */
    val starts: List<Int> get() = (added.filter { it - 1 !in added } + removedBefore.keys).distinct().sorted()
}

/** The edits a chat's steps made to [path] (oldest first). */
fun chatEdits(turns: List<List<ChatBlock>>, path: String): List<List<DiffRow>> =
    turns.flatten().filterIsInstance<ChatBlock.Tool>().flatMap { editHunks(it, path) }

/**
 * Finds each edit in the file by the text it left (its kept and added lines, in order) and marks it; an edit the
 * file no longer contains (changed again since, or undone) is skipped. Later edits win where they overlap.
 */
fun fileChanges(lines: List<String>, hunks: List<List<DiffRow>>): FileChanges {
    val text = lines.map(String::trimEnd)
    val added = mutableSetOf<Int>(); val removed = mutableMapOf<Int, Int>()
    for (hunk in hunks) {
        val kept = hunk.filter { it.kind == DiffKind.CONTEXT || it.kind == DiffKind.ADD }.map { it.text.trimEnd() }
        if (kept.none(String::isNotBlank) || hunk.none { it.kind != DiffKind.CONTEXT }) continue
        val start = (0..text.size - kept.size).firstOrNull { at -> kept.indices.all { text[at + it] == kept[it] } } ?: continue
        var line = start + 1; var pending = 0
        for (row in hunk) when (row.kind) {
            DiffKind.REMOVE -> pending++
            DiffKind.ADD, DiffKind.CONTEXT -> {
                if (pending > 0) { removed[line] = (removed[line] ?: 0) + pending; pending = 0 }
                if (row.kind == DiffKind.ADD) added += line
                line++
            }
            DiffKind.HEADER -> Unit
        }
        if (pending > 0) removed[line] = (removed[line] ?: 0) + pending
    }
    return FileChanges(added, removed)
}
