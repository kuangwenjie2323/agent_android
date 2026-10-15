package com.karewinkcloud.agentweb.client.data

import com.karewinkcloud.agentweb.client.core.*
import kotlinx.serialization.json.*
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale

/** A build published on the server for Settings > Check for updates. */
data class AppUpdate(val versionCode: Int, val versionName: String, val size: Long, val sha256: String, val notes: String) {
    /** When it was built, on the build machine's wall clock (version codes count its minutes since 2026-01-01). */
    val builtAt: String get() = buildTime(versionCode)

    companion object {
        fun from(data: JsonObject): AppUpdate? {
            if (data.boolean("available") != true) return null
            val code = data.long("versionCode")?.toInt() ?: return null
            return AppUpdate(code, data.string("versionName").orEmpty(), data.long("size") ?: 0,
                data.string("sha256").orEmpty(), data.string("notes").orEmpty())
        }

        private const val EPOCH_2026_UTC = 1_767_225_600_000L

        fun buildTime(versionCode: Int): String = SimpleDateFormat("MM-dd HH:mm", Locale.ROOT)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(Date(EPOCH_2026_UTC + versionCode * 60_000L))
    }
}

/** The downloaded file is not the published build (damaged, or replaced by a newer one meanwhile). */
class UpdateMismatchException : IOException("The downloaded build does not match the published one.")

/** Sending the diagnostic log to the server and fetching a published build. */
interface AppSupport {
    /** Sends the log parts in order; returns the upload id, which names its folder on the server. */
    suspend fun uploadLogs(parts: List<ByteArray>, meta: JsonObject, onProgress: (Int, Int) -> Unit = { _, _ -> }): String
    /** The published build, or null when the server offers none. */
    suspend fun latestUpdate(): AppUpdate?
    /** Downloads [update] to [destination] and checks it is exactly the published file. */
    suspend fun downloadUpdate(update: AppUpdate, destination: File, onProgress: (Long, Long) -> Unit = { _, _ -> })
}

class HttpAppSupport(
    private val http: HttpAgentRepository,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: () -> String = { java.util.UUID.randomUUID().toString().replace("-", "").take(8) },
) : AppSupport {
    override suspend fun uploadLogs(parts: List<ByteArray>, meta: JsonObject, onProgress: (Int, Int) -> Unit): String {
        require(parts.isNotEmpty() && parts.size <= MAX_PARTS)
        val id = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(now())) + "-" + random()
        parts.forEachIndexed { index, part ->
            val body = buildJsonObject {
                put("uploadId", id); put("part", index + 1); put("parts", parts.size)
                put("data", Base64.getEncoder().encodeToString(part))
                if (index == 0) put("meta", meta)
            }
            // A part may be sent again: the server keeps the last copy of each part number.
            var attempt = 0
            while (true) {
                try { http.json("/api/client-logs", body, timeoutSeconds = 60); break }
                catch (e: IOException) { if (++attempt >= 3) throw e }
            }
            onProgress(index + 1, parts.size)
        }
        return id
    }

    override suspend fun latestUpdate(): AppUpdate? = AppUpdate.from(http.json("/api/android/update"))

    override suspend fun downloadUpdate(update: AppUpdate, destination: File, onProgress: (Long, Long) -> Unit) {
        destination.parentFile?.mkdirs()
        http.download("/api/android/update/apk", destination, APK_MIME, MAX_APK_BYTES, onProgress)
        val digest = destination.inputStream().use { input ->
            val sha = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; sha.update(buffer, 0, count) }
            sha.digest().joinToString("") { "%02x".format(it) }
        }
        if (!digest.equals(update.sha256, ignoreCase = true)) {
            destination.delete()
            throw UpdateMismatchException()
        }
    }

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        const val MAX_APK_BYTES = 300L * 1024 * 1024
        /** Mirrors the server: each part stays well under the 64 KB API body limit after base64. */
        const val PART_BYTES = 36 * 1024
        const val MAX_PARTS = 64
        const val NOTE_CHARS = 1000
    }
}
