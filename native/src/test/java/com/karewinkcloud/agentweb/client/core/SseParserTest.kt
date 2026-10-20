package com.karewinkcloud.agentweb.client.core

import org.junit.Assert.*
import org.junit.Test

internal fun fixture(path: String): String = requireNotNull(SseParserTest::class.java.classLoader?.getResourceAsStream(path)) {
    "Missing conformance fixture $path"
}.bufferedReader().use { it.readText() }

class SseParserTest {
    @Test fun goldenHeartbeatFixtureParsesOneCharacterAtATime() {
        val parser = SseParser()
        val frames = fixture("f-05/events.sse").flatMap { parser.feed(it.toString()) }
        assertEquals(2, frames.size)
        assertEquals("run-fixture-f05:1", frames[0].id)
        assertEquals("text", StreamEvent.from(frames[0]).type)
        assertEquals("done", StreamEvent.from(frames[1]).type)
    }

    @Test fun crlfSplitAcrossChunksAndMultilineData() {
        val parser = SseParser()
        assertTrue(parser.feed("\uFEFF: ping\r").isEmpty())
        val frames = parser.feed("\n\r\nid: run:1\r\ndata: {\r\ndata: \"type\":\"text\",\"seq\":1,\"content\":\"你好\"}\r\n\r\n")
        assertEquals("你好", StreamEvent.from(frames.single()).payload.string("content"))
    }

    @Test fun incompleteFrameIsNeverDispatchedOnEof() {
        assertTrue(SseParser().feed("id: run:1\ndata: {\"type\":\"done\",\"seq\":1}\n").isEmpty())
    }

    @Test fun commentsAndRetryFieldsAreNotEvents() {
        assertTrue(SseParser().feed(": keepalive\nretry: 1000\n\n").isEmpty())
    }

    @Test fun idIsRequiredOnEveryContractEvent() {
        val frames = SseParser().feed("id: run:1\ndata: {\"type\":\"text\",\"seq\":1,\"content\":\"a\"}\n\ndata: {\"type\":\"text\",\"seq\":2,\"content\":\"b\"}\n\n")
        assertThrows(StreamProtocolException::class.java) { StreamEvent.from(frames[1]) }
    }

    @Test fun mismatchingSequenceIsRejected() {
        assertThrows(StreamProtocolException::class.java) {
            StreamEvent.from(SseFrame("run:1", "{\"type\":\"text\",\"seq\":2,\"content\":\"hello\"}"))
        }
    }

    @Test fun malformedJsonIsRejected() {
        assertThrows(StreamProtocolException::class.java) { StreamEvent.from(SseFrame("run:1", "[broken")) }
    }

    @Test fun frameSizeIsBounded() {
        assertThrows(StreamProtocolException::class.java) { SseParser(32).feed("data: " + "a".repeat(40)) }
    }

    @Test fun cursorParsingMatchesAsciiContract() {
        assertEquals(EventId("run", 0), EventId.parse("run:0"))
        assertNull(EventId.parse("run:٣"))
        assertNull(EventId.parse("run:-1"))
        assertNull(EventId.parse("run:1234567890123"))
        assertNull(EventId.parse(":1"))
        assertNull(EventId.parse("run:1 "))
    }

    @Test fun aMergedReplayEventCarriesTheSequenceItsRunEndsAt() {
        val text = StreamEvent.from(SseFrame("run:4", """{"type":"text","seq":4,"content":"Hello world","through":6}"""))
        assertEquals(6L, text.through)
        assertEquals("Hello world", text.payload.string("content"))
        val thinking = StreamEvent.from(SseFrame("run:8", """{"type":"thinking","seq":8,"content":"hm","through":9}"""))
        assertEquals(9L, thinking.through)
        // A run of one event is sent unchanged; through == seq is still a valid range.
        assertEquals(4L, StreamEvent.from(SseFrame("run:4", """{"type":"text","seq":4,"content":"a","through":4}""")).through)
        assertNull(StreamEvent.from(SseFrame("run:7", """{"type":"text","seq":7,"content":"a"}""")).through)
    }

    @Test fun invalidThroughSequencesAreRejected() {
        val bad = listOf(
            """{"type":"text","seq":4,"content":"a","through":3}""",
            """{"type":"text","seq":4,"content":"a","through":"6"}""",
            """{"type":"text","seq":4,"content":"a","through":6.5}""",
            """{"type":"text","seq":4,"content":"a","through":null}""",
            """{"type":"done","seq":4,"conversationId":"c","through":5}""",
            """{"type":"tool_call","seq":4,"id":"t","name":"Read","input":{},"through":5}""",
        )
        for (data in bad) {
            assertThrows(data, StreamProtocolException::class.java) { StreamEvent.from(SseFrame("run:4", data)) }
        }
    }
}
