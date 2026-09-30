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
}
