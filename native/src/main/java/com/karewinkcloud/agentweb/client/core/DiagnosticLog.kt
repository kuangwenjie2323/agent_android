package com.karewinkcloud.agentweb.client.core

import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPOutputStream

/**
 * The app's own diagnostic log: one JSON object per line in a few small files that roll over, so it never
 * grows past [maxFiles] x [maxFileBytes]. It records timings, states and errors only, never message text,
 * attachments, tokens or headers, and it leaves the phone only when the user taps Upload in Settings.
 */
class DiagnosticLog(
    private val dir: File,
    private val maxFileBytes: Long = 512 * 1024,
    private val maxFiles: Int = 4,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val current = File(dir, "current.jsonl")

    fun write(level: Char, tag: String, message: String, fields: Map<String, Any?> = emptyMap()) {
        val line = buildJsonObject {
            put("t", clock()); put("l", level.toString()); put("tag", tag); put("m", message.take(MAX_MESSAGE))
            fields.forEach { (key, value) ->
                when (value) {
                    null -> Unit
                    is Boolean -> put(key, value)
                    is Number -> put(key, value)
                    else -> put(key, value.toString().take(if (key in LONG_FIELDS) MAX_TRACE else MAX_FIELD))
                }
            }
        }.toString() + "\n"
        val bytes = line.toByteArray()
        synchronized(lock) {
            runCatching {
                dir.mkdirs()
                if (current.length() > 0 && current.length() + bytes.size > maxFileBytes) rotate()
                FileOutputStream(current, true).use { it.write(bytes) }
            }
        }
    }

    private fun rotate() {
        var stamp = clock()
        while (File(dir, archiveName(stamp)).exists()) stamp++
        current.renameTo(File(dir, archiveName(stamp)))
        archives().dropLast(maxFiles - 1).forEach { it.delete() }
    }

    private fun archiveName(stamp: Long) = "log-%013d.jsonl".format(stamp)

    private fun archives(): List<File> = dir.listFiles { file -> file.name.startsWith("log-") && file.name.endsWith(".jsonl") }
        .orEmpty().sortedBy { it.name }

    /** Oldest line first. */
    fun lines(): List<String> = synchronized(lock) {
        (archives() + listOf(current).filter { it.isFile }).flatMap { file -> runCatching { file.readLines() }.getOrDefault(emptyList()) }
            .filter { it.isNotBlank() }
    }

    fun sizeBytes(): Long = synchronized(lock) { (archives() + listOf(current)).sumOf { if (it.isFile) it.length() else 0L } }

    fun clear() = synchronized(lock) { (archives() + listOf(current)).forEach { it.delete() } }

    /**
     * The whole log, oldest line first, as standalone gzip parts of at most [maxPartBytes] each, every part
     * whole lines. When there are more than [maxParts], the oldest lines are left out.
     */
    fun gzipParts(maxPartBytes: Int, maxParts: Int = 64): List<ByteArray> {
        val parts = ArrayList<ByteArray>()
        fun emit(chunk: List<String>) {
            val packed = gzip(chunk)
            when {
                packed.size <= maxPartBytes -> parts += packed
                chunk.size > 1 -> { emit(chunk.subList(0, chunk.size / 2)); emit(chunk.subList(chunk.size / 2, chunk.size)) }
                else -> parts += gzip(listOf(buildJsonObject { put("l", "W"); put("tag", "log"); put("m", "line too large, left out") }.toString()))
            }
        }
        val chunk = ArrayList<String>()
        var size = 0
        // Text compresses about 6x; the split above handles chunks that do not.
        val target = maxPartBytes * 6
        for (line in lines()) {
            if (chunk.isNotEmpty() && size + line.length > target) { emit(chunk.toList()); chunk.clear(); size = 0 }
            chunk += line; size += line.length + 1
        }
        if (chunk.isNotEmpty()) emit(chunk)
        return parts.takeLast(maxParts)
    }

    companion object {
        const val MAX_MESSAGE = 300
        const val MAX_FIELD = 600
        const val MAX_TRACE = 16 * 1024
        /** Fields that may carry a stack trace. */
        val LONG_FIELDS = setOf("stack", "trace")

        fun gzip(lines: List<String>): ByteArray = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { zip -> lines.forEach { zip.write((it + "\n").toByteArray()) } }
        }.toByteArray()
    }
}
