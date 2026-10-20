package com.karewinkcloud.agentweb.client

import android.app.Application
import com.karewinkcloud.agentweb.client.data.Diagnostics
import com.karewinkcloud.agentweb.client.data.installStrictMode

/** Starts diagnostics before anything else runs, so a crash during start-up is recorded too. */
class AgentWebApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        warmUpFrameworkPaths()
        Diagnostics.install(this)
        // On in every build: the builds installed on the phone are debug builds, and release checks read
        // the `strict` lines from the app log.
        installStrictMode()
    }

    /**
     * Android creates the cache, no-backup and preferences folders on whichever thread first asks for
     * them; on a fresh install that first ask could be the main thread, and StrictMode would report the
     * mkdir. Ask once before monitoring starts, so by then they exist and the Activity only looks them up.
     */
    @Suppress("UNUSED_EXPRESSION")
    private fun warmUpFrameworkPaths() {
        cacheDir
        noBackupFilesDir
        for (name in arrayOf("native-settings", "native-credentials", "seen_chats", "notifications",
            "comfy-pending-v1", "diagnostics")) getSharedPreferences(name, MODE_PRIVATE)
    }
}
