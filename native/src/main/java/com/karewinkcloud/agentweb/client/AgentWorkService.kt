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
import com.karewinkcloud.agentweb.client.core.StreamProtocolException
import com.karewinkcloud.agentweb.client.core.TurnState
import com.karewinkcloud.agentweb.client.core.TurnHandover
import com.karewinkcloud.agentweb.client.core.TurnStreams
import com.karewinkcloud.agentweb.client.core.array
import com.karewinkcloud.agentweb.client.core.boolean
import com.karewinkcloud.agentweb.client.core.string
import com.karewinkcloud.agentweb.client.core.wireJson
import com.karewinkcloud.agentweb.client.data.Diagnostics
import com.karewinkcloud.agentweb.client.data.HttpAgentRepository
import com.karewinkcloud.agentweb.client.data.HttpComfyRepository
import com.karewinkcloud.agentweb.client.data.SettingsStore
import com.karewinkcloud.agentweb.client.ui.comfyErrorResource
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
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

    override fun onCreate() { super.onCreate(); instance = this }

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
        // A new start replaces every follower; release their claim before the replacement starts.
        TurnStreams.releaseFollowers()
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
        try {
            // The screen stops its stream before the hand-over; a claim it still holds wins here, so
            // one run is never streamed by both at once.
            // The screen keeps its own stream through the grace after leaving; follow once it has handed over.
            if (!awaitHandOver()) return
            if (!TurnStreams.beginFollower(turn.conversationId, turn.controlId)) {
                Diagnostics.event("stream", "follow skipped", "who" to "background", "run" to turn.controlId)
                return
            }
            followResumed(repository, turn)
        } finally {
            TurnStreams.endFollower(turn.conversationId, turn.controlId)
            turnsLeft--; refreshOngoing()
        }
    }

    /** Waits for the screen to hand its turns over; false when that never came (the screen kept them). */
    private suspend fun awaitHandOver(): Boolean {
        val until = System.currentTimeMillis() + TurnHandover.GRACE_MILLIS * 4
        while (!TurnStreams.isHandedOver()) {
            if (System.currentTimeMillis() > until) return false
            delay(200)
        }
        return true
    }

    private suspend fun followResumed(repository: HttpAgentRepository, turn: Turn) {
        // Questions the screen showed are not in the resumed stream any more: announce them now; a
        // later answer event takes the note away again. The screen read on during the grace: resume after
        // the furthest event it saw, not where it was when the app was left.
        val resumed = turn.resumeState(maxOf(turn.from, TurnStreams.furthest(turn.conversationId, turn.controlId)))
        val asked = if (resumed != null) turn.questions.mapTo(mutableSetOf()) { it.id } else mutableSetOf()
        if (resumed != null) turn.questions.forEach { notifyQuestion(turn, it) }
        var sawRunning = false
        // The run the app knew can be gone by now (a queued message started the next one): then the chat's
        // current run is followed instead, once, so its question and its end still reach the user.
        var run: String? = turn.controlId
        var previous = resumed
        while (true) try {
            Diagnostics.event("stream", "follow started", "who" to "background",
                "from" to (previous?.lastSequence ?: 0), "run" to (run ?: ""))
            repository.follow(turn.conversationId, run, previous = previous).collect { snapshot ->
                TurnStreams.note(turn.conversationId, snapshot.turn.runId, snapshot.turn.lastSequence)
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
            break
        } catch (e: StreamProtocolException) {
            if (run == null) { Diagnostics.warn("work", "turn follow failed", "conversation" to turn.conversationId,
                "error" to Diagnostics.describe(e)); break }
            Diagnostics.event("work", "following the chat's current run", "conversation" to turn.conversationId)
            run = null
            previous = null
        } catch (e: Exception) {
            if (e !is CancellationException) Diagnostics.warn("work", "turn follow failed", "conversation" to turn.conversationId,
                "error" to Diagnostics.describe(e))
            break
        }
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

    override fun onDestroy() {
        if (instance === this) instance = null
        TurnStreams.releaseFollowers()
        Diagnostics.event("work", "background follow stopped")
        scope.cancel(); super.onDestroy()
    }

    /** Stops following now, before the screen's own stream resumes on coming back. */
    internal fun halt() { TurnStreams.releaseFollowers(); work?.cancel(); turnsLeft = 0; creationsLeft = 0 }

    data class Turn(val conversationId: String, val controlId: String, val title: String,
        /** The last event the screen saw; the follower resumes after it instead of replaying the turn. */
        val from: Long = 0,
        /** Questions still open on screen: the resumed stream no longer carries them, so announce them. */
        val questions: List<ChatBlock.Question> = emptyList()) {
        /** The state the repository resumes from: the run, its last event, and the questions to keep an eye on. */
        fun resumeState(after: Long = from) = after.takeIf { it > 0 }?.let {
            TurnState(conversationId, controlId, lastSequence = it, blocks = questions)
        }
    }
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
        private const val EXTRA_TURN_FROM = "turn_from"
        private const val EXTRA_TURN_QUESTIONS = "turn_questions"
        private const val EXTRA_JOB_IDS = "job_ids"
        private const val EXTRA_JOB_TITLES = "job_titles"
        private const val ID_ONGOING = 41
        @Volatile private var instance: AgentWorkService? = null

        fun start(context: Context, turns: List<Turn>, creations: List<Creation>) = runCatching {
            context.startForegroundService(Intent(context, AgentWorkService::class.java)
                .putStringArrayListExtra(EXTRA_TURN_IDS, ArrayList(turns.map { it.conversationId }))
                .putStringArrayListExtra(EXTRA_TURN_CONTROLS, ArrayList(turns.map { it.controlId }))
                .putStringArrayListExtra(EXTRA_TURN_TITLES, ArrayList(turns.map { it.title }))
                .putExtra(EXTRA_TURN_FROM, turns.map { it.from }.toLongArray())
                .putExtra(EXTRA_TURN_QUESTIONS, questionsJson(turns))
                .putStringArrayListExtra(EXTRA_JOB_IDS, ArrayList(creations.map { it.requestId }))
                .putStringArrayListExtra(EXTRA_JOB_TITLES, ArrayList(creations.map { it.title })))
        }.onFailure { Diagnostics.warn("work", "could not start background follow", "error" to Diagnostics.describe(it)) }

        fun stop(context: Context) = runCatching {
            instance?.halt()
            context.stopService(Intent(context, AgentWorkService::class.java))
        }

        private fun questionsJson(turns: List<Turn>): String = buildJsonArray {
            turns.forEach { turn -> addJsonArray {
                turn.questions.forEach { question -> addJsonObject {
                    put("id", question.id); put("question", question.question)
                    put("multi", question.multi); put("answer", question.answer)
                    putJsonArray("options") { question.options.forEach { add(it) } }
                } }
            } }
        }.toString()

        private fun parseQuestions(raw: String?): List<List<ChatBlock.Question>> {
            val rows = raw?.let { runCatching { wireJson.parseToJsonElement(it) as? JsonArray }.getOrNull() } ?: return emptyList()
            return rows.map { row ->
                (row as? JsonArray)?.mapNotNull { item ->
                    val question = item as? JsonObject ?: return@mapNotNull null
                    ChatBlock.Question(question.string("id").orEmpty(), question.string("question").orEmpty(),
                        question.array("options").mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                        question.boolean("multi") == true, question.string("answer"))
                }.orEmpty()
            }
        }

        private fun Intent.turns(): List<Turn> {
            val ids = getStringArrayListExtra(EXTRA_TURN_IDS).orEmpty(); val controls = getStringArrayListExtra(EXTRA_TURN_CONTROLS).orEmpty()
            val titles = getStringArrayListExtra(EXTRA_TURN_TITLES).orEmpty(); val from = getLongArrayExtra(EXTRA_TURN_FROM) ?: LongArray(0)
            val questions = parseQuestions(getStringExtra(EXTRA_TURN_QUESTIONS))
            return ids.indices.filter { it < controls.size }.map { index ->
                Turn(ids[index], controls[index], titles.getOrNull(index).orEmpty(), from.getOrNull(index) ?: 0,
                    questions.getOrNull(index).orEmpty())
            }.take(6)
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
