package com.karewinkcloud.agentweb.client.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WorkStepsTest {
    private fun tool(name: String, input: String, status: StepStatus = StepStatus.COMPLETE) = ChatBlock.Tool("t-$name", name, input, status = status)

    @Test fun stepsAreNamedByWhatTheyDo() {
        assertEquals(StepSummary(StepKind.COMMAND, "Fetch robot-c's poll results"),
            stepSummary(tool("Bash", """{"command":"grokbot-ask check robot-c","description":"Fetch robot-c's poll results"}""")))
        assertEquals(StepSummary(StepKind.COMMAND, "ls -la"), stepSummary(tool("shell", """{"command":"ls -la\ncat x"}""")))
        assertEquals(StepSummary(StepKind.READ, "heal.sh"), stepSummary(tool("Read", """{"file_path":"/home/k/heal.sh"}""")))
        assertEquals(StepSummary(StepKind.EDIT, "heal.sh"), stepSummary(tool("Edit", """{"file_path":"/a/heal.sh","old_string":"a","new_string":"b"}""")))
        assertEquals(StepSummary(StepKind.WEB_FETCH, "docs.example.com"), stepSummary(tool("WebFetch", """{"url":"https://docs.example.com/x"}""")))
        assertEquals(StepKind.MCP, stepSummary(tool("mcp__claude_ai_Cloudflare__search_docs", """{"query":"r2"}""")).kind)
        assertEquals("search_docs", stepSummary(tool("mcp__claude_ai_Cloudflare__search_docs", "{}")).mcpTool)
        assertEquals("web_extract", stepSummary(tool("mcp:crawlflare/web_extract", """{"url":"https://a.b"}""")).mcpTool)
        assertEquals(StepSummary(StepKind.WRITE, "new.conf"),
            stepSummary(tool("file_change", """{"changes":[{"path":"/x/new.conf","kind":{"type":"add"},"diff":"a\nb"}]}""")))
        assertEquals(StepSummary(StepKind.EDIT, "a.kt +1"),
            stepSummary(tool("file_change", """{"changes":[{"path":"/x/a.kt","kind":{"type":"update"},"diff":"@@ -1 +1 @@\n-a\n+b"},{"path":"/x/b.kt","kind":{"type":"add"},"diff":"c"}]}""")))
        assertEquals(StepKind.OTHER, stepSummary(tool("Mystery", "not json")).kind)
    }

    @Test fun anEditShowsOnlyTheLinesThatChanged() {
        val rows = lineDiff("one\ntwo\nthree\n", "one\n2\nthree\nfour")
        assertEquals(listOf(DiffKind.CONTEXT to "one", DiffKind.REMOVE to "two", DiffKind.ADD to "2", DiffKind.CONTEXT to "three", DiffKind.ADD to "four"),
            rows.map { it.kind to it.text })
        val diff = stepDiffs(tool("Edit", """{"file_path":"/a/b.py","old_string":"x = 1\ny = 2","new_string":"x = 1\ny = 3"}""")).single()
        assertEquals(Triple("/a/b.py", 1, 1), Triple(diff.path, diff.added, diff.removed))
    }

    @Test fun unifiedDiffsKeepTheirLineNumbers() {
        val rows = unifiedRows("--- a/x\n+++ b/x\n@@ -66,3 +66,4 @@\n     location {\n-        old;\n+        new;\n+        more;\n         tail;\n")
        assertEquals(DiffKind.HEADER, rows[0].kind)
        assertEquals(DiffRow(DiffKind.CONTEXT, "    location {", 66, 66), rows[1])
        assertEquals(DiffRow(DiffKind.REMOVE, "        old;", oldLine = 67), rows[2])
        assertEquals(DiffRow(DiffKind.ADD, "        new;", newLine = 67), rows[3])
        assertEquals(DiffRow(DiffKind.ADD, "        more;", newLine = 68), rows[4])
        assertEquals(DiffRow(DiffKind.CONTEXT, "        tail;", 68, 69), rows[5])
    }

    @Test fun newFilesAndCodexChangesBecomeFileDiffs() {
        val written = stepDiffs(tool("Write", """{"file_path":"/a/new.md","content":"# Title\nbody\n"}""")).single()
        assertEquals(listOf(1, 2), written.rows.map { it.newLine })
        assertEquals(2, written.added)
        val codex = stepDiffs(tool("file_change", """{"changes":[{"path":"/x/a.conf","kind":{"type":"update"},"diff":"@@ -10,2 +10,2 @@\n keep\n-old\n+new\n"},{"path":"/x/gone","kind":{"type":"delete"},"diff":""}]}"""))
        assertEquals(listOf("/x/a.conf" to (1 to 1), "/x/gone" to (0 to 0)), codex.map { it.path to (it.added to it.removed) })
        assertTrue(stepDiffs(tool("Bash", """{"command":"ls"}""")).isEmpty())
    }

    @Test fun aTurnReadsInOrderWithWorkBetweenItsText() {
        val think = ChatBlock.Thought("plan", status = StepStatus.COMPLETE)
        val run = tool("Bash", """{"command":"ls"}""")
        val ask = tool("mcp__agentweb__ask_user", """{"question":"?"}""")
        val text = ChatBlock.Text("Looking at the files.")
        val pieces = turnPieces(listOf(think, run, ChatBlock.Text(""), text, ask, tool("Read", """{"file_path":"/a"}"""), ChatBlock.Text("Done.")))
        assertEquals(4, pieces.size)
        assertEquals(TurnPiece.Work(listOf(think, run), 0), pieces[0])
        assertEquals(TurnPiece.Single(text, 3), pieces[1])
        assertTrue((pieces[2] as TurnPiece.Work).steps.single() is ChatBlock.Tool)  // ask_user is not a step
        assertEquals(TurnPiece.Single(ChatBlock.Text("Done."), 6), pieces[3])
    }

    @Test fun anEmptyInstantThoughtIsLeftOut() {
        val empty = ChatBlock.Thought("", status = StepStatus.COMPLETE, durationMs = 0)
        val run = tool("Bash", """{"command":"ls"}""")
        assertEquals(listOf(TurnPiece.Work(listOf(run), 1)), turnPieces(listOf(empty, run, empty.copy(durationMs = null))))
        // Still thinking, or thought for a while: shown.
        val thinking = ChatBlock.Thought("", status = StepStatus.RUNNING)
        assertEquals(listOf(TurnPiece.Work(listOf(thinking), 0)), turnPieces(listOf(thinking)))
        val long = ChatBlock.Thought("", status = StepStatus.COMPLETE, durationMs = 2500)
        assertEquals(listOf(TurnPiece.Work(listOf(long), 0)), turnPieces(listOf(long)))
    }

    @Test fun anOutlineThoughtThatLeftWordsBehindStaysInTheTimeline() {
        val kept = ChatBlock.Thought("", status = StepStatus.COMPLETE, durationMs = 600, chars = 140)
        assertEquals(listOf(TurnPiece.Work(listOf(kept), 0)), turnPieces(listOf(kept)))
        // No words to fetch: the same instant thought, empty on the server too, stays out.
        assertTrue(turnPieces(listOf(kept.copy(chars = 0))).isEmpty())
    }

    /** What the folded row reads from a turn: counts by kind, diff sizes, files and thinking time. */
    private data class TurnShape(val kinds: List<StepKind>, val added: Int, val removed: Int, val files: List<String>,
        val failed: Int, val thoughtMs: Long)
    private fun shape(blocks: List<ChatBlock>): TurnShape {
        val tools = blocks.filterIsInstance<ChatBlock.Tool>()
        val diffs = tools.flatMap(::stepDiffs)
        return TurnShape(tools.map { stepSummary(it).kind }, diffs.sumOf { it.added }, diffs.sumOf { it.removed },
            diffs.map { it.path }.filter(String::isNotBlank).distinct().sorted(),
            tools.count { it.status == StepStatus.ERROR },
            blocks.filterIsInstance<ChatBlock.Thought>().sumOf { it.durationMs ?: 0L })
    }

    private fun turnMessage() = buildJsonObject {
        put("id", "2"); put("role", "assistant")
        putJsonArray("blocks") {
            addJsonObject { put("type", "thinking"); put("content", "Look at the workspace first"); put("duration", 2.2) }
            addJsonObject {
                put("type", "tool"); put("id", "b1"); put("name", "Bash")
                putJsonObject("input") {
                    put("command", "find . -maxdepth 3 | sort | head -200 " + "x".repeat(300)); put("description", "List the workspace")
                }
                put("result", "a.txt\nb.txt\nc.txt\n".repeat(50))
            }
            addJsonObject {
                put("type", "tool"); put("id", "e1"); put("name", "Edit")
                putJsonObject("input") { put("file_path", "/a/main.py"); put("old_string", "x = 1\ny = 2"); put("new_string", "x = 1\ny = 3") }
                put("result", "Edited main.py")
            }
            addJsonObject {
                put("type", "tool"); put("id", "w1"); put("name", "Write")
                putJsonObject("input") { put("file_path", "/a/new.md"); put("content", "# Title\nbody\n") }
                put("result", "Wrote new.md")
            }
            addJsonObject {
                put("type", "tool"); put("id", "f1"); put("name", "Bash")
                putJsonObject("input") { put("command", "false") }
                put("result", "Exit code 1"); put("is_error", true)
            }
            addJsonObject {
                put("type", "task_notice"); put("task_id", "bz1"); put("tool_id", "b2"); put("status", "completed")
                put("description", "Sample sockets for 15 minutes " + "y".repeat(300))
            }
            addJsonObject { put("type", "text"); put("content", "Done.") }
        }
    }

    /** The server's outline rules for a history page: the summary's inputs stay, the long text moves to sizes. */
    private fun outline(message: JsonObject): JsonObject {
        val bashLike = setOf("bash", "shell", "exec", "exec_command", "local_shell", "run_command", "command_execution")
        val blocks = message.array("blocks").map { element ->
            val block = element as JsonObject
            when (block.string("type")) {
                "thinking" -> JsonObject(block + ("content" to JsonPrimitive("")) +
                    ("chars" to JsonPrimitive(block.string("content").orEmpty().length)))
                "tool" -> {
                    val input = block.obj("input")?.let { raw ->
                        val command = raw.string("command")
                        if (block.string("name").orEmpty().lowercase() in bashLike && command != null)
                            JsonObject(raw + ("command" to JsonPrimitive(command.take(160)))) else raw
                    }
                    val keepResult = block.boolean("is_error") == true || !block.string("diff").isNullOrBlank()
                    val sizes: Map<String, JsonElement> = if (keepResult) emptyMap() else mapOf(
                        "result" to JsonPrimitive(""), "resultChars" to JsonPrimitive(block.string("result").orEmpty().length))
                    JsonObject(block + mapOf("input" to (input ?: JsonNull)) + sizes)
                }
                "task_notice" -> JsonObject(block.mapValues { (key, value) ->
                    if (key in setOf("description", "content") && value is JsonPrimitive) JsonPrimitive(value.content.take(160)) else value
                })
                else -> block
            }
        }
        return JsonObject(message + mapOf("outline" to JsonPrimitive(true), "blocks" to JsonArray(blocks)))
    }

    @Test fun anOutlineTurnSumsUpLikeTheFullOne() {
        val full = ChatMessage.from(turnMessage())
        val reduced = ChatMessage.from(outline(turnMessage()))
        assertFalse(full.outline); assertTrue(reduced.outline)
        // Counts, edits +/-, files and thinking seconds read the same on either page.
        assertEquals(shape(full.blocks), shape(reduced.blocks))
        assertEquals(turnPieces(full.blocks).size, turnPieces(reduced.blocks).size)
        // The long text really was left out, with its size kept for the summary and the fetch.
        val thought = reduced.blocks.filterIsInstance<ChatBlock.Thought>().first()
        assertEquals("", thought.content); assertEquals(27, thought.chars)
        val bash = reduced.blocks.filterIsInstance<ChatBlock.Tool>().first()
        assertEquals("", bash.result); assertTrue(bash.resultChars!! > 0)
        assertEquals("List the workspace", stepSummary(bash).target)
    }

    @Test fun backgroundTasksAreRecognisedAndNamed() {
        val bash = tool("Bash", """{"command":"sleep 900","description":"Sample sockets for 15 minutes","run_in_background":true}""")
        assertTrue(isBackground(bash))
        assertFalse(isBackground(tool("Bash", """{"command":"ls"}""")))
        val started = bash.copy(task = BackgroundTask("bz1", description = ""))
        assertEquals(listOf(started to started.task!!), backgroundTasks(listOf(ChatBlock.Text("x"), started)))
        assertEquals("Sample sockets for 15 minutes", taskName(started, started.task!!))  // falls back to the step's own words
        // Checking on a task or stopping it refers to the task by id; the timeline shows its name.
        assertEquals(StepSummary(StepKind.TASK_OUTPUT, "bz1", taskRef = "bz1"),
            stepSummary(tool("Read", """{"file_path":"/tmp/claude-1000/-proj/s1/tasks/bz1.output"}""")))
        assertEquals(StepSummary(StepKind.TASK_STOP, "bz1", taskRef = "bz1"), stepSummary(tool("TaskStop", """{"task_id":"bz1"}""")))
        assertEquals(StepKind.TASK_OUTPUT, stepSummary(tool("BashOutput", """{"bash_id":"b2"}""")).kind)
        assertEquals(StepKind.TODO, stepSummary(tool("TaskUpdate", """{"subject":"Ship it"}""")).kind)
        assertEquals(StepKind.READ, stepSummary(tool("Read", """{"file_path":"/home/k/tasks.output.md"}""")).kind)
    }
}
