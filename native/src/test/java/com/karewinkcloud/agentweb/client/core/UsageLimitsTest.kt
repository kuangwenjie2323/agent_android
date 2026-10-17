package com.karewinkcloud.agentweb.client.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class UsageLimitsTest {
    @Test fun theClaudeAgentCarriesItsUsageWindows() {
        val agent = Agent.from(wireJson.parseToJsonElement("""{"id":"claude","name":"Claude","models":["opus"],"model":"opus",
            "available":true,"limits":{"status":"allowed","window":"five_hour","resetsAt":1792003200,"extraUsage":false,
            "updatedAt":1792000000.5,"windows":{"five_hour":{"used":0.84,"resetsAt":1792003200},"seven_day":{"used":0.73,"resetsAt":1792213200}}}}""").jsonObject)
        val limits = agent.limits!!
        assertEquals(UsageLimits.LimitWindow(0.84, 1792003200), limits.windows["five_hour"])
        assertEquals(0.84, limits.highest, 0.0001)
        assertFalse(limits.exhausted)
        assertEquals(1792000000L, limits.updatedAt)
    }

    @Test fun aRefusedTurnMarksTheSubscriptionUsedUp() {
        val limits = UsageLimits.from(buildJsonObject { put("status", "rejected"); put("resetsAt", 1792003200) })!!
        assertTrue(limits.exhausted)
        assertEquals(1792003200L, limits.resetsAt)
        assertNull(UsageLimits.from(buildJsonObject { put("windows", buildJsonObject { }) }))
        assertNull(Agent.from(buildJsonObject { put("id", "deepseek"); put("name", "DeepSeek") }).limits)
    }
}
