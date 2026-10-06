package com.karewinkcloud.agentweb.client.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import com.karewinkcloud.agentweb.client.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

internal fun InputStream.boundedBytes(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(32 * 1024)
    while (true) {
        val n = read(buffer); if (n < 0) break
        require(out.size().toLong() + n <= limit) { "单个附件不能超过 8 MB（照片原图最大 64 MB）" }
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

/** Sampling bounds bitmap memory; re-encoding retains orientation and discards all EXIF metadata. */
suspend fun readAttachment(context: Context, uri: Uri, image: Boolean): Attachment = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    var name = if (image) "photo.jpg" else "file"
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) name = cursor.getString(0) ?: name
    }
    var mime = resolver.getType(uri) ?: "application/octet-stream"
    val bytes = if (!image) resolver.openInputStream(uri)?.use { it.boundedBytes(MAX_ATTACHMENT_BYTES) }
        ?: error("无法读取附件，请重新选择") else {
        val (photoMime, encoded) = readPhoto(context, uri)
        mime = photoMime
        name = name.substringBeforeLast('.', name) + if (photoMime == "image/png") ".png" else ".jpg"
        encoded
    }
    val directory = File(context.cacheDir, "chat-input").apply { mkdirs() }
    // Never retain source EXIF/camera data. This file contains only the encoded payload.
    val preview = File(directory, newControlId()).apply { writeBytes(bytes) }
    encodeAttachment(name, mime, bytes, if (image) AttachmentKind.IMAGE else AttachmentKind.FILE, preview.absolutePath)
}

/** A picked photo re-encoded at most 2048 px (8 MB): orientation applied, EXIF dropped; PNG only with transparency. */
internal suspend fun readPhoto(context: Context, uri: Uri): Pair<String, ByteArray> = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    // Large original photos stream to disk; only the sampled bitmap enters memory.
    val source = File.createTempFile("photo-import-", ".tmp", context.cacheDir)
    try {
        resolver.openInputStream(uri)?.use { input -> source.outputStream().use { out ->
            val buffer = ByteArray(32 * 1024); var total = 0L
            while (true) {
                ensureActive()
                val count = input.read(buffer); if (count < 0) break
                total += count
                require(total <= 64L * 1024 * 1024) { "照片原图不能超过 64 MB" }
                out.write(buffer, 0, count)
            }
        } } ?: error("无法读取附件，请重新选择")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        require(bounds.outWidth in 1..65536 && bounds.outHeight in 1..65536) { "无法读取这张照片，请选择 JPEG、PNG 或 WebP" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        var bitmap = BitmapFactory.decodeFile(source.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("无法解码照片")
        val orientation = runCatching { ExifInterface(source.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
        val matrix = Matrix().apply { when (orientation) {
            2 -> setScale(-1f, 1f); 3 -> setRotate(180f); 4 -> setScale(1f, -1f)
            5 -> { setRotate(90f); postScale(-1f, 1f) }; 6 -> setRotate(90f)
            7 -> { setRotate(-90f); postScale(-1f, 1f) }; 8 -> setRotate(-90f)
        } }
        if (!matrix.isIdentity) {
            val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (oriented !== bitmap) bitmap.recycle()
            bitmap = oriented
        }
        try {
            val alpha = bitmap.hasAlpha()
            var encoded: ByteArray
            while (true) {
                encoded = ByteArrayOutputStream().use { out ->
                    check(bitmap.compress(if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 88, out))
                    out.toByteArray()
                }
                if (encoded.size <= MAX_ATTACHMENT_BYTES) break
                require(bitmap.width > 256 && bitmap.height > 256) { "照片压缩后仍超过 8 MB" }
                val smaller = Bitmap.createScaledBitmap(bitmap, (bitmap.width * .75).toInt(), (bitmap.height * .75).toInt(), true)
                bitmap.recycle(); bitmap = smaller
            }
            (if (alpha) "image/png" else "image/jpeg") to encoded
        } finally { bitmap.recycle() }
    } finally { source.delete() }
}

internal fun decodeChatBitmap(file: File, size: Int): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    require(bounds.outWidth in 1..65536 && bounds.outHeight in 1..65536) { "无法解码图片" }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > size) sample *= 2
    return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("无法解码图片")
}
