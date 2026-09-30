package com.karewinkcloud.agentweb.client.core

import java.util.Base64
import kotlinx.serialization.json.*

const val MAX_ATTACHMENTS = 4
const val MAX_ATTACHMENT_BYTES = 8 * 1024 * 1024
const val MAX_ATTACHMENTS_BYTES = 32 * 1024 * 1024

enum class AttachmentKind { IMAGE, FILE }
data class Attachment(val id: String, val name: String, val mime: String, val size: Int, val dataUrl: String,
    val kind: AttachmentKind, val previewPath: String? = null) {
    fun json() = buildJsonObject { put("name", name); put("data_url", dataUrl) }
    fun media() = MessageMedia(name, mime, localPath = previewPath, size = size.toLong())
}
fun attachmentError(sizes: List<Int>): String? = when {
    sizes.size > MAX_ATTACHMENTS -> "每条消息最多添加 4 个附件"
    sizes.any { it < 0 || it > MAX_ATTACHMENT_BYTES } -> "单个附件不能超过 8 MB"
    sizes.sumOf { it.toLong() } > MAX_ATTACHMENTS_BYTES -> "附件总大小不能超过 32 MB"
    else -> null
}
fun encodeAttachment(name: String, mime: String, bytes: ByteArray, kind: AttachmentKind,
    previewPath: String? = null): Attachment {
    require(attachmentError(listOf(bytes.size)) == null) { "单个附件不能超过 8 MB" }
    val safeMime = mime.lowercase().takeIf { it.matches(Regex("[a-z0-9.+-]+/[a-z0-9.+-]+")) } ?: "application/octet-stream"
    return Attachment(newControlId(), name.replace(Regex("[\\p{Cntrl}/\\\\]"), "_").take(80).ifBlank { "file" }, safeMime, bytes.size,
        "data:$safeMime;base64," + Base64.getEncoder().encodeToString(bytes), kind, previewPath)
}
fun attachmentFields(attachments: List<Attachment>): Map<String, JsonElement> {
    require(attachmentError(attachments.map { it.size }) == null) { "附件超出限制" }
    return buildMap {
        attachments.filter { it.kind == AttachmentKind.IMAGE }.takeIf { it.isNotEmpty() }?.let { put("images", JsonArray(it.map(Attachment::json))) }
        attachments.filter { it.kind == AttachmentKind.FILE }.takeIf { it.isNotEmpty() }?.let { put("files", JsonArray(it.map(Attachment::json))) }
    }
}
fun attachmentMessage(text: String, attachments: List<Attachment>) = text.ifBlank {
    when { attachments.any { it.kind == AttachmentKind.IMAGE } -> "(image)"; attachments.isNotEmpty() -> "(file)"; else -> "" }
}

data class MessageMedia(val name: String, val mime: String, val url: String? = null, val localPath: String? = null, val size: Long = 0) {
    companion object {
        fun from(j: JsonObject): MessageMedia {
            val key = j.string("key")
            return MessageMedia(j.string("name") ?: "file", j.string("mime") ?: "application/octet-stream",
                if (!key.isNullOrBlank()) "/api/files/$key" else j.string("url") ?: j.string("data_url"), size = j.long("size") ?: 0)
        }
    }
}
