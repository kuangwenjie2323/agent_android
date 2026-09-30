package com.karewinkcloud.agentweb.client.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.karewinkcloud.agentweb.client.R
import com.karewinkcloud.agentweb.client.R.string as S
import org.commonmark.node.*
import org.commonmark.node.Text as MarkdownText
import org.commonmark.node.Paragraph as MarkdownParagraph
import com.karewinkcloud.agentweb.client.core.*

private fun Node.children(): List<Node> = buildList {
    var node = firstChild
    while (node != null) { add(node); node = node.next }
}

/** Native Compose text only: HTML is literal, and links never execute script or intent URLs. */
@Composable
fun Markdown(text: String, modifier: Modifier = Modifier, streaming: Boolean = false) {
    val parser = remember { StreamingMarkdown() }
    val blocks = remember(text, streaming) { parser.update(text, streaming) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        blocks.forEachIndexed { index, node -> key(index) { MarkdownBlock(node) } }
    }
}

@Composable
private fun MarkdownBlock(node: Node) {
    when (node) {
        is MarkdownParagraph -> {
            val text = inlineText(node)
            if (text.isNotBlank()) Text(text, style = MaterialTheme.typography.bodyLarge)
            node.images().forEach { image -> ChatMediaCard(MessageMedia(image.children().filterIsInstance<MarkdownText>().joinToString("") { it.literal }.ifBlank { "image" }, "image/*", image.destination)) }
        }
        is Heading -> Text(inlineText(node), style = MaterialTheme.typography.bodyLarge.copy(
            fontSize = when (node.level) { 1 -> 26.sp; 2 -> 22.sp; else -> 18.sp }, fontWeight = FontWeight.SemiBold))
        is FencedCodeBlock -> CodeBlock(node.literal.removeSuffix("\n"), node.info.orEmpty().substringBefore(' '))
        is IndentedCodeBlock -> CodeBlock(node.literal.removeSuffix("\n"), "Code")
        is TableBlock -> TableView(node)
        is MathBlock -> Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(12.dp)) {
            Text(readableMath(node.literal), Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(16.dp), fontFamily = FontFamily.Serif, fontSize = 18.sp)
        }
        is BulletList, is OrderedList -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            node.children().forEachIndexed { index, child ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val task = (child.firstChild?.firstChild as? MarkdownText)?.literal?.let { Regex("^\\[([ xX])]\\s").find(it)?.groupValues?.get(1) }
                    Text(if (task != null) { if (task == " ") "☐" else "☑" } else if (node is OrderedList) "${node.markerStartNumber + index}." else "•",
                        Modifier.widthIn(min = 20.dp), style = MaterialTheme.typography.bodyLarge)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        child.children().forEach { MarkdownBlock(it) }
                    }
                }
            }
        }
        is BlockQuote -> Row(Modifier.fillMaxWidth()) {
            Column(Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(16.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) { node.children().forEach { MarkdownBlock(it) } }
        }
        is ThematicBreak -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        is HtmlBlock -> simpleHtmlText(node.literal).trim().takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        else -> node.children().forEach { MarkdownBlock(it) }
    }
}

private fun Node.images(): List<Image> = children().flatMap { if (it is Image) listOf(it) else it.images() }

@Composable
private fun inlineText(node: Node): AnnotatedString {
    val colors = MaterialTheme.colorScheme
    val open = LocalOpenLink.current
    return remember(node, colors, open) {
        buildAnnotatedString {
            var insideLink = false
            fun AnnotatedString.Builder.link(url: String, label: () -> Unit) {
                if (insideLink) { label(); return }
                insideLink = true
                withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline)),
                    LinkInteractionListener { open(url) })) { label() }
                insideLink = false
            }
            fun AnnotatedString.Builder.literal(value: String) {
                val pattern = Regex("https?://[^\\s<>]+|www\\.[^\\s<>]+|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}|(?<!\\\\)\\$([^$\\n]+)\\$")
                var start = 0
                pattern.findAll(value).forEach { match ->
                    append(value.substring(start, match.range.first))
                    if (match.value.startsWith('$')) withStyle(SpanStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic)) { append(readableMath(match.groupValues[1])) }
                    else {
                        val url = match.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']')
                        link(when { url.startsWith("www.") -> "https://$url"; '@' in url && !url.startsWith("http") -> "mailto:$url"; else -> url }) { append(url) }
                        append(match.value.removePrefix(url))
                    }
                    start = match.range.last + 1
                }
                append(value.substring(start))
            }
            fun AnnotatedString.Builder.walk(current: Node) {
                when (current) {
                    is MarkdownText -> {
                        val taskFirst = current.previous == null && current.parent is MarkdownParagraph && current.parent.previous == null && current.parent.parent is ListItem
                        literal(if (taskFirst) current.literal.replace(Regex("^\\[[ xX]]\\s+"), "") else current.literal)
                    }
                    is SoftLineBreak -> append("\n")
                    is HardLineBreak -> append("\n")
                    is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = colors.surfaceContainerLow)) { append(current.literal) }
                    is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { current.children().forEach { walk(it) } }
                    is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { current.children().forEach { walk(it) } }
                    is Strike -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { current.children().forEach { walk(it) } }
                    is Link -> {
                        val safe = current.destination.startsWith("https://", true) || current.destination.startsWith("http://", true) || current.destination.startsWith("mailto:", true) || current.destination.startsWith("/api/files/")
                        if (safe) link(current.destination) { current.children().forEach { walk(it) } }
                        else current.children().forEach { walk(it) }
                    }
                    is Image -> Unit
                    is HtmlInline -> append(simpleHtmlText(current.literal))
                    else -> current.children().forEach { walk(it) }
                }
            }
            walk(node)
        }
    }
}

@Suppress("DEPRECATION")
@Composable
internal fun CodeBlock(code: String, language: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(code) { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(language.ifBlank { tr(S.code) }, style = MaterialTheme.typography.labelMedium)
                ActionIcon(if (copied) R.drawable.aw_check else R.drawable.aw_copy, tr(if (copied) S.copied else S.copy_code)) { clipboard.setText(AnnotatedString(code)); copied = true }
            }
            SelectionContainer {
                if (language.lowercase() in setOf("diff", "patch")) DiffView(code)
                else Text(highlightedCode(code, language), Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 21.sp)
            }
        }
    }
}

@Composable
private fun highlightedCode(code: String, language: String): AnnotatedString {
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < .5f
    return remember(code, language, dark, colors) { buildAnnotatedString {
        codeTokens(code, language).forEach { token -> withStyle(SpanStyle(color = when (token.kind) {
            TokenKind.KEYWORD -> if (dark) Color(0xffcfb1f0) else Color(0xff713b89)
            TokenKind.STRING -> if (dark) Color(0xffb7d69a) else Color(0xff376323)
            TokenKind.NUMBER -> if (dark) Color(0xffedc082) else Color(0xff86520c)
            TokenKind.COMMENT -> colors.onSurfaceVariant
            TokenKind.PLAIN -> colors.onSurface
        })) { append(token.text) } }
    } }
}

@Composable
internal fun DiffView(diff: String) {
    val lines = remember(diff) { diffLines(diff) }
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).width(IntrinsicSize.Max).padding(vertical = 8.dp)) {
        lines.forEach { line ->
            val background = when(line.kind) { DiffKind.ADD -> if (dark) Color(0xff213c28) else Color(0xffe0f0de)
                DiffKind.REMOVE -> MaterialTheme.colorScheme.errorContainer; else -> Color.Transparent }
            Row(Modifier.fillMaxWidth().background(background).padding(horizontal = 8.dp, vertical = 1.dp)) {
                Text(line.gutter, Modifier.width(24.dp), fontFamily = FontFamily.Monospace,
                    color = when (line.kind) { DiffKind.ADD -> if (dark) Color(0xffb2e3b3) else Color(0xff285b30)
                        DiffKind.REMOVE -> MaterialTheme.colorScheme.onErrorContainer; else -> MaterialTheme.colorScheme.onSurfaceVariant })
                Text(line.text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 21.sp,
                    color = if (line.kind == DiffKind.REMOVE) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Suppress("DEPRECATION")
@Composable
private fun TableView(table: TableBlock) {
    val clipboard = LocalClipboardManager.current
    val parser = remember { richMarkdownParser() }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(12.dp)) {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton({ clipboard.setText(AnnotatedString(table.tsv())) }, Modifier.heightIn(min = 48.dp)) { Text(tr(S.copy_table)) }
            }
            // Up to three columns share the bubble width; wider tables keep 180 dp cells and scroll.
            BoxWithConstraints { val cellWidth = table.header.size.coerceAtLeast(1).let { n -> if (n <= 3) maxWidth / n else 180.dp }
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                (listOf(table.header) + table.rows).forEachIndexed { row, cells ->
                    Row(Modifier.height(IntrinsicSize.Min)) {
                        table.header.indices.forEach { column ->
                            val node = remember(cells.getOrElse(column) { "" }) { parser.parse(cells.getOrElse(column) { "" }) }
                            Text(inlineText(node), Modifier.width(cellWidth).fillMaxHeight()
                                .background(if (row == 0) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                                .border(.5.dp, MaterialTheme.colorScheme.outlineVariant).padding(10.dp),
                                style = MaterialTheme.typography.bodyMedium, fontWeight = if (row == 0) FontWeight.SemiBold else FontWeight.Normal,
                                textAlign = when { table.alignments[column].startsWith(':') && table.alignments[column].endsWith(':') -> androidx.compose.ui.text.style.TextAlign.Center
                                    table.alignments[column].endsWith(':') -> androidx.compose.ui.text.style.TextAlign.End; else -> androidx.compose.ui.text.style.TextAlign.Start })
                        }
                    }
                }
            }
            }
        }
    }
}
