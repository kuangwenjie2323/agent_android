package com.karewinkcloud.agentweb.client.core

import org.commonmark.node.Node
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser

/** Keeps completed top-level AST nodes by identity. Only the unfinished block is parsed again. */
class StreamingMarkdown {
    private val parser = richMarkdownParser()
    private val frozen = mutableListOf<Node>()
    private var offset = 0
    private var previous = ""
    var parsedCharacters = 0L
        private set

    fun update(source: String, streaming: Boolean): List<Node> {
        if (!source.startsWith(previous)) { frozen.clear(); offset = 0 }
        previous = source
        val tail = source.substring(offset)
        parsedCharacters += tail.length
        val document = parser.parse(tail)
        val nodes = mutableListOf<Node>()
        var node = document.firstChild
        while (node != null) { nodes += node; node = node.next }
        if (nodes.size > 1) {
            val lastLine = nodes.last().sourceSpans.firstOrNull()?.lineIndex ?: 0
            var boundary = 0
            repeat(lastLine) { boundary = tail.indexOf('\n', boundary).let { if (it < 0) tail.length else it + 1 } }
            if (boundary > 0) {
                frozen += nodes.dropLast(1).onEach { it.unlink() }
                offset += boundary
                nodes.subList(0, nodes.lastIndex).clear()
            }
        }
        val visibleTail = if (streaming) hideIncompleteInline(source.substring(offset)) else source.substring(offset)
        if (visibleTail != source.substring(offset)) {
            parsedCharacters += visibleTail.length
            val masked = parser.parse(visibleTail)
            nodes.clear()
            node = masked.firstChild
            while (node != null) { nodes += node; node = node.next }
        }
        return frozen + nodes
    }
}

/** Hide unfinished delimiters, never code fences, escapes, list bullets or word underscores. */
fun hideIncompleteInline(source: String): String {
    val hidden = BooleanArray(source.length)
    val open = mutableMapOf<String, Int>()
    var fence: String? = null
    var code: String? = null
    var linkStart: Int? = null
    var i = 0
    while (i < source.length) {
        val lineStart = i == 0 || source[i - 1] == '\n'
        if (lineStart) {
            val end = source.indexOf('\n', i).let { if (it < 0) source.length else it }
            val line = source.substring(i, end)
            val marker = Regex("^ {0,3}(`{3,}|~{3,})").find(line)?.groupValues?.get(1)
            if (marker != null) {
                if (fence == null) fence = marker
                else if (marker.first() == fence.first() && marker.length >= fence.length) fence = null
                i = end + 1; continue
            }
            if (fence != null || line.startsWith("    ") || line.startsWith('\t')) { i = end + 1; continue }
        }
        val c = source[i]
        if (c == '\\') { i += 2; continue }
        if (c == '`') {
            var end = i + 1
            while (end < source.length && source[end] == c) end++
            val token = source.substring(i, end)
            if (code == token) { open.remove(token); code = null }
            else if (code == null) { code = token; open[token] = i }
            i = end; continue
        }
        if (code != null) { i++; continue }
        if (c == '*' || c == '_' || c == '~') {
            var end = i + 1
            while (end < source.length && source[end] == c) end++
            if (c == '~' && end - i < 2) { i = end; continue }
            val token = source.substring(i, end)
            val before = source.getOrNull(i - 1)
            val after = source.getOrNull(end)
            if (!(c == '_' && before?.isLetterOrDigit() == true && after?.isLetterOrDigit() == true)) {
                if (token in open && before?.isWhitespace() == false) open.remove(token)
                else if (after == null || (!after.isWhitespace() && (c == '*' || before == null || !before.isLetterOrDigit()))) open[token] = i
            }
            i = end; continue
        }
        if (c == '[') linkStart = i
        if (c == ']' && source.getOrNull(i + 1) == '(' && linkStart != null) {
            val close = source.indexOf(')', i + 2)
            if (close < 0) { hidden[linkStart] = true; for (k in i until source.length) hidden[k] = true; break }
            linkStart = null; i = close + 1; continue
        }
        if (c == ']' && source.getOrNull(i + 1) != null) linkStart = null
        i++
    }
    open.forEach { (token, start) -> for (k in start until start + token.length) hidden[k] = true }
    linkStart?.let { hidden[it] = true; if (source.lastOrNull() == ']') hidden[source.lastIndex] = true }
    return buildString { source.forEachIndexed { index, c -> if (!hidden[index]) append(c) } }
}
