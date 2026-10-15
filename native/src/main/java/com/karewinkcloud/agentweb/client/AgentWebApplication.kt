package com.karewinkcloud.agentweb.client

import android.app.Application
import com.karewinkcloud.agentweb.client.data.Diagnostics

/** Starts diagnostics before anything else runs, so a crash during start-up is recorded too. */
class AgentWebApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Diagnostics.install(this)
    }
}
