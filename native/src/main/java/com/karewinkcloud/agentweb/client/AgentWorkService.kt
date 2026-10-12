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
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.karewinkcloud.agentweb.client.core.ChatBlock
import com.karewinkcloud.agentweb.client.data.HttpAgentRepository
import com.karewinkcloud.agentweb.client.data.SettingsStore
import kotlinx.coroutines.*

/**
 * Follows a running turn while the app is in the background: a quiet ongoing notification, a
 * heads-up notification with the options as buttons when the agent asks a question, and one when
 * the turn finishes. Started when the app leaves the foreground, stopped when it comes back.
 */
class AgentWorkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var follow: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        val cid = intent?.getStringExtra(EXTRA_CONVERSATION) ?: run { stopSelf(); return START_NOT_STICKY }
        val control = intent.getStringExtra(EXTRA_CONTROL) ?: run { stopSelf(); return START_NOT_STICKY }
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        createChannels(this)
        val ongoing = NotificationCompat.Builder(this, CHANNEL_WORK).setSmallIcon(R.drawable.aw_create)
            .setContentTitle(getString(R.string.notify_working)).setContentText(title).setOngoing(true).setSilent(true)
            .setContentIntent(openApp(this)).build()
        try {
            ServiceCompat.startForeground(this, ID_ONGOING, ongoing,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        } catch (_: Exception) { stopSelf(); return START_NOT_STICKY }
        follow?.cancel()
        follow = scope.launch { followTurn(cid, control, title) }
        return START_NOT_STICKY
    }

    private suspend fun followTurn(cid: String, control: String, title: String) {
        val store = SettingsStore(applicationContext)
        val repository = HttpAgentRepository(store.load().origin, store)
        val asked = mutableSetOf<String>()
        try {
            repository.follow(cid, control).collect { snapshot ->
                val questions = snapshot.turn.blocks.filterIsInstance<ChatBlock.Question>()
                questions.filter { it.answer == null && asked.add(it.id) }.forEach { notifyQuestion(cid, control, it) }
                questions.filter { it.answer != null && it.id in asked }.forEach { cancel(this, it.id.hashCode()) }
                if (snapshot.turn.done) {
                    notify(this, ID_DONE, NotificationCompat.Builder(this, CHANNEL_ATTENTION).setSmallIcon(R.drawable.aw_check)
                        .setContentTitle(getString(R.string.notify_done)).setContentText(title).setAutoCancel(true)
                        .setContentIntent(openApp(this)).build())
                    throw CancellationException("turn finished")
                }
            }
        } catch (_: Exception) {
        } finally { stopSelf() }
    }

    private fun notifyQuestion(cid: String, control: String, question: ChatBlock.Question) {
        val builder = NotificationCompat.Builder(this, CHANNEL_ATTENTION).setSmallIcon(R.drawable.aw_chat)
            .setContentTitle(getString(R.string.notify_question)).setContentText(question.question)
            .setStyle(NotificationCompat.BigTextStyle().bigText(question.question))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true).setContentIntent(openApp(this))
        // Up to three options as buttons; several picks (multi-select) need the app.
        if (!question.multi) question.options.take(3).forEachIndexed { index, option ->
            val answer = Intent(this, AnswerReceiver::class.java).putExtra(EXTRA_CONVERSATION, cid).putExtra(EXTRA_CONTROL, control)
                .putExtra(EXTRA_QUESTION, question.id).putExtra(EXTRA_ANSWER, option)
            builder.addAction(0, option, PendingIntent.getBroadcast(this, question.id.hashCode() * 8 + index, answer,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        notify(this, question.id.hashCode(), builder.build())
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        const val CHANNEL_WORK = "agent_work"
        const val CHANNEL_ATTENTION = "agent_attention"
        const val ACTION_STOP = "stop"
        const val EXTRA_CONVERSATION = "conversation"
        const val EXTRA_CONTROL = "control"
        const val EXTRA_TITLE = "title"
        const val EXTRA_QUESTION = "question"
        const val EXTRA_ANSWER = "answer"
        private const val ID_ONGOING = 41
        private const val ID_DONE = 42

        fun start(context: Context, cid: String, control: String, title: String) = runCatching {
            context.startForegroundService(Intent(context, AgentWorkService::class.java)
                .putExtra(EXTRA_CONVERSATION, cid).putExtra(EXTRA_CONTROL, control).putExtra(EXTRA_TITLE, title))
        }

        fun stop(context: Context) = runCatching { context.stopService(Intent(context, AgentWorkService::class.java)) }

        internal fun createChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(NotificationChannel(CHANNEL_WORK, context.getString(R.string.notify_channel_work),
                NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ATTENTION, context.getString(R.string.notify_channel_attention),
                NotificationManager.IMPORTANCE_HIGH))
        }

        internal fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)

        internal fun notify(context: Context, id: Int, notification: Notification) = runCatching {
            context.getSystemService(NotificationManager::class.java)?.notify(id, notification)
        }

        internal fun cancel(context: Context, id: Int) = runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(id) }
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
