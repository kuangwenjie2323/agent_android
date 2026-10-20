package com.karewinkcloud.agentweb.client

import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import java.util.Locale
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import com.karewinkcloud.agentweb.client.data.*
import com.karewinkcloud.agentweb.client.ui.*
import com.karewinkcloud.agentweb.client.core.TurnHandover
import com.karewinkcloud.agentweb.client.core.TurnStreams
import com.karewinkcloud.agentweb.client.core.needsPolling

class MainActivity : ComponentActivity() {
    private lateinit var settings: SettingsViewModel
    private var chatModel: AgentViewModel? = null
    private var studioModel: ComfyViewModel? = null
    private var following = false
    /** The delayed hand-over to the background follower; cancelled when the screen comes back first. */
    private val handler = Handler(Looper.getMainLooper())
    private val handOver = Runnable { handOverIfDue() }
    /** A tapped notification's target ("conversation" or "creation" to its id), shown once the view models exist. */
    private val openRequest = mutableStateOf<Pair<String, String>?>(null)
    private val listCacheDir by lazy { java.io.File(noBackupFilesDir, "conversation-list") }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        settings = ViewModelProvider(this, factory {
            SettingsViewModel(SettingsStore(applicationContext), deviceName = Build.MODEL)
        })[SettingsViewModel::class.java]
        takeOpenRequest(intent)
        receiveCallback(intent)
        askForNotifications()
        setContent {
            val preferences by settings.state.collectAsStateWithLifecycle()
            val authentication by settings.authentication.collectAsStateWithLifecycle()
            val phase by settings.phase.collectAsStateWithLifecycle()
            val connection = ServerConnection(preferences.origin, authentication.revision,
                phase == SignInPhase.SIGNING_OUT || (!settings.localTesting() && authentication.origin != preferences.origin))
            val chat: AgentViewModel = viewModel(factory = factory {
                AgentViewModel(HttpAgentRepository(preferences.origin, settings.store, listCacheDir = listCacheDir), preferences.choice,
                    connection.signInRequired, watchIdleChats = true, seenChats = PrefsSeenChats(applicationContext),
                    online = { phoneOnline(applicationContext) }, saveChoice = settings.store::saveChoice).also { it.appliedConnection = connection }
            })
            SideEffect { chatModel = chat }
            LaunchedEffect(connection) {
                // The epoch survives Activity recreation along with the chat ViewModel.
                if (chat.appliedConnection != connection) {
                    chat.appliedConnection = connection
                    chat.changeServer(HttpAgentRepository(connection.origin, settings.store, listCacheDir = listCacheDir), settings.store.load().choice, connection.signInRequired)
                }
            }
            val studio: ComfyViewModel = viewModel(factory = factory {
                ComfyViewModel(HttpComfyRepository(HttpAgentRepository(connection.origin, settings.store, listCacheDir = listCacheDir)),
                    AndroidComfyPendingStore(applicationContext, connection.origin, settings.store),
                    java.io.File(cacheDir, "comfy-output"), connection.signInRequired).also { it.appliedConnection = connection }
            })
            SideEffect { studioModel = studio }
            LaunchedEffect(studio) { kotlinx.coroutines.delay(3_000); studio.preload() }
            val request by openRequest
            LaunchedEffect(request) {
                val (kind, id) = request ?: return@LaunchedEffect
                openRequest.value = null
                if (kind == "creation") { chat.selectTab(AppTab.CREATE); studio.openById(id) } else chat.openById(id)
            }
            LaunchedEffect(connection) {
                if (studio.appliedConnection != connection) {
                    studio.appliedConnection = connection
                    studio.changeConnection(HttpComfyRepository(HttpAgentRepository(connection.origin, settings.store, listCacheDir = listCacheDir)),
                        AndroidComfyPendingStore(applicationContext, connection.origin, settings.store), connection.signInRequired)
                }
            }
            val dark = preferences.theme == "dark" || (preferences.theme == "system" && isSystemInDarkTheme())
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }
            val baseConfiguration = LocalConfiguration.current
            val localized = remember(preferences.language, baseConfiguration) {
                createConfigurationContext(Configuration(baseConfiguration).apply {
                    if (preferences.language != "system") setLocale(Locale.forLanguageTag(preferences.language))
                })
            }
            val passkey = remember(this) { AndroidPasskeyProvider(this) }
            val chatMedia = remember(connection) { ChatMediaStore(HttpAgentRepository(connection.origin, settings.store), cacheDir) }
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration,
                LocalAppResources provides localized.resources, LocalActivity provides this,
                // The localized context no longer resolves to this Activity, so the owners that
                // Compose would otherwise find through LocalContext must be provided explicitly.
                LocalActivityResultRegistryOwner provides this, LocalOnBackPressedDispatcherOwner provides this) {
                AgentWebTheme(preferences.theme) {
                    ChatMediaHost(chatMedia, { url -> openBrowser(url, chat::browserUnavailable) }) {
                        AgentWebApp(chat, settings, studio, { url -> openBrowser(url, settings::browserUnavailable) }, passkey)
                    }
                }
            }
        }
    }

    // Leaving the app (Home, recents, screen off) starts the hand-over clock; a quick return cancels it.
    override fun onUserLeaveHint() { super.onUserLeaveHint(); leftForBackground() }

    override fun onStop() { super.onStop(); if (!isChangingConfigurations) leftForBackground() }

    override fun onStart() {
        // Coming back: no hand-over may start now, and a running follower stops before the screen's
        // stream resumes. Doing this before super.onStart() keeps the two never streaming one run.
        handler.removeCallbacks(handOver)
        TurnStreams.returned()
        following = false
        AgentWorkService.stop(this)
        super.onStart()
        AgentWorkService.clearAll(this)
    }

    override fun onDestroy() {
        // A rotation's old Activity must not hand over the turn its replacement keeps streaming.
        if (isChangingConfigurations) handler.removeCallbacks(handOver)
        super.onDestroy()
    }

    private fun leftForBackground() {
        if (!TurnStreams.left(System.currentTimeMillis())) return
        // The follower service starts now, while Android still lets an app that was just on screen start one
        // (a few seconds only, so it cannot wait for the grace). It reads nothing until the hand-over below,
        // so a quick return costs no second stream.
        if (!followInBackground()) { TurnStreams.returned(); return }
        handler.removeCallbacks(handOver)
        handler.postDelayed(handOver, TurnHandover.GRACE_MILLIS)
    }

    private fun handOverIfDue() {
        // onUserLeaveHint can fire while the screen is still started (a dialog on top): never hand over
        // a turn that is still on screen.
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { TurnStreams.returned(); return }
        if (!TurnStreams.due(System.currentTimeMillis())) return
        // The screen lets go first, then the follower may start: one run is never read twice. A control request
        // in flight keeps the screen's stream, and the follower skips that run (the screen still holds it).
        chatModel?.yieldStream()
        TurnStreams.handOver()
    }

    /**
     * Every running turn (the open chat and the list) and every open creation goes to the notification follower;
     * false when there is nothing to follow or the service could not start.
     */
    private fun followInBackground(): Boolean {
        if (following) return true
        val state = chatModel?.state?.value
        val turns = handoverTurns(state)
        val studio = studioModel?.state?.value
        val creations = studio?.jobs.orEmpty().filter { it.needsPolling() }.map { job ->
            AgentWorkService.Creation(job.requestId, studio?.workflows?.find { it.id == job.workflowId }?.title ?: job.workflowId)
        }
        if (turns.isEmpty() && creations.isEmpty()) return false
        following = AgentWorkService.start(this, turns, creations).isSuccess
        return following
    }

    private fun takeOpenRequest(incoming: Intent) {
        incoming.getStringExtra(AgentWorkService.EXTRA_OPEN_CONVERSATION)?.let { openRequest.value = "conversation" to it }
        incoming.getStringExtra(AgentWorkService.EXTRA_OPEN_CREATION)?.let { openRequest.value = "creation" to it }
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val preferences = getSharedPreferences("notifications", MODE_PRIVATE)
        if (preferences.getBoolean("asked", false)) return
        preferences.edit().putBoolean("asked", true).apply()
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeOpenRequest(intent)
        receiveCallback(intent)
    }

    private fun receiveCallback(incoming: Intent) {
        val callback = incoming.dataString.takeIf { incoming.action == Intent.ACTION_VIEW }
        // Do not let the code survive in the Activity intent or in later instance-state saves.
        incoming.data = null
        incoming.replaceExtras(Bundle())
        intent = Intent(this, MainActivity::class.java)
        if (callback != null) settings.callback(callback)
    }

    private fun openBrowser(url: String, onUnavailable: () -> Unit) {
        val uri = Uri.parse(url)
        try {
            val browser = CustomTabsClient.getPackageName(this, listOf("com.android.chrome"))
            if (browser != null) {
                CustomTabsIntent.Builder().setShowTitle(true).build().apply { intent.setPackage(browser) }
                    .launchUrl(this, uri)
                return
            }
        } catch (_: Exception) { /* Try the default browser below. */ }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: Exception) { onUnavailable() }
    }
}

private fun <T : ViewModel> factory(create: () -> T) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = create() as VM
}
