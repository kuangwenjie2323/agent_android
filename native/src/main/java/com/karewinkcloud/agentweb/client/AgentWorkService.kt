package com.karewinkcloud.agentweb.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.karewinkcloud.agentweb.client.core.ChatBlock
import com.karewinkcloud.agentweb.client.core.ComfyJob
import com.karewinkcloud.agentweb.client.core.ComfyJobPhase
import com.karewinkcloud.agentweb.client.core.phase
import com.karewinkcloud.agentweb.client.data.Diagnostics
import com.karewinkcloud.agentweb.client.data.HttpAgentRepository
import com.karewinkcloud.agentweb.client.data.HttpComfyRepository
import com.karewinkcloud.agentweb.client.data.SettingsStore
import com.karewinkcloud.agentweb.client.ui.comfyErrorResource
import kotlinx.coroutines.*
import java.io.File

/**
 * Follows work while the app is in the background: every running chat turn (a heads-up question with
 * the options as buttons, a note when it finishes) and every open creation job (a note with a preview
 * when it is ready, or why it failed). Started when the app leaves the foreground, stopped when it
 * comes back. Tapping a note opens that conversation or creation.
 */
class AgentWorkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var work: Job? = null
    // What the ongoing note counts; both change only on the main thread.
    private var turnsLeft = 0
    private var creationsLeft = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val turns = intent?.turns().orEmpty()
        val creations = intent?.creations().orEmpty()
        if (turns.isEmpty() && creations.isEmpty()) { stopSelf(); return START_NOT_STICKY }
        createChannels(this)
        turnsLeft = turns.size; creationsLeft = creations.size
        Diagnostics.event("work", "following in the background", "turns" to turns.size, "creations" to creations.size)
        try {
            ServiceCompat.startForeground(this, ID_ONGOING, ongoing(),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        } catch (e: Exception) {
            Diagnostics.warn("work", "foreground service refused", "error" to Diagnostics.describe(e))
            stopSelf(); return START_NOT_STICKY
        }
        work?.cancel()
        work = scope.launch {
            val store = SettingsStore(applicationContext)
            val repository = HttpAgentRepository(store.load().origin, store)
            supervisorScope {
                turns.forEach { turn -> launch { followTurn(repository, turn) } }
                if (creations.isNotEmpty()) launch { followCreations(HttpComfyRepository(repository), creations) }
            }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun ongoing(): Notification {
        val summary = listOfNotNull(
            turnsLeft.takeIf { it > 0 }?.let { getString(R.string.notify_background_turns, it) },
            creationsLeft.takeIf { it > 0 }?.let { getString(R.string.notify_background_jobs, it) }).joinToString(" · ")
        return NotificationCompat.Builder(this, CHANNEL_WORK).setSmallIcon(R.drawable.aw_create)
            .setContentTitle(getString(R.string.notify_background)).setContentText(summary).setOngoing(true).setSilent(true)
            .setContentIntent(openApp(this)).build()
    }

    /** The ongoing note follows what is still running ("3 in progress" stayed after two had finished). */
    private fun refreshOngoing() { if (turnsLeft + creationsLeft > 0) notify(this, ID_ONGOING, ongoing()) }

    private suspend fun followTurn(repository: HttpAgentRepository, turn: Turn) {
        val asked = mutableSetOf<String>()
        var sawRunning = false
        try {
            repository.follow(turn.conversationId, turn.controlId).collect { snapshot ->
                if (!snapshot.turn.done) sawRunning = true
                val questions = snapshot.turn.blocks.filterIsInstance<ChatBlock.Question>()
                questions.filter { it.answer == null && asked.add(it.id) }.forEach { notifyQuestion(turn, it) }
                questions.filter { it.answer != null && it.id in asked }.forEach { cancel(this, it.id.hashCode()) }
                if (snapshot.turn.done) {
                    // A list entry can be stale: a turn that had already finished is not news.
                    if (sawRunning) notify(this, turn.conversationId.hashCode(), NotificationCompat.Builder(this, CHANNEL_ATTENTION)
                        .setSmallIcon(R.drawable.aw_check).setContentTitle(getString(R.string.notify_done)).setContentText(turn.title)
                        .setAutoCancel(true).setContentIntent(openConversation(this, turn.conversationId)).build())
                    throw CancellationException("turn finished")
                }
            }
        } catch (e: Exception) {
            if (e !is CancellationException) Diagnostics.warn("work", "turn follow failed", "conversation" to turn.conversationId,
                "error" to Diagnostics.describe(e))
        }
        finally { turnsLeft--; refreshOngoing() }
    }

    private suspend fun followCreations(studio: HttpComfyRepository, creations: List<Creation>) {
        val open = creations.associateBy { it.requestId }.toMutableMap()
        var misses = 0
        // Kaggle can queue for an hour; a connection that keeps failing gives up after a few minutes of misses.
        while (open.isNotEmpty() && misses < 20) {
            for (creation in open.values.toList()) {
                val job = try { studio.job(creation.requestId) } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { misses++; continue }
                when (job.phase()) {
                    ComfyJobPhase.COMPLETE -> { notifyCreation(studio, job, creation, ok = true); open.remove(creation.requestId) }
                    ComfyJobPhase.FAILED -> { notifyCreation(studio, job, creation, ok = false); open.remove(creation.requestId) }
                    ComfyJobPhase.UNKNOWN -> open.remove(creation.requestId)
                    else -> Unit
                }
                if (creationsLeft != open.size) { creationsLeft = open.size; refreshOngoing() }
            }
            if (open.isNotEmpty()) delay(15_000)
        }
    }

    private suspend fun notifyCreation(studio: HttpComfyRepository, job: ComfyJob, creation: Creation, ok: Boolean) {
        val reason = getString(comfyErrorResource(job.errorCode ?: "execution_failed"))
        val builder = NotificationCompat.Builder(this, CHANNEL_ATTENTION).setSmallIcon(if (ok) R.drawable.aw_check else R.drawable.aw_create)
            .setContentTitle(getString(if (ok) R.string.notify_creation_done else R.string.notify_creation_failed))
            .setContentText(if (ok) creation.title else reason)
            .setAutoCancel(true).setContentIntent(openCreation(this, job.requestId))
        if (!ok) builder.setStyle(NotificationCompat.BigTextStyle().bigText(creation.title + "\n" + reason))
        // A preview of the result makes the note worth opening; it is optional.
        if (ok) job.outputs.firstOrNull()?.let { output ->
            val preview = try {
                withContext(Dispatchers.IO) {
                    val file = File(cacheDir, "notify-${job.requestId.hashCode()}.jpg")
                    try { studio.thumbnail(job.requestId, output, file, 512); BitmapFactory.decodeFile(file.path) } finally { file.delete() }
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            preview?.let { builder.setLargeIcon(it).setStyle(NotificationCompat.BigPictureStyle().bigPicture(it).bigLargeIcon(null as Bitmap?)) }
        }
        notify(this, job.requestId.hashCode(), builder.build())
    }

    private fun notifyQuestion(turn: Turn, question: ChatBlock.Question) {
        val builder = NotificationCompat.Builder(this, CHANNEL_ATTENTION).setSmallIcon(R.drawable.aw_chat)
            .setContentTitle(getString(R.string.notify_question)).setContentText(question.question)
            .setStyle(NotificationCompat.BigTextStyle().bigText(question.question))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true).setContentIntent(openConversation(this, turn.conversationId))
        // Up to three options as buttons; several picks (multi-select) need the app.
        if (!question.multi) question.options.take(3).forEachIndexed { index, option ->
            val answer = Intent(this, AnswerReceiver::class.java).putExtra(EXTRA_CONVERSATION, turn.conversationId)
                .putExtra(EXTRA_CONTROL, turn.controlId).putExtra(EXTRA_QUESTION, question.id).putExtra(EXTRA_ANSWER, option)
            builder.addAction(0, option, PendingIntent.getBroadcast(this, question.id.hashCode() * 8 + index, answer,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        notify(this, question.id.hashCode(), builder.build())
    }

    override fun onDestroy() { Diagnostics.event("work", "background follow stopped"); scope.cancel(); super.onDestroy() }

    data class Turn(val conversationId: String, val controlId: String, val title: String)
    data class Creation(val requestId: String, val title: String)

    companion object {
        const val CHANNEL_WORK = "agent_work"
        const val CHANNEL_ATTENTION = "agent_attention"
        const val EXTRA_CONVERSATION = "conversation"
        const val EXTRA_CONTROL = "control"
        const val EXTRA_QUESTION = "question"
        const val EXTRA_ANSWER = "answer"
        /** Set on the Activity intent of a tapped note: the conversation or creation to show. */
        const val EXTRA_OPEN_CONVERSATION = "open_conversation"
        const val EXTRA_OPEN_CREATION = "open_creation"
        private const val EXTRA_TURN_IDS = "turn_ids"
        private const val EXTRA_TURN_CONTROLS = "turn_controls"
        private const val EXTRA_TURN_TITLES = "turn_titles"
        private const val EXTRA_JOB_IDS = "job_ids"
        private const val EXTRA_JOB_TITLES = "job_titles"
        private const val ID_ONGOING = 41

        fun start(context: Context, turns: List<Turn>, creations: List<Creation>) = runCatching {
            context.startForegroundService(Intent(context, AgentWorkService::class.java)
                .putStringArrayListExtra(EXTRA_TURN_IDS, ArrayList(turns.map { it.conversationId }))
                .putStringArrayListExtra(EXTRA_TURN_CONTROLS, ArrayList(turns.map { it.controlId }))
                .putStringArrayListExtra(EXTRA_TURN_TITLES, ArrayList(turns.map { it.title }))
                .putStringArrayListExtra(EXTRA_JOB_IDS, ArrayList(creations.map { it.requestId }))
                .putStringArrayListExtra(EXTRA_JOB_TITLES, ArrayList(creations.map { it.title })))
        }.onFailure { Diagnostics.warn("work", "could not start background follow", "error" to Diagnostics.describe(it)) }

        fun stop(context: Context) = runCatching { context.stopService(Intent(context, AgentWorkService::class.java)) }

        private fun Intent.turns(): List<Turn> {
            val ids = getStringArrayListExtra(EXTRA_TURN_IDS).orEmpty(); val controls = getStringArrayListExtra(EXTRA_TURN_CONTROLS).orEmpty()
            val titles = getStringArrayListExtra(EXTRA_TURN_TITLES).orEmpty()
            return ids.indices.filter { it < controls.size }.map { Turn(ids[it], controls[it], titles.getOrNull(it).orEmpty()) }.take(6)
        }

        private fun Intent.creations(): List<Creation> {
            val ids = getStringArrayListExtra(EXTRA_JOB_IDS).orEmpty(); val titles = getStringArrayListExtra(EXTRA_JOB_TITLES).orEmpty()
            return ids.mapIndexed { index, id -> Creation(id, titles.getOrNull(index).orEmpty()) }.take(12)
        }

        internal fun createChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(NotificationChannel(CHANNEL_WORK, context.getString(R.string.notify_channel_work),
                NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ATTENTION, context.getString(R.string.notify_channel_attention),
                NotificationManager.IMPORTANCE_HIGH))
        }

        internal fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)

        private fun openConversation(context: Context, id: String): PendingIntent = PendingIntent.getActivity(context, id.hashCode(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(EXTRA_OPEN_CONVERSATION, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        private fun openCreation(context: Context, requestId: String): PendingIntent = PendingIntent.getActivity(context, requestId.hashCode(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(EXTRA_OPEN_CREATION, requestId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        internal fun notify(context: Context, id: Int, notification: Notification) = runCatching {
            context.getSystemService(NotificationManager::class.java)?.notify(id, notification)
        }

        internal fun cancel(context: Context, id: Int) = runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(id) }

        /** The app is in front again: its notes have done their job. */
        fun clearAll(context: Context) = runCatching { context.getSystemService(NotificationManager::class.java)?.cancelAll() }
    }
}

/** A tap on an option button in the question notification answers it without opening the app. */
class AnswerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cid = intent.getStringExtra(AgentWorkService.EXTRA_CONVERSATION) ?: return
        val control = intent.getStringExtra(AgentWorkService.EXTRA_CONTROL) ?: return
        val question = intent.getStringExtra(AgentWorkService.EXTRA_QUESTION) ?: return
        val answer = intent.getStringExtra(AgentWorkService.EXTRA_ANSWER) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val app = context.applicationContext
            val store = SettingsStore(app)
            val ok = runCatching { HttpAgentRepository(store.load().origin, store).answer(cid, control, question, answer) }.getOrDefault(false)
            AgentWorkService.notify(app, question.hashCode(), NotificationCompat.Builder(app, AgentWorkService.CHANNEL_ATTENTION)
                .setSmallIcon(R.drawable.aw_check).setSilent(true).setAutoCancel(true).setTimeoutAfter(8000)
                .setContentTitle(app.getString(if (ok) R.string.notify_answered else R.string.notify_answer_failed, answer))
                .setContentIntent(AgentWorkService.openApp(app)).build())
            pending.finish()
        }
    }
}
