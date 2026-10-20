package com.karewinkcloud.agentweb.client.core

import org.junit.Assert.*
import org.junit.Test

class TurnHandoverTest {
    @Test fun aQuickReturnKeepsTheScreenStreamAndStartsNothing() {
        val handover = TurnHandover()
        assertTrue(handover.left(1_000))
        assertFalse(handover.left(1_200))          // onUserLeaveHint then onStop count once
        assertFalse(handover.due(5_999))          // still inside the grace
        assertFalse(handover.returned())          // nothing was handed over
        assertFalse(handover.due(60_000))         // the cancelled clock never fires
    }

    @Test fun theTurnIsHandedOverOnceAfterTheGrace() {
        val handover = TurnHandover()
        handover.left(1_000)
        assertFalse(handover.due(5_999))
        assertTrue(handover.due(6_000))
        assertFalse(handover.isHandedOver())      // not before the screen has let go
        assertTrue(handover.handOver())
        assertFalse(handover.due(7_000))          // a second timer tick keeps it handed over
        assertFalse(handover.handOver())
        assertTrue(handover.isHandedOver())
        assertTrue(handover.returned())           // coming back stops the follower
        assertFalse(handover.isHandedOver())
    }

    @Test fun theGraceIsCountedAgainAfterACanceledHandOver() {
        val handover = TurnHandover()
        handover.left(0)
        handover.returned()
        assertTrue(handover.left(100))
        assertFalse(handover.due(5_000))
        assertTrue(handover.due(5_100))
    }

    @Test fun oneRunIsNeverStreamedByTheScreenAndTheFollowerAtOnce() {
        val handover = TurnHandover()
        assertTrue(handover.screenStarted("chat", "run"))
        assertFalse(handover.beginFollower("chat", "run"))
        handover.screenStopped("chat", "run")
        assertTrue(handover.beginFollower("chat", "run"))
        assertFalse(handover.beginFollower("chat", "run"))   // never two followers for one run
        handover.releaseFollowers()
        assertTrue(handover.beginFollower("chat", "run"))
        handover.endFollower("chat", "run")
        assertTrue(handover.beginFollower("chat", "run"))
    }

    @Test fun theFurthestPositionWinsAndNeverMovesBack() {
        val handover = TurnHandover()
        handover.note("chat", "run", 12_607)
        handover.note("chat", "run", 26_257)   // the background follower read on
        assertEquals(26_257, handover.furthest("chat", "run"))
        handover.note("chat", "run", 20_000)   // a late snapshot from the screen
        assertEquals(26_257, handover.furthest("chat", "run"))
        assertEquals(0, handover.furthest("chat", "other-run"))
        handover.note("chat", "", 99)          // a run the stream has not named yet
        assertEquals(0, handover.furthest("chat", ""))
    }
}
