package com.karewinkcloud.agentweb.client.core

import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.util.zip.GZIPInputStream

class DiagnosticLogTest {
    @get:Rule val folder = TemporaryFolder()
    private var now = 1_000L

    private fun log(maxFileBytes: Long = 512 * 1024, maxFiles: Int = 4) =
        DiagnosticLog(folder.root.resolve("diagnostics"), maxFileBytes, maxFiles) { now++ }

    private fun unzip(part: ByteArray) = GZIPInputStream(part.inputStream()).readBytes().decodeToString().lines().filter { it.isNotBlank() }

    @Test fun linesAreJsonWithBoundedFields() {
        val log = log()
        log.write('W', "net", "x".repeat(1000), mapOf("ms" to 12, "ok" to true, "skip" to null,
            "error" to "e".repeat(5000), "stack" to "s".repeat(5000)))
        val line = Json.parseToJsonElement(log.lines().single()).jsonObject
        assertEquals("W", line["l"]!!.jsonPrimitive.content)
        assertEquals("net", line["tag"]!!.jsonPrimitive.content)
        assertEquals(DiagnosticLog.MAX_MESSAGE, line["m"]!!.jsonPrimitive.content.length)
        assertEquals(12, line["ms"]!!.jsonPrimitive.int)
        assertTrue(line["ok"]!!.jsonPrimitive.boolean)
        assertFalse("skip" in line)
        assertEquals(DiagnosticLog.MAX_FIELD, line["error"]!!.jsonPrimitive.content.length)
        assertEquals(5000, line["stack"]!!.jsonPrimitive.content.length)
    }

    @Test fun filesRollOverAndTheOldestLinesGo() {
        val log = log(maxFileBytes = 400, maxFiles = 3)
        repeat(100) { log.write('I', "t", "line $it") }
        val files = folder.root.resolve("diagnostics").listFiles()!!
        assertTrue(files.size <= 3)
        assertTrue(log.sizeBytes() <= 3 * 400)
        val messages = log.lines().map { Json.parseToJsonElement(it).jsonObject["m"]!!.jsonPrimitive.content }
        assertEquals("line 99", messages.last())
        assertFalse("line 0" in messages)
        assertEquals(messages.sortedBy { it.removePrefix("line ").toInt() }, messages)
    }

    @Test fun gzipPartsAreSmallWholeLinesInOrder() {
        val log = log()
        val random = java.util.Random(7)
        // Random text barely compresses, so the parts must split.
        repeat(400) { i -> log.write('I', "t", "line $i", mapOf("noise" to (1..500).map { 'a' + random.nextInt(26) }.joinToString(""))) }
        val parts = log.gzipParts(8 * 1024)
        assertTrue(parts.size > 1)
        parts.forEach { assertTrue(it.size <= 8 * 1024) }
        assertEquals(log.lines(), parts.flatMap(::unzip))
    }

    @Test fun tooManyPartsKeepTheNewest() {
        val log = log()
        val random = java.util.Random(3)
        repeat(200) { i -> log.write('I', "t", "line $i", mapOf("noise" to (1..400).map { 'a' + random.nextInt(26) }.joinToString(""))) }
        val all = log.gzipParts(4 * 1024)
        val kept = log.gzipParts(4 * 1024, maxParts = 2)
        assertEquals(all.takeLast(2).map(::unzip), kept.map(::unzip))
        assertTrue(unzip(kept.last()).last().contains("line 199"))
    }

    @Test fun clearEmptiesTheLog() {
        val log = log(maxFileBytes = 200)
        repeat(20) { log.write('I', "t", "line $it") }
        log.clear()
        assertEquals(emptyList<String>(), log.lines())
        assertEquals(0L, log.sizeBytes())
        assertEquals(emptyList<ByteArray>(), log.gzipParts(1024))
    }
}
