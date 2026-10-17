package com.karewinkcloud.agentweb.client.core

import org.junit.Assert.*
import org.junit.Test

class ChatFilesTest {
    private fun tool(name: String, input: String) = ChatBlock.Tool("t-$name-${input.hashCode()}", name, input, status = StepStatus.COMPLETE)

    @Test fun aNamedLineOpensThere() {
        assertEquals(FileRef("src/app.kt", 42), fileRef("src/app.kt:42"))
        assertEquals(FileRef("src/app.kt", 42), fileRef("src/app.kt:42:7"))
        assertEquals(FileRef("docs/a.md", 10), fileRef("docs/a.md#L10-L20"))
        assertEquals(FileRef("/home/me/report.md"), fileRef(" /home/me/report.md "))
        assertNull(fileRef("  "))
    }

    @Test fun codeThatNamesAFileAndCodeThatDoesNot() {
        for (code in listOf("README.md", "src/app.kt:42", "build.gradle.kts", "/etc/nginx/site.conf", "~/notes/today.md",
                "/opt/tool/run.anything", "报告.md", "file:///home/me/a.md"))
            assertNotNull(code, namedFile(code))
        for (code in listOf("v1.2.3", "api.deepseek.com", "*.md", ".md", "npm run build", "https://example.com/a.md",
                "obj.method()", "a.b", "src/", "\$HOME/a.md"))
            assertNull(code, namedFile(code))
    }

    @Test fun linksToFilesButNeverToTheWebOrScripts() {
        assertEquals(FileRef("docs/My File.md"), linkedFile("docs/My%20File.md"))
        assertEquals(FileRef("src/a.kt", 3), linkedFile("src/a.kt:3"))
        assertEquals(FileRef("a+b.md"), linkedFile("a+b.md"))
        assertEquals(FileRef("file:///home/me/a.md"), linkedFile("file:///home/me/a.md"))
        for (target in listOf("https://x.com/a.md", "mailto:a@b.c", "javascript:alert(1)", "intent://x", "#section", "//cdn.x/a.js", ""))
            assertNull(target, linkedFile(target))
    }

    @Test fun fullPathsInRunningText() {
        fun paths(text: String) = PLAIN_PATH.findAll(text).map { it.value }.toList()
        assertEquals(listOf("/home/me/报告.md"), paths("保存在/home/me/报告.md里了"))
        assertEquals(listOf("/srv/app/main.py:12"), paths("see /srv/app/main.py:12, then"))
        assertEquals(listOf("~/notes/a.txt"), paths("(~/notes/a.txt)"))
        assertEquals(listOf("/tmp/out.markdown"), paths("wrote /tmp/out.markdown."))
        assertEquals(emptyList<String>(), paths("https://x.com/a.md and/or 1/2.pdf, x.com/b.md, /usr/bin/env, /a/b.mdx2"))
    }

    @Test fun filesTheChatChangedNewestFirstWithTheirCounts() {
        val turns = listOf(
            listOf(tool("Write", """{"file_path":"/w/a.md","content":"one\ntwo"}"""),
                tool("Edit", """{"file_path":"/w/b.kt","old_string":"x","new_string":"y"}""")),
            listOf(tool("Read", """{"file_path":"/w/c.md"}"""),
                tool("Edit", """{"file_path":"/w/a.md","old_string":"two","new_string":"2"}"""),
                tool("file_change", """{"changes":[{"path":"/w/old.txt","kind":{"type":"delete"},"diff":""}]}""")))
        val files = changedFiles(turns)
        assertEquals(listOf("/w/old.txt", "/w/a.md", "/w/b.kt"), files.map { it.path })
        assertEquals(ChangedFile("/w/a.md", added = 3, removed = 1, created = true, deleted = false), files[1])
        assertEquals(ChangedFile("/w/b.kt", added = 1, removed = 1, created = false, deleted = false), files[2])
        assertTrue(files[0].deleted)
    }

    @Test fun aReadStepOpensItsFileAtThePartItRead() {
        assertEquals(FileRef("/w/c.md", 120), stepFileRef(tool("Read", """{"file_path":"/w/c.md","offset":120,"limit":40}""")))
        assertEquals(FileRef("/w/c.md"), stepFileRef(tool("Read", """{"file_path":"/w/c.md"}""")))
        assertEquals(FileRef("/w/shot.png"), stepFileRef(tool("view_image", """{"path":"/w/shot.png"}""")))
        assertNull(stepFileRef(tool("Read", """{"file_path":"relative.md"}""")))
        assertNull(stepFileRef(tool("Bash", """{"command":"cat /w/c.md"}""")))
        // A background task's output file is the task's, shown by the task list.
        assertNull(stepFileRef(tool("Read", """{"file_path":"/tmp/claude/tasks/b1.output"}""")))
    }

    @Test fun frontMatterIsShownAsCodeNotAsAHeading() {
        assertEquals("```yaml\nname: a\ntype: b\n```\n\n# Title\nbody",
            markdownForPreview("---\nname: a\ntype: b\n---\n# Title\nbody"))
        assertEquals("```yaml\nname: a\n```\n\n", markdownForPreview("---\nname: a\n---"))
        // A rule further down, or a file that only starts with one, is left alone.
        assertEquals("# Title\n---\nx", markdownForPreview("# Title\n---\nx"))
        assertEquals("---\nno end", markdownForPreview("---\nno end"))
    }

    @Test fun howAFileIsShown() {
        fun shown(name: String, kind: String = "text") = filePreview(FileContent("/w/$name", kind, "text/plain", ByteArray(0), 0, 0, false))
        assertEquals(FilePreview.MARKDOWN, shown("README.md"))
        assertEquals(FilePreview.WEB, shown("index.HTML"))
        assertEquals(FilePreview.WEB, shown("logo.svg"))
        assertEquals(FilePreview.TEXT, shown("main.py"))
        assertEquals(FilePreview.IMAGE, shown("a.png", "image"))
        assertEquals(FilePreview.PDF, shown("a.pdf", "pdf"))
    }
}
