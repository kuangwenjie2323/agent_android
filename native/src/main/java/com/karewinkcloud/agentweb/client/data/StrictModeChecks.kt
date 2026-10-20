package com.karewinkcloud.agentweb.client.data

import android.os.Build
import android.os.StrictMode
import android.os.SystemClock
import android.os.strictmode.DiskReadViolation
import android.os.strictmode.DiskWriteViolation
import android.os.strictmode.LeakedClosableViolation
import android.os.strictmode.NetworkViolation
import android.os.strictmode.Violation
import java.util.concurrent.Executor

private const val APP_PACKAGE = "com.karewinkcloud.agentweb.client"

/** Runs the listener right where the violation happened; the violation carries that thread's stack. */
private val onTheSpot = Executor { it.run() }

/**
 * Turns StrictMode violations into app-log lines through `Diagnostics.warn("strict", ...)`: one per
 * violation type and app call site, and at most [maxPerWindow] lines per [windowMillis], so a loop on
 * the main thread cannot flood the log. This alone is the report: nothing here is ever fatal.
 */
internal class StrictReports(
    private val clock: () -> Long = SystemClock::uptimeMillis,
    private val maxPerWindow: Int = 8,
    private val windowMillis: Long = 60_000,
    private val packageName: String = APP_PACKAGE,
    private val log: (type: String, at: String) -> Unit = { type, at -> Diagnostics.warn("strict", type, "at" to at) },
) {
    private val reported = HashSet<String>()
    private var windowStart: Long? = null
    private var inWindow = 0

    /** True when the line was written: the call site is new and the window still has room. */
    fun report(type: String, frames: Array<StackTraceElement>): Boolean {
        val at = topAppFrame(frames, packageName) ?: "unknown"
        val key = "$type at $at"
        synchronized(this) {
            if (key in reported) return false
            val now = clock()
            val start = windowStart
            if (start == null || now - start >= windowMillis) { windowStart = now; inWindow = 0 }
            if (inWindow >= maxPerWindow) return false
            reported += key
            inWindow++
        }
        log(type, at)
        return true
    }
}

/**
 * The app frame nearest the I/O: StrictMode's own policy frames sit above it, then the JDK's file or
 * socket call. "data.SettingsStore.<init>(SettingsStore.kt:23)" is enough to find the place.
 */
internal fun topAppFrame(frames: Array<StackTraceElement>, packageName: String = APP_PACKAGE): String? {
    val policy = frames.indexOfLast { it.className.startsWith("android.os.StrictMode") }
    return frames.asSequence().drop(policy + 1)
        .firstOrNull { it.className.startsWith("$packageName.") &&
            !it.className.startsWith("$packageName.data.StrictReports") }
        ?.let { "${it.className.removePrefix("$packageName.")}.${it.methodName}(${it.fileName ?: "?"}:${it.lineNumber})" }
}

/**
 * StrictMode watches the main thread for disk and network work and for closable objects nobody closed.
 * Violations go to the app log (see [StrictReports]); they never crash or show a dialog. On API < 28
 * the listener used here does not exist, so they only reach logcat, which is enough to see a crash risk.
 */
internal fun installStrictMode() {
    val reports = StrictReports()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val thread = StrictMode.ThreadPolicy.Builder()
            .detectDiskReads().detectDiskWrites().detectNetwork()
            .penaltyListener(onTheSpot) { violation ->
                reports.report(threadType(violation), violation.stackTrace)
            }
            .build()
        val vm = StrictMode.VmPolicy.Builder()
            .detectLeakedClosableObjects()
            .penaltyListener(onTheSpot) { violation ->
                // Only leaked closables are watched. The cause is the allocation site: the app frame that
                // opened the object, which is where the missing close belongs.
                reports.report("leaked closable", violation.cause?.stackTrace ?: violation.stackTrace)
            }
            .build()
        StrictMode.setThreadPolicy(thread)
        StrictMode.setVmPolicy(vm)
    } else {
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder()
            .detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog().build())
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder()
            .detectLeakedClosableObjects().penaltyLog().build())
    }
}

private fun threadType(violation: Violation): String = when (violation) {
    is DiskReadViolation -> "disk read"
    is DiskWriteViolation -> "disk write"
    is NetworkViolation -> "network"
    is LeakedClosableViolation -> "leaked closable"
    else -> violation.javaClass.simpleName.removeSuffix("Violation")
        .replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase().ifBlank { "violation" }
}
