package com.karewinkcloud.agentweb.client.ui

import com.karewinkcloud.agentweb.client.core.*
import com.karewinkcloud.agentweb.client.data.Connection
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class TurnChromeTest {
    private fun labels(language: String): FooterLabels {
        val folder = if (language == "zh") "values-zh" else "values"
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/$folder/strings.xml"))
        val strings = document.getElementsByTagName("string")
        fun value(name: String): String = (0 until strings.length).map { strings.item(it) }
            .first { it.attributes.getNamedItem("name").nodeValue == name }.textContent
        return FooterLabels(value("turn_input"), value("turn_output"), value("turn_steps"))
    }

    @Test fun sizeChipsAreShortAndUpscaledSizesAreGrouped() {
        assertEquals("4K · 16:9", sizeChipLabel("16:9 4K · 3840×2160"))
        assertEquals("1:1 · 1024", sizeChipLabel("1:1 · 1024×1024"))
        assertEquals("9:16 · 1664", sizeChipLabel("9:16 · 928×1664"))
        assertEquals("1024x1024", sizeChipLabel("1024x1024"))
        assertTrue(sizeIsUpscaled("9:16 1080p · 1080×1920"))
        assertFalse(sizeIsUpscaled("4:3 · 1472×1104"))
    }
    @Test fun composerModelChipListsOnlyNonDefaultSettings() {
        assertEquals("DS V4.1 Flash", composerModelLabel("DS V4.1 Flash", null, null))
        assertEquals("Opus 5.5 · 高 · 只读规划", composerModelLabel("Opus 5.5", "高", "只读规划"))
    }
    @Test fun footerUsesRealChineseResourcesAndUsageDuration() {
        assertEquals("18s · 输入 19.5k · 输出 752 · 3 步", turnFooter(TurnUsage(inputTokens = 19524, outputTokens = 752, durationMs = 18000), 5000, 3, labels("zh")))
    }
    @Test fun footerUsesRealEnglishResourcesAndTraceFallback() {
        assertEquals("1:05 · Input 31.1k · Output 140 · 2 steps", turnFooter(TurnUsage(inputTokens = 31107, outputTokens = 140), 65000, 2, labels("en")))
    }
    @Test fun missingAndNegativePartsAreOmitted() {
        assertEquals("", turnFooter(null, null, null, labels("en")))
        assertEquals("", turnFooter(TurnUsage(), null, 0, labels("zh")))
        assertEquals("输出 0", turnFooter(TurnUsage(inputTokens = -1, outputTokens = 0), null, 0, labels("zh")))
        assertEquals("0s · Input 0", turnFooter(TurnUsage(inputTokens = 0, durationMs = 0), null, null, labels("en")))
    }
    @Test fun tokenSuffixBoundsAreStable() {
        assertEquals("999", compactTokenCount(999)); assertEquals("1k", compactTokenCount(1000))
        assertEquals("1.1k", compactTokenCount(1050)); assertEquals("19.5k", compactTokenCount(19524))
        assertEquals("1000k", compactTokenCount(1_000_000))
        assertEquals("9223372036854775.8k", compactTokenCount(Long.MAX_VALUE))
    }
    @Test fun elapsedClockUsesSecondsThenMinutes() {
        assertEquals("0s", elapsedLabel(-1)); assertEquals("12s", elapsedLabel(12999))
        assertEquals("59s", elapsedLabel(59999)); assertEquals("1:00", elapsedLabel(60000))
        assertEquals("1:05", elapsedLabel(65000)); assertEquals("120:01", elapsedLabel(7201000))
    }
    @Test fun phaseFollowsThoughtToolTextAndWaitingBetweenSteps() {
        val base = TurnState("c", "r")
        assertEquals(WorkingPhase.WAITING, workingStatus(base, Connection.LIVE)?.phase)
        val thought = ChatBlock.Thought("Considering", status = StepStatus.RUNNING)
        assertEquals(WorkingPhase.THINKING, workingStatus(base.copy(blocks = listOf(thought)), Connection.LIVE)?.phase)
        val tool = ChatBlock.Tool("t", "mcp__filesystem__read_file")
        assertEquals(WorkingStatus(WorkingPhase.TOOL, "read_file"), workingStatus(base.copy(blocks = listOf(thought, tool)), Connection.LIVE))
        assertEquals(WorkingPhase.THINKING, workingStatus(base.copy(blocks = listOf(tool.copy(status = StepStatus.COMPLETE))), Connection.LIVE)?.phase)
        assertEquals(WorkingPhase.WRITING, workingStatus(base.copy(blocks = listOf(ChatBlock.Text("Answer"))), Connection.LIVE)?.phase)
        assertEquals(WorkingPhase.THINKING, workingStatus(base.copy(blocks = listOf(ChatBlock.Text("Before"), thought)), Connection.LIVE)?.phase)
    }
    @Test fun connectionAndTerminalTruthOverrideContent() {
        val turn = TurnState("c", "r", blocks = listOf(ChatBlock.Text("Answer")))
        assertEquals(WorkingPhase.WAITING, workingStatus(turn, Connection.CONNECTING)?.phase)
        assertEquals(WorkingPhase.RECONNECTING, workingStatus(turn, Connection.RECONNECTING)?.phase)
        assertNull(workingStatus(turn.copy(done = true, revealing = true), Connection.RECONNECTING))
    }
    @Test fun concurrentToolWinsAndLongNamesStayCompact() {
        val tool = ChatBlock.Tool("t", "mcp__tools__" + "x".repeat(100))
        assertEquals(WorkingPhase.TOOL, workingStatus(TurnState("c", "r", blocks = listOf(tool, ChatBlock.Text("Progress"))), Connection.LIVE)?.phase)
        assertEquals(48, shortToolName(tool.name).length)
    }
    @Test fun traceDurationUsesSpanningTimingsOrHistoryThoughtDuration() {
        assertEquals(18000L, traceDurationMs(listOf(ChatBlock.Thought(startedAt = 1000, durationMs = 5000), ChatBlock.Tool("t", "read", startedAt = 7000, durationMs = 12000))))
        assertEquals(4700L, traceDurationMs(listOf(ChatBlock.Thought(durationMs = 4700))))
        assertNull(traceDurationMs(listOf(ChatBlock.Text("No metadata"))))
        assertNull(traceDurationMs(listOf(ChatBlock.Tool("t", "read"))))
    }
}
