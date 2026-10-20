package com.karewinkcloud.agentweb.client.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryOverviewTest {
    @Test fun theIndexAndTheLastTidyAreRead() {
        val overview = MemoryOverview.from(Json.parseToJsonElement("""{"entries":[{"title":"Lead/worker split","file":"lead-worker-split.md",
            "hook":"Claude reviews","type":"feedback","modified":1792400000}],"lastPass":{"status":"ok","at":1792390000,"summary":"Merged two",
            "changed":["MEMORY.md","a.md"],"removed":["old.md"],"files":27},"running":false}""").jsonObject)
        assertEquals(MemoryEntry("Lead/worker split", "lead-worker-split.md", "Claude reviews", "feedback", 1792400000), overview.entries.single())
        assertEquals(MemoryPass("ok", 1792390000, "Merged two", listOf("MEMORY.md", "a.md"), listOf("old.md"), 27), overview.lastPass)
        assertNull(MemoryOverview.from(Json.parseToJsonElement("""{"entries":[],"lastPass":null}""").jsonObject).lastPass)
    }
}
