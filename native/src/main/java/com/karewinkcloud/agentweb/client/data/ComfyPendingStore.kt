package com.karewinkcloud.agentweb.client.data

import android.content.Context
import com.karewinkcloud.agentweb.client.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

interface ComfyPendingStore {
    suspend fun read(): String?
    suspend fun write(id: String?)
}

/** Only a UUID is persisted, partitioned by origin + credential fingerprint. No prompt or token. */
class AndroidComfyPendingStore(context: Context, private val origin: String, private val tokens: TokenProvider) : ComfyPendingStore {
    private val prefs = context.getSharedPreferences("comfy-pending-v1", Context.MODE_PRIVATE)
    private val scope by lazy {
        MessageDigest.getInstance("SHA-256").digest((origin + "\n" + tokens.tokenFor(origin).orEmpty()).toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        prefs.getString(scope, null)?.also { if (!validComfyId(it)) throw ComfyFailure("storage") }
    }
    override suspend fun write(id: String?) = withContext(Dispatchers.IO) {
        require(id == null || validComfyId(id))
        val edit = prefs.edit()
        if (id == null) edit.remove(scope) else edit.putString(scope, id)
        if (!edit.commit()) throw ComfyFailure("storage")
    }
}
