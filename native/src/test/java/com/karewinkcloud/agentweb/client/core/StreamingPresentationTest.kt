package com.karewinkcloud.agentweb.client.core

import org.junit.Test
import org.junit.Assert.*
import org.commonmark.node.*
import kotlinx.serialization.json.*

class StreamingPresentationTest {
    @Test fun burstRevealsGraduallyAndFinishesWithinOneSecond() {
        val pacer = StreamPacer()
        pacer.offer(20_000, 0)
        assertTrue(pacer.frame(16) in 1 until 20_000)
        var visible = 0
        for (time in 32L..864L step 16) { val next = pacer.frame(time); assertTrue(next >= visible); visible = next }
        assertEquals(20_000, visible); assertFalse(pacer.pending)
    }
    @Test fun steadyPacingAcceleratesWithBacklogAndRetainsServerTruth() {
        val small = StreamPacer(); val large = StreamPacer()
        small.offer(100, 0); large.offer(10_000, 0)
        assertTrue(large.frame(16) > small.frame(16))
        val state = TurnState("chat", "run", 10, listOf(ChatBlock.Text("A".repeat(10_000)), ChatBlock.Error(ClientError("cancelled", "cancelled", false))), done = true)
        val shown = large.present(state, 32)
        assertEquals(10, shown.lastSequence); assertTrue(shown.done); assertTrue(shown.revealing)
        assertEquals(state.blocks.last(), shown.blocks.last())
    }
    @Test fun continuousArrivalNeverFallsMoreThanOneSecondBehind() {
        val pacer = StreamPacer()
        for (time in 0L..10_000L step 16) {
            pacer.offer(((time + 16) * 10).toInt(), time)
            val visible = pacer.frame(time)
            assertTrue(visible >= ((time - 864).coerceAtLeast(0) * 10).toInt())
        }
    }
    @Test fun revealNeverSplitsSurrogateAndPreservesCompletedBlockIdentity() {
        val first = ChatBlock.Text("done")
        val state = TurnState("c", "r", blocks = listOf(first, ChatBlock.Text("😀".repeat(100))))
        val pacer = StreamPacer(); pacer.offer(state.text.length, 0)
        for (time in 0L..900 step 16) {
            val shown = pacer.present(state, time)
            val tail = (shown.blocks.last() as ChatBlock.Text).content
            assertFalse(tail.lastOrNull()?.isHighSurrogate() == true)
            if (shown.text.length >= 4) assertSame(first, shown.blocks.first())
        }
    }
    @Test fun completedMarkdownNodesStayFrozenWhileTailIsParsedAsMarkdown() {
        val parser = StreamingMarkdown()
        val first = parser.update("# Title\n\n**bold**", true)
        val next = parser.update("# Title\n\n**bold** and *more*", true)
        assertSame(first.first(), next.first())
        assertTrue(next.first() is Heading)
        assertTrue(next.last().firstChild is StrongEmphasis)
        val finished = parser.update("# Title\n\n**bold** and *more*", false)
        assertSame(next.first(), finished.first())
    }
    @Test fun unfinishedInlineDelimitersAreHiddenUntilTheyClose() {
        assertEquals("A bold", hideIncompleteInline("A **bold"))
        assertEquals("A **bold**", hideIncompleteInline("A **bold**"))
        assertEquals("中强调", hideIncompleteInline("中*强调"))
        assertEquals("use code", hideIncompleteInline("use `code"))
        assertEquals("a label", hideIncompleteInline("a [label](https://exa"))
        assertEquals("a label", hideIncompleteInline("a [label"))
        assertEquals("a label", hideIncompleteInline("a [label]"))
    }
    @Test fun codeFencesListsEscapesAndWordUnderscoresRemainIntact() {
        for (text in listOf("```kt\nval x = \"**hi\"\n", "* item", "snake_case", "a \\*literal", "`a * b`", "a * b", "    x**y")) {
            assertEquals(text, hideIncompleteInline(text))
        }
    }
    @Test fun blankLinesInsideFenceOrListDoNotFreezeAnUnfinishedBlock() {
        val parser = StreamingMarkdown()
        val first = parser.update("```kt\nval a = 1\n\n", true)
        assertEquals(1, first.size); assertTrue(first.first() is FencedCodeBlock)
        val second = parser.update("```kt\nval a = 1\n\nval b = 2\n```\n\nNext", true)
        assertEquals(2, second.size); assertTrue((second.first() as FencedCodeBlock).literal.contains("val b"))
        val list = StreamingMarkdown().update("- first\n\n  continuation\n- second", true)
        assertEquals(1, list.size); assertTrue(list.first() is BulletList)
    }
    @Test fun syntheticFiveThousandTokenStreamBoundsParserWorkAndFreezesHistory() {
        val parser = StreamingMarkdown()
        val pacer = StreamPacer()
        val source = StringBuilder()
        var now = 0L
        var firstBlock: Node? = null
        repeat(5000) { token ->
            source.append(if (token % 20 == 19) "end\n\n" else "字 word ")
            if (token % 8 == 7 || token == 4999) {
                now += 16
                val full = source.toString()
                pacer.offer(full.length, now)
                val length = pacer.frame(now)
                val nodes = parser.update(full.take(length), true)
                if (nodes.size > 1) {
                    if (firstBlock == null) firstBlock = nodes.first() else assertSame(firstBlock, nodes.first())
                }
            }
        }
        now += 864
        val end = parser.update(source.toString().take(pacer.frame(now)), false)
        assertFalse(pacer.pending); assertEquals(250, end.size)
        assertTrue("only tail blocks should be reparsed: ${parser.parsedCharacters}", parser.parsedCharacters < source.length * 8L)
    }
    @Test fun replacementResetsTheMarkdownCache() {
        val parser = StreamingMarkdown()
        parser.update("# old\n\nold text", true)
        val fresh = parser.update("# replacement", false)
        assertEquals(1, fresh.size); assertEquals("replacement", (fresh.first().firstChild as Text).literal)
    }
    @Test fun toolPresentationShowsExplicitDiffAndActualCommandWithBounds() {
        val tool = ChatBlock.Tool("1", "Edit", """{"file_path":"a.kt","old_string":"before","new_string":"after"}""")
        assertEquals("Edit · a.kt", toolCommand(tool)); assertEquals("-before\n+after", compactToolDiff(tool))
        assertNull(compactToolDiff(ChatBlock.Tool("2", "Read")))
        val long = tool.copy(diff = (1..40).joinToString("\n") { "+line" })
        assertEquals(17, compactToolDiff(long)!!.lines().size)
    }
    @Test fun explicitDiffSurvivesLiveReductionAndHistoryMapping() {
        val id = "0".repeat(32)
        val event = StreamEvent(EventId(id, 1), "tool_call", buildJsonObject { put("id", "t"); put("name", "Edit"); put("diff", "+new") })
        val state = TurnReducer.reduce(TurnState("c", id), event, 100)
        assertEquals("+new", (state.blocks.first() as ChatBlock.Tool).diff)
        val history = historyBlocks(wireJson.parseToJsonElement("""{"blocks":[{"type":"tool","id":"t","name":"Edit","result":"ok","diff":"+new","duration_ms":123}]}""").jsonObject)
        assertEquals("+new", (history.first() as ChatBlock.Tool).diff); assertEquals(123L, (history.first() as ChatBlock.Tool).durationMs)
    }
}
