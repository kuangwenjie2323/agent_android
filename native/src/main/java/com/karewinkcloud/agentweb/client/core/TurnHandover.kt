package com.karewinkcloud.agentweb.client.core

/**
 * Who streams each running turn, how far each stream got, and when a hidden screen hands its turn to
 * the background follower. MainActivity, AgentViewModel and AgentWorkService share one instance in the
 * app process, so one turn is never streamed twice and is never replayed from its first event after
 * an app switch. Times are wall-clock milliseconds supplied by the caller, which keeps the decisions
 * testable without Android.
 */
open class TurnHandover(private val graceMillis: Long = GRACE_MILLIS) {
    private enum class Side { SCREEN, BACKGROUND }
    private data class Key(val conversationId: String, val runId: String)
    private data class Place(var lastSequence: Long)

    private var awaySince: Long? = null
    private var handedOver = false
    private val owners = mutableMapOf<Key, Side>()
    // Only the newest runs matter; the cap keeps the map from growing through a long session.
    private val places = object : java.util.LinkedHashMap<Key, Place>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Place>) = size > 32
    }

    /** The screen left at [now]; false when it was already away (onUserLeaveHint then onStop). */
    @Synchronized fun left(now: Long): Boolean {
        if (awaySince != null || handedOver) return false
        awaySince = now
        return true
    }

    /** The screen has stayed away for the whole grace: it may stop its stream and hand the turn over. */
    @Synchronized fun due(now: Long): Boolean {
        val since = awaySince ?: return false
        return !handedOver && now - since >= graceMillis
    }

    /**
     * The screen's stream has stopped: the waiting follower may start now. A separate step from [due], so the
     * follower never sees the hand-over before the screen has let go of the run.
     */
    @Synchronized fun handOver(): Boolean {
        if (awaySince == null || handedOver) return false
        awaySince = null
        handedOver = true
        return true
    }

    /** The screen is back and cancels a pending hand-over; true when a follower owns the turn and must stop. */
    @Synchronized fun returned(): Boolean {
        awaySince = null
        val was = handedOver
        handedOver = false
        return was
    }

    @Synchronized fun isHandedOver() = handedOver

    /** The screen's stream for [runId] took the run over; false when the background follower still held it. */
    @Synchronized fun screenStarted(conversationId: String, runId: String): Boolean {
        val key = Key(conversationId, runId)
        val displaced = owners[key] == Side.BACKGROUND
        owners[key] = Side.SCREEN
        return !displaced
    }

    @Synchronized fun screenStopped(conversationId: String, runId: String) {
        val key = Key(conversationId, runId)
        if (owners[key] == Side.SCREEN) owners.remove(key)
    }

    /** Starts the background follower for [runId]; false while the screen or another follower owns that run. */
    @Synchronized fun beginFollower(conversationId: String, runId: String): Boolean {
        val key = Key(conversationId, runId)
        if (owners.containsKey(key)) return false
        owners[key] = Side.BACKGROUND
        return true
    }

    @Synchronized fun endFollower(conversationId: String, runId: String) {
        val key = Key(conversationId, runId)
        if (owners[key] == Side.BACKGROUND) owners.remove(key)
    }

    /** The service is going away: no follower owns any turn any more. */
    @Synchronized fun releaseFollowers() {
        owners.entries.removeAll { it.value == Side.BACKGROUND }
    }

    /** Remembers the furthest event any stream of this run has read. */
    @Synchronized fun note(conversationId: String, runId: String, lastSequence: Long) {
        if (runId.isBlank()) return
        val key = Key(conversationId, runId)
        val place = places[key]
        if (place == null) places[key] = Place(lastSequence)
        else if (lastSequence > place.lastSequence) place.lastSequence = lastSequence
    }

    /** The last event any stream of this run has read; 0 when none has. */
    @Synchronized fun furthest(conversationId: String, runId: String): Long =
        places[Key(conversationId, runId)]?.lastSequence ?: 0

    companion object {
        /** How long the screen may be away before its running turn goes to the background follower. */
        const val GRACE_MILLIS = 5_000L
    }
}

/** The hand-over ledger of the app process. */
object TurnStreams : TurnHandover()
