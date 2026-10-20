package com.karewinkcloud.agentweb.client.data

import org.junit.Assert.*
import org.junit.Test

class StrictReportsTest {
    private val app = "com.karewinkcloud.agentweb.client"
    private fun frame(className: String, method: String, file: String = "Source.kt", line: Int = 1) =
        StackTraceElement(className, method, file, line)

    @Test fun aViolationIsWrittenOncePerCallSiteWithItsTypeAndTopAppFrame() {
        val written = mutableListOf<Pair<String, String>>()
        val reports = StrictReports(clock = { 0 }, log = { type, at -> written += type to at })
        // StrictMode's listener and the JDK's call sit above the app frame that caused the read.
        val frames = arrayOf(
            frame("$app.data.StrictModeChecksKt\$installStrictMode\$1", "onThreadViolation"),
            frame("android.os.StrictMode\$AndroidBlockGuardPolicy", "onReadFromDisk"),
            frame("android.os.BlockGuardOs", "stat"),
            frame("java.io.File", "exists"),
            frame("$app.data.SettingsStore", "<init>", "SettingsStore.kt", 23),
            frame("$app.MainActivity", "onCreate", "MainActivity.kt", 49),
        )
        assertTrue(reports.report("disk read", frames))
        assertFalse(reports.report("disk read", frames))
        assertEquals(listOf("disk read" to "data.SettingsStore.<init>(SettingsStore.kt:23)"), written)
        // Another kind of violation at the same place is its own finding.
        assertTrue(reports.report("disk write", frames))
        assertEquals(2, written.size)
    }

    @Test fun aCallSiteWithoutAnAppFrameIsReportedAsUnknown() {
        val written = mutableListOf<String>()
        val reports = StrictReports(clock = { 0 }, log = { _, at -> written += at })
        assertTrue(reports.report("network", arrayOf(frame("okhttp3.RealCall", "execute"))))
        assertEquals(listOf("unknown"), written)
    }

    @Test fun linesAreRateLimitedPerWindowAndSuppressedSitesWaitForTheNextWindow() {
        val written = mutableListOf<String>()
        var now = 1_000L
        val reports = StrictReports(clock = { now }, maxPerWindow = 2, windowMillis = 60_000,
            log = { _, at -> written += at })
        fun site(index: Int) = arrayOf(frame("$app.Site$index", "run", "Sites.kt", index))

        assertTrue(reports.report("disk read", site(0)))
        assertTrue(reports.report("disk read", site(1)))
        assertFalse(reports.report("disk read", site(2)))  // the window is full
        assertEquals(2, written.size)

        now += 60_000
        assertTrue(reports.report("disk read", site(2)))  // a site dropped before is not lost
        assertFalse(reports.report("disk read", site(0)))  // a site already written never repeats
        assertEquals(3, written.size)
    }
}
