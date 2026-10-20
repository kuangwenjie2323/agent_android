package com.karewinkcloud.agentweb.client.core

import org.junit.Assert.*
import org.junit.Test

class StreamPacerTest {
    @Test fun aResumedTurnKeepsItsTextAndRevealsOnlyWhatComesLater() {
        val pacer = StreamPacer()
        pacer.prime(500)
        pacer.offer(500, 0)
        assertEquals(500, pacer.frame(16))  // nothing on screen is typed out again
        assertFalse(pacer.pending)
        pacer.offer(520, 100)
        val next = pacer.frame(116)
        assertTrue(next in 500..520)
        assertEquals(520, pacer.frame(1_000))  // anything older than the age limit is shown whole
    }

    @Test fun aLargeMergedBacklogIsShownWithinTheAgeLimit() {
        val pacer = StreamPacer()
        pacer.offer(30_000, 0)  // a merged replay of a long turn
        var now = 0L
        var shown = 0
        while (now <= 900) { now += 16; shown = pacer.frame(now) }
        assertEquals(30_000, shown)
    }
}
