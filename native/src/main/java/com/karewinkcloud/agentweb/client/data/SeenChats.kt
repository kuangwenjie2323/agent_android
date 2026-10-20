package com.karewinkcloud.agentweb.client.data

import android.content.Context
import com.karewinkcloud.agentweb.client.core.Conversation

/**
 * When the user last looked at each chat, on the server's updated_at scale (seconds), so the list can mark a
 * chat with a reply the user has not read yet, like the session list in Claude's desktop app.
 */
interface SeenChats {
    fun seenAt(id: String): Long?
    fun markSeen(id: String, at: Long)
    /** Chats the list shows for the first time count as read up to their last change. */
    fun adopt(rows: List<Conversation>) { rows.forEach { if (seenAt(it.id) == null) markSeen(it.id, it.updatedAt) } }
}

class MemorySeenChats : SeenChats {
    private val seen = mutableMapOf<String, Long>()
    override fun seenAt(id: String) = seen[id]
    override fun markSeen(id: String, at: Long) { seen[id] = maxOf(seen[id] ?: 0, at) }
}

class PrefsSeenChats(context: Context) : SeenChats {
    private val prefs = context.getSharedPreferences("seen_chats", Context.MODE_PRIVATE)
    override fun seenAt(id: String) = if (prefs.contains(id)) prefs.getLong(id, 0) else null
    override fun markSeen(id: String, at: Long) {
        if (at > (seenAt(id) ?: Long.MIN_VALUE)) prefs.edit().putLong(id, at).apply()
    }
    override fun adopt(rows: List<Conversation>) {
        val fresh = rows.filter { !prefs.contains(it.id) }
        if (fresh.isNotEmpty()) prefs.edit().apply { fresh.forEach { putLong(it.id, it.updatedAt) } }.apply()
    }
}

/** A chat has news when it changed after the user last looked and nothing runs or waits there. */
fun Conversation.unread(seen: Long?) = seen != null && updatedAt > seen && !running && !waitingInput

/**
 * False only when the phone has no network at all (airplane mode, no signal). Android's "validated" mark is
 * not required: its check servers can be out of reach in China while the network itself works.
 */
fun phoneOnline(context: Context): Boolean = runCatching {
    val manager = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return true
    val network = manager.activeNetwork ?: return false
    manager.getNetworkCapabilities(network)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false
}.getOrDefault(true)
