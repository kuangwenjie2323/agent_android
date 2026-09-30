package com.karewinkcloud.agentweb.client.core

import org.junit.Test
import org.junit.Assert.*
import org.commonmark.node.*
import kotlinx.serialization.json.*
import java.util.Base64

class RichFeaturesTest {
    @Test fun tableParsesAlignmentEscapesCodeAndMissingCells() {
        val table = richMarkdownParser().parse("| Name | Value |\n| :--- | ---: |\n| a\\|b | `x|y` |\n| short |\n").firstChild as TableBlock
        assertEquals(listOf("Name", "Value"), table.header)
        assertEquals(listOf(":---", "---:"), table.alignments)
        assertEquals(listOf("a|b", "`x|y`"), table.rows.first())
        assertEquals("Name\tValue\na|b\tx|y\nshort\t", table.tsv())
    }
    @Test fun tableDelimiterMustMatchHeaderAndMustNotOverrideCode() {
        assertTrue(richMarkdownParser().parse("a | b\n---\n").firstChild !is TableBlock)
        assertTrue(richMarkdownParser().parse("```\na | b\n--- | ---\n```\n").firstChild is FencedCodeBlock)
        assertTrue(richMarkdownParser().parse("a | b\n--- | --- | ---\n").firstChild !is TableBlock)
    }
    @Test fun streamedTableAndMathRemainTailsUntilCompleteThenFreeze() {
        val parser = StreamingMarkdown()
        val text = "# Intro\n\na | b\n--- | ---\n1 | 2\n"
        val first = parser.update(text, true)
        val second = parser.update(text + "3 | 4\n\n$$\nx^2\n", true)
        assertSame(first.first(), second.first())
        assertEquals(2, (second[1] as TableBlock).rows.size)
        val third = parser.update(text + "3 | 4\n\n$$\nx^2\n$$\n\nEnd", true)
        assertSame(second[1], third[1]); assertTrue(third[2] is MathBlock)
        val fourth = parser.update(text + "3 | 4\n\n$$\nx^2\n$$\n\nEnd now", true)
        assertSame(third[2], fourth[2])
    }
    @Test fun strikeSupportsNestedEmphasisAndTaskListsRemainLists() {
        val paragraph = richMarkdownParser().parse("~~**old**~~").firstChild
        assertTrue(paragraph.firstChild is Strike)
        assertTrue(paragraph.firstChild.firstChild is StrongEmphasis)
        val list = richMarkdownParser().parse("- [ ] todo\n  - [x] child\n- [X] done").firstChild
        assertTrue(list is BulletList); assertTrue(list.firstChild.lastChild is BulletList)
    }
    @Test fun malformedMathRemainsReadableAndUnclosedBlockNeverThrows() {
        assertEquals("α + a/b ≤ ∞", readableMath("\\alpha + \\frac{a}{b} \\leq \\infty"))
        assertEquals("∫₀^∞ e⁻ˣ² dx = √π/2", readableMath("\\int_0^\\infty e^{-x^2}\\,dx = \\frac{\\sqrt{\\pi}}{2}"))
        assertEquals("(a+b)/(c-d)", readableMath("\\frac{a+b}{c-d}"))
        assertEquals("xₘₐₓ + y₁₂ = speed", readableMath("x_{max} + y_{12} = \\text{speed}"))
        assertEquals("\\unknown{broken", readableMath("\\unknown{broken"))
        val math = richMarkdownParser().parse("$$\nx^2 + \\broken{").firstChild as MathBlock
        assertTrue(readableMath(math.literal).contains("x²"))
    }
    @Test fun commonHtmlFromModelsRendersAsText() {
        assertEquals("a\nb\nc", simpleHtmlText("a<br>b</br>c"))
        assertEquals("bold and key", simpleHtmlText("<b>bold</b> and <kbd>key</kbd>"))
        assertEquals("List<String>", simpleHtmlText("List<String>"))
        assertEquals("done", plainPreview("done\n</br>"))
    }
    @Test fun tokenizerPreservesEveryByteAcrossCommonLanguages() {
        val source = "const val = 42; /* note */ \"你好\"\nreturn val\n"
        for (lang in listOf("kotlin", "python", "js", "ts", "json", "bash", "sh", "sql", "html", "css", "go", "rust", "java", "c", "cpp", "yaml")) {
            val tokens = codeTokens(source, lang)
            assertEquals(source, tokens.joinToString("") { it.text })
            assertTrue(tokens.any { it.kind == TokenKind.NUMBER }); assertTrue(tokens.any { it.kind == TokenKind.STRING })
        }
    }
    @Test fun codeCommentsStringsUnknownLanguagesAndBoundsAreSafe() {
        assertEquals(TokenKind.COMMENT, codeTokens("# print(1)", "python").single().kind)
        assertEquals(TokenKind.COMMENT, codeTokens("-- SELECT 2", "sql").single().kind)
        assertEquals(TokenKind.STRING, codeTokens("\"// not comment\"", "json").single().kind)
        assertEquals(TokenKind.PLAIN, codeTokens("function x()", "unknown").single().kind)
        assertEquals(TokenKind.KEYWORD, codeTokens("select", "sql").single().kind)
        assertTrue(codeTokens("<div>", "html").any { it.text == "div" && it.kind == TokenKind.KEYWORD })
        assertEquals(1, codeTokens("a".repeat(200_001), "js").size)
    }
    @Test fun diffGuttersExcludeFileHeadersAndPreserveLineContent() {
        val lines = diffLines("--- a/x\n+++ b/x\n@@ -1 +1 @@\n-old\n+new\n unchanged")
        assertEquals(listOf(DiffKind.HEADER, DiffKind.HEADER, DiffKind.HEADER, DiffKind.REMOVE, DiffKind.ADD, DiffKind.CONTEXT), lines.map { it.kind })
        assertEquals("−", lines[3].gutter); assertEquals("new", lines[4].text); assertEquals("unchanged", lines.last().text)
        assertNotNull(compactToolDiff(ChatBlock.Tool("t", "Bash", result = "--- a/x\n+++ b/x\n@@ -1 +1 @@\n-old\n+new")))
    }
    @Test fun attachmentEncodingSplitsImagesAndFilesAndSupportsEmptyText() {
        val bytes = byteArrayOf(0, 1, 127, -1)
        val image = encodeAttachment("照片.png", "image/png", bytes, AttachmentKind.IMAGE)
        val file = encodeAttachment("file.txt", "text/plain", "你好".toByteArray(), AttachmentKind.FILE)
        val json = SendRequest("c", "", ModelChoice(), attachments = listOf(image, file)).json()
        assertEquals("(image)", json.string("message"))
        assertEquals("照片.png", json.objects("images").single().string("name"))
        assertArrayEquals(bytes, Base64.getDecoder().decode(json.objects("images").single().string("data_url")!!.substringAfter(',')))
        assertEquals("file.txt", json.objects("files").single().string("name"))
        assertEquals("(file)", QueueRequest("c", "", ModelChoice(), attachments = listOf(file)).json().string("message"))
        assertFalse(SendRequest("c", "hello", ModelChoice()).json().containsKey("images"))
    }
    @Test fun attachmentLimitsAreCombinedAndInclusiveWithClearErrors() {
        assertNull(attachmentError(List(4) { MAX_ATTACHMENT_BYTES }))
        assertTrue(attachmentError(List(5) { 1 })!!.contains("4"))
        assertTrue(attachmentError(listOf(MAX_ATTACHMENT_BYTES + 1))!!.contains("8 MB"))
        assertNotNull(attachmentError(listOf(-1)))
        assertEquals("application/octet-stream", encodeAttachment("../a\n", "bad\nmime", byteArrayOf(1), AttachmentKind.FILE).mime)
    }
    @Test fun historyAndLiveArtifactsUseSameAuthenticatedFilePath() {
        val item = buildJsonObject { put("key", "c/images/a.png"); put("name", "图.png"); put("mime", "image/png") }
        val history = ChatMessage.from(buildJsonObject { put("role", "user"); put("images", JsonArray(listOf(item))) })
        assertEquals("/api/files/c/images/a.png", history.blocks.filterIsInstance<ChatBlock.Media>().single().items.single().url)
        val reduced = TurnReducer.reduce(TurnState("c", "r"), StreamEvent(EventId("r", 1), "artifacts", buildJsonObject { put("items", JsonArray(listOf(item))) }), 1)
        assertEquals(history.blocks, reduced.blocks)
        val user = TurnReducer.reduce(TurnState("c", "r"), StreamEvent(EventId("r", 1), "artifacts", buildJsonObject { put("role", "user"); put("items", JsonArray(listOf(item))) }), 1)
        assertTrue(user.blocks.isEmpty()); assertEquals(1, user.lastSequence)
    }
    @Test fun projectSelectionResetsOnlyOnSuccessfulCatalogRefresh() {
        val project = Project("p", "项目", "repo", "main")
        val selected = ProjectSelection().refreshed(listOf(project, project)).select("p")
        assertEquals(1, selected.projects.size); assertEquals(project, selected.selected)
        assertEquals(selected, selected.select("missing"))
        assertNull(selected.refreshed(emptyList()).selectedId); assertNull(selected.select(null).selected)
        assertNull(Project.from(buildJsonObject { put("id", "p") }))
    }
    @Test fun previewUsesLastVisibleLineAndStripsMarkdown() {
        assertEquals("最后 link code", plainPreview("# First\n**最后** [link](https://example.test) `code`\n```"))
        assertEquals("done", plainPreview("intro\n- [x] ~~done~~"))
        assertEquals("https://example.test", plainPreview("<https://example.test>"))
        assertEquals("DS V4.1 Flash", compactModelLabel("DeepSeek V4.1 Flash"))
        assertEquals("snake_case", plainPreview("`snake_case`"))
    }
    @Test fun reconnectBackoffIsCappedAndHttpRejectionsStopRetries() {
        assertEquals(listOf(500L, 1000L, 2000L, 4000L, 8000L, 16000L, 30000L), (1..7).map { ReconnectPolicy.delayMillis(it) })
        assertEquals(30000, ReconnectPolicy.delayMillis(Int.MAX_VALUE))
        assertTrue(ReconnectPolicy.retryStatus(503)); assertTrue(ReconnectPolicy.retryStatus(429))
        for (status in listOf(400, 401, 403, 404, 409)) assertFalse(ReconnectPolicy.retryStatus(status))
    }
}
