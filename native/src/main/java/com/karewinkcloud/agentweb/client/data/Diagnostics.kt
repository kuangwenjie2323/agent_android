package com.karewinkcloud.agentweb.client.data

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.core.content.edit
import com.karewinkcloud.agentweb.client.BuildConfig
import com.karewinkcloud.agentweb.client.core.DiagnosticLog
import okhttp3.HttpUrl
import okhttp3.Interceptor
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide diagnostics: what the app did and how long it took, kept in [DiagnosticLog] on the phone.
 * Records requests (method, path, status, time; never queries, headers or bodies), stream reconnects,
 * errors shown, crashes, why the previous process ended, main-thread stalls, and foreground changes.
 * Every call is a no-op until [install] runs, so JVM tests and previews stay silent.
 */
object Diagnostics {
    @Volatile private var log: DiagnosticLog? = null
    private val writer: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { task -> Thread(task, "diagnostics").apply { isDaemon = true } }
    }

    fun install(app: Application) {
        if (log != null) return
        log = DiagnosticLog(File(app.noBackupFilesDir, "diagnostics"))
        installCrashHandler()
        event("app", "start", "version" to BuildConfig.VERSION_NAME, "code" to BuildConfig.VERSION_CODE,
            "build" to BuildConfig.BUILD_LABEL, "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "android" to "${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT})",
            "sinceProcessStartMs" to SystemClock.uptimeMillis() - Process.getStartUptimeMillis())
        enqueue { recordExits(app) }
        app.registerActivityLifecycleCallbacks(Foreground)
        app.registerComponentCallbacks(Memory)
    }

    /** JVM tests: record into [target] (or stop recording) without the Android hooks. */
    internal fun attach(target: DiagnosticLog?) { log = target }

    fun event(tag: String, message: String, vararg fields: Pair<String, Any?>) = write('I', tag, message, fields)
    fun warn(tag: String, message: String, vararg fields: Pair<String, Any?>) = write('W', tag, message, fields)

    /** "IOException: timeout": enough to tell failures apart without a whole stack. */
    fun describe(error: Throwable): String = "${error.javaClass.simpleName}: ${redact(error.message.orEmpty()).take(200)}"

    // A JSON parse error quotes the input it failed on, which can be message text.
    private val jsonInput = Regex("JSON input: .*")
    internal fun redact(text: String) = text.replace(jsonInput, "JSON input: (left out)")

    private fun write(level: Char, tag: String, message: String, fields: Array<out Pair<String, Any?>>) {
        val target = log ?: return
        val values = fields.toMap()
        enqueue { target.write(level, tag, message, values) }
    }

    private fun enqueue(task: () -> Unit) {
        runCatching { writer.execute { runCatching(task) } }
    }

    /** Writes everything queued, then returns the log for upload. */
    fun flushed(): DiagnosticLog? {
        val target = log ?: return null
        Network.flush()
        runCatching { writer.submit { }.get(3, TimeUnit.SECONDS) }
        return target
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                // The process is about to die: let queued lines land, then write the crash itself.
                writer.shutdown(); writer.awaitTermination(300, TimeUnit.MILLISECONDS)
                log?.write('E', "crash", "uncaught exception", mapOf("thread" to thread.name,
                    "error" to describe(error), "stack" to redact(error.stackTraceToString())))
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Why earlier processes ended (crash, ANR, low memory, killed in the background...), once each. */
    private fun recordExits(app: Application) {
        if (Build.VERSION.SDK_INT < 30) return
        val prefs = app.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
        val seen = prefs.getLong("exitSeenAt", 0)
        val exits = app.getSystemService(ActivityManager::class.java)
            ?.getHistoricalProcessExitReasons(app.packageName, 0, 8).orEmpty()
        exits.filter { it.timestamp > seen }.sortedBy { it.timestamp }.forEach { exit ->
            val bad = exit.reason in setOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE,
                ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_LOW_MEMORY)
            val trace = if (exit.reason == ApplicationExitInfo.REASON_ANR)
                runCatching { exit.traceInputStream?.use { mainThread(it.readBytes().decodeToString()) } }.getOrNull() else null
            log?.write(if (bad) 'W' else 'I', "exit", "previous process ended", mapOf("reason" to exitReason(exit.reason),
                "at" to exit.timestamp, "status" to exit.status, "importance" to exit.importance,
                "pssKb" to exit.pss, "rssKb" to exit.rss, "description" to exit.description, "trace" to trace))
        }
        exits.maxOfOrNull { it.timestamp }?.let { prefs.edit { putLong("exitSeenAt", it) } }
    }

    /** The main thread's section of an ANR dump. */
    internal fun mainThread(dump: String): String {
        val start = dump.indexOf("\"main\"").takeIf { it >= 0 } ?: return dump.take(DiagnosticLog.MAX_TRACE)
        val end = dump.indexOf("\n\n", start).takeIf { it > 0 } ?: dump.length
        return dump.substring(start, end)
    }

    internal fun exitReason(reason: Int) = when (reason) {
        1 -> "exit_self"; 2 -> "signaled"; 3 -> "low_memory"; 4 -> "crash"; 5 -> "crash_native"; 6 -> "anr"
        7 -> "initialization_failure"; 8 -> "permission_change"; 9 -> "excessive_resource_usage"; 10 -> "user_requested"
        11 -> "user_stopped"; 12 -> "dependency_died"; 13 -> "other"; 14 -> "freezer"; 15 -> "package_state_change"
        16 -> "package_updated"; else -> "unknown_$reason"
    }

    /** Foreground and background, and the main-thread watchdog while the app is in front. */
    private object Foreground : Application.ActivityLifecycleCallbacks {
        private var started = 0
        override fun onActivityStarted(activity: Activity) { if (started++ == 0) { event("app", "foreground"); Watchdog.start() } }
        override fun onActivityStopped(activity: Activity) {
            if (--started == 0) { Watchdog.stop(); Network.flush(); event("app", "background") }
        }
        override fun onActivityCreated(activity: Activity, saved: Bundle?) {
            event("app", "activity created", "restored" to (saved != null))
            activity.window?.decorView?.post { event("app", "first frame", "sinceProcessStartMs" to
                SystemClock.uptimeMillis() - Process.getStartUptimeMillis()) }
        }
        override fun onActivityDestroyed(activity: Activity) {
            event("app", "activity destroyed", "finishing" to activity.isFinishing, "configChange" to activity.isChangingConfigurations)
        }
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
    }

    private object Memory : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            val runtime = Runtime.getRuntime()
            event("memory", "trim", "level" to level, "heapUsedMb" to (runtime.totalMemory() - runtime.freeMemory()) / 1_048_576,
                "heapMaxMb" to runtime.maxMemory() / 1_048_576)
        }
        @Deprecated("Deprecated in Android") override fun onLowMemory() { warn("memory", "low memory") }
        override fun onConfigurationChanged(newConfig: Configuration) {}
    }

    /** Notes when the main thread stops answering for [BLOCK_MS] (a visible freeze), with its stack. */
    private object Watchdog {
        private const val BLOCK_MS = 800L
        private const val PERIOD_MS = 200L
        private val main by lazy { Handler(Looper.getMainLooper()) }
        @Volatile private var thread: Thread? = null

        fun start() {
            if (thread != null) return
            // Published before it runs: the loop keeps going only while it is the current watchdog.
            val next = Thread(::watch, "diagnostics-watchdog").apply { isDaemon = true }
            thread = next
            next.start()
        }

        fun stop() { thread?.interrupt(); thread = null }

        private fun watch() {
            val me = Thread.currentThread()
            val answered = AtomicLong()
            try {
                while (thread === me) {
                    val ping = SystemClock.uptimeMillis()
                    main.post { answered.set(ping) }
                    Thread.sleep(BLOCK_MS)
                    if (answered.get() != ping) {
                        val stack = Looper.getMainLooper().thread.stackTrace.take(30).joinToString("\n") { "at $it" }
                        warn("ui", "main thread blocked", "forMs" to BLOCK_MS, "stack" to stack)
                        while (answered.get() != ping && thread === me) Thread.sleep(50)
                        warn("ui", "main thread resumed", "blockedMs" to SystemClock.uptimeMillis() - ping)
                    }
                    Thread.sleep(PERIOD_MS)
                }
            } catch (_: InterruptedException) { }
        }
    }

    /** Every request's outcome; quick successful GETs (list polling) are summed up once a minute. */
    val interceptor = Interceptor { chain ->
        val request = chain.request()
        if (log == null) return@Interceptor chain.proceed(request)
        val started = System.nanoTime()
        val target = target(request.url)
        try {
            val response = chain.proceed(request)
            val stream = response.body?.contentType()?.subtype == "event-stream"
            Network.record(request.method, target, response.code, (System.nanoTime() - started) / 1_000_000,
                response.body?.contentLength() ?: -1, stream)
            response
        } catch (e: IOException) {
            val ms = (System.nanoTime() - started) / 1_000_000
            if (chain.call().isCanceled()) event("net", "${request.method} $target canceled", "ms" to ms)
            // Sign-in failures keep only the type: their messages could echo an authorization code.
            else warn("net", "${request.method} $target failed", "ms" to ms,
                "error" to if (target.startsWith("/_access/")) e.javaClass.simpleName else describe(e))
            throw e
        }
    }

    /** The API path alone; other hosts (cloud storage) keep their host. Query strings are dropped: they can carry signatures. */
    internal fun target(url: HttpUrl): String {
        val path = url.encodedPath.take(160)
        return if (path.startsWith("/api/") || path.startsWith("/_access/")) path else url.host + path
    }

    internal object Network {
        private const val SLOW_MS = 1500L
        private const val SUMMARY_MS = 60_000L
        private var count = 0
        private var totalMs = 0L
        private var maxMs = 0L
        private var since = 0L
        private val paths = HashMap<String, Int>()
        private val ids = Regex("/[0-9a-fA-F-]{16,}|/\\d{6,}")

        @Synchronized fun record(method: String, target: String, code: Int, ms: Long, bytes: Long, stream: Boolean) {
            when {
                stream -> event("net", "$method $target $code stream opened", "ms" to ms)
                method == "GET" && code in 200..399 && ms < SLOW_MS -> {
                    if (count == 0) since = System.currentTimeMillis()
                    count++; totalMs += ms; maxMs = maxOf(maxMs, ms)
                    val key = target.replace(ids, "/:id")
                    paths[key] = (paths[key] ?: 0) + 1
                }
                code >= 400 -> warn("net", "$method $target $code", "ms" to ms, "bytes" to bytes)
                else -> event("net", "$method $target $code", "ms" to ms, "bytes" to bytes)
            }
            if (count > 0 && System.currentTimeMillis() - since >= SUMMARY_MS) flush()
        }

        @Synchronized fun flush() {
            if (count == 0) return
            event("net", "GET ok x$count", "avgMs" to totalMs / count, "maxMs" to maxMs,
                "paths" to paths.entries.sortedByDescending { it.value }.take(5).joinToString { "${it.key} x${it.value}" })
            count = 0; totalMs = 0; maxMs = 0; paths.clear()
        }
    }
}
