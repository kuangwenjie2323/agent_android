package com.karewinkcloud.agentweb.client.data

import com.karewinkcloud.agentweb.client.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.security.MessageDigest

data class MediaTarget(val url: HttpUrl, val authenticated: Boolean)
fun mediaTarget(origin: String, value: String): MediaTarget? {
    val base = origin.toHttpUrlOrNull() ?: return null
    val url = base.resolve(value) ?: return null
    if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) return null
    val sameOrigin = url.scheme == base.scheme && url.host == base.host && url.port == base.port
    val authenticated = sameOrigin && url.encodedPath.startsWith("/api/files/")
    if (!authenticated && (!url.isHttps || !value.startsWith("https://", true))) return null
    return MediaTarget(url, authenticated)
}

/** Chat pictures and files kept on the phone (the user asked for up to 1 GB). */
const val CHAT_MEDIA_CACHE_BYTES = 1024L * 1024 * 1024

data class CachedMedia(val file: File, val mime: String)
class ChatMediaStore(private val repository: HttpAgentRepository, cache: File) {
    // One folder per server + credential, kept across launches so pictures already seen show at once
    // (each used to cost a fresh download after every restart); another account's downloads are never
    // reused. Without a credential (local testing) each launch gets its own folder.
    private val root = File(cache, "chat-media/${repository.credentialKey() ?: newControlId()}").apply { mkdirs() }
    private val permits = Semaphore(2)
    private val locks = Array(32) { Mutex() }
    private val downloads = mutableMapOf<String, CachedMedia>()
    fun isFileLink(url: String) = mediaTarget(repository.origin, url)?.authenticated == true
    suspend fun load(media: MessageMedia): CachedMedia = permits.withPermit { withContext(Dispatchers.IO) {
        media.localPath?.let { path ->
            val file = File(path)
            require(file.canonicalFile.parentFile == File(root.parentFile.parentFile, "chat-input").canonicalFile)
            require(file.isFile)
            return@withContext CachedMedia(file, media.mime)
        }
        val url = requireNotNull(media.url) { "附件没有可下载地址" }
        // Thumbnail and full-screen requests for one URL must never write the same file concurrently.
        locks[(url.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            synchronized(downloads) { downloads[url] }?.takeIf { it.file.isFile }?.let { return@withLock it }
            val target = requireNotNull(mediaTarget(repository.origin, url)) { "不支持此附件地址" }
            val key = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
            val file = File(root, key); val type = File(root, "$key.type")
            val cached = if (file.isFile && type.isFile) CachedMedia(file, type.readText()).also { file.setLastModified(System.currentTimeMillis()) }
            else {
                // Written aside and renamed, so a download cut off by the app being killed is never taken as complete.
                val part = File(root, "$key.part")
                val mime = repository.downloadChatMedia(target, part, 32L * 1024 * 1024)
                type.writeText(mime)
                if (!part.renameTo(file)) { part.delete(); throw java.io.IOException("Media could not be saved") }
                // Cache is private and disposable; a missing evicted file is fetched again on demand.
                pruneChatCache(root.parentFile, file, CHAT_MEDIA_CACHE_BYTES)
                CachedMedia(file, mime)
            }
            cached.also { synchronized(downloads) {
                if (downloads.size >= 128) downloads.remove(downloads.keys.first())
                downloads[url] = it
            } }
        }
    } }
}

internal fun pruneChatCache(directory: File, keep: File, maxBytes: Long) {
    val files = directory.walkTopDown().filter { it.isFile }.toList()
    var bytes = files.sumOf { it.length() }
    files.sortedBy { it.lastModified() }.forEach { old ->
        if (bytes > maxBytes && old != keep) { val size = old.length(); if (old.delete()) bytes -= size }
    }
}
