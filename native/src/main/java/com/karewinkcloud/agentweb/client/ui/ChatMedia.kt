@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.karewinkcloud.agentweb.client.ui

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.karewinkcloud.agentweb.client.R
import com.karewinkcloud.agentweb.client.R.string as S
import com.karewinkcloud.agentweb.client.core.MessageMedia
import com.karewinkcloud.agentweb.client.data.*
import kotlinx.coroutines.*

internal val LocalChatMedia = staticCompositionLocalOf<ChatMediaStore?> { null }
internal val LocalOpenMedia = staticCompositionLocalOf<(MessageMedia) -> Unit> { {} }
internal val LocalOpenLink = staticCompositionLocalOf<(String) -> Unit> { {} }

@Composable
fun ChatMediaHost(store: ChatMediaStore, browser: (String) -> Unit, content: @Composable () -> Unit) {
    var selected by remember(store) { mutableStateOf<MessageMedia?>(null) }
    val context = LocalContext.current
    val openLink: (String) -> Unit = remember(store, browser, context) { { url ->
        if (store.isFileLink(url)) selected = MessageMedia(url.substringAfterLast('/').substringBefore('?').ifBlank { "file" }, "application/octet-stream", url)
        else if (url.startsWith("https://", true) || url.startsWith("http://", true)) browser(url)
        else if (url.startsWith("mailto:", true)) runCatching {
            context.startActivity(Intent(Intent.ACTION_SENDTO, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    } }
    CompositionLocalProvider(LocalChatMedia provides store, LocalOpenMedia provides { selected = it }, LocalOpenLink provides openLink) {
        content()
        selected?.let { MediaDialog(it) { selected = null } }
    }
}

private data class LoadedMedia(val cached: CachedMedia? = null, val bitmap: Bitmap? = null, val error: Boolean = false)
@Composable
private fun loadMedia(media: MessageMedia, size: Int, revision: Int = 0): State<LoadedMedia> {
    val store = LocalChatMedia.current
    return produceState(LoadedMedia(), store, media, size, revision) {
        value = LoadedMedia()
        try {
            val cached = store?.load(media) ?: return@produceState
            val bitmap = if (cached.mime.startsWith("image/") || media.mime.startsWith("image/")) withContext(Dispatchers.IO) { decodeChatBitmap(cached.file, size) } else null
            value = LoadedMedia(cached, bitmap)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { value = LoadedMedia(error = true) }
    }
}

@Composable
internal fun ChatMediaCard(media: MessageMedia, thumbnail: Boolean = false, height: Dp = 240.dp) {
    val open = LocalOpenMedia.current
    var revision by remember(media) { mutableIntStateOf(0) }
    if (!media.mime.startsWith("image/")) { FileCard(media); return }
    val loaded by loadMedia(media, if (thumbnail) 160 else 800, revision)
    val bitmap = loaded.bitmap
    // A fixed height keeps the conversation from jumping when the picture decodes; the width follows the
    // picture's own shape (clamped), so a phone screenshot is a tall card, not a small image in a wide frame.
    val aspect = bitmap?.let { (it.width.toFloat() / it.height).coerceIn(.45f, 2.2f) } ?: .75f
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = (if (thumbnail) Modifier.size(64.dp) else Modifier.height(height).width(height * aspect))
            .combinedClickable(onClick = { if (loaded.error) revision++ else open(media) }, onLongClick = { open(media) })) {
        Box(contentAlignment = Alignment.Center) {
            bitmap?.let { Image(it.asImageBitmap(), media.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                ?: if (loaded.error) Text(tr(S.output_failed), Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
                else CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        }
    }
}

/** A sent or received file: icon, name and size in a compact card that opens it. */
@Composable
internal fun FileCard(media: MessageMedia) {
    val open = LocalOpenMedia.current
    Surface(onClick = { open(media) }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.widthIn(min = 180.dp, max = 280.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .14f), modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) { AppIcon(R.drawable.aw_file) }
            }
            Column(Modifier.weight(1f, fill = false)) {
                Text(media.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, style = MaterialTheme.typography.bodyMedium)
                Text(listOfNotNull(media.name.substringAfterLast('.', "").takeIf { it.isNotBlank() && it.length <= 6 }?.uppercase(),
                        media.size.takeIf { it > 0 }?.let(::fileSize)).joinToString(" · ").ifBlank { media.mime },
                    maxLines = 1, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

internal fun fileSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

/** The user's pictures and files above their bubble, right-aligned; several pictures share a row. */
@Composable
internal fun UserAttachments(media: List<MessageMedia>) {
    val images = media.filter { it.mime.startsWith("image/") }
    val files = media - images.toSet()
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (images.size == 1) ChatMediaCard(images.single(), height = 220.dp)
        else if (images.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(6.dp)) { images.forEach { ChatMediaCard(it, height = 140.dp) } }
        files.forEach { FileCard(it) }
    }
}

@Composable
private fun MediaDialog(media: MessageMedia, close: () -> Unit) {
    val loaded by loadMedia(media, 2048)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember(media) { mutableStateOf<Int?>(null) }
    val cached = loaded.cached
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(cached?.mime ?: media.mime)) { uri ->
        if (uri != null && cached != null) scope.launch {
            try {
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { out -> cached.file.inputStream().use { it.copyTo(out) } } ?: error("No destination") }
                status = S.output_saved
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { status = S.save_failed }
        }
    }
    fun launch(action: String) {
        if (cached == null) return
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.comfy-output", cached.file)
            val intent = Intent(action).apply {
                if (action == Intent.ACTION_SEND) { type = cached.mime; putExtra(Intent.EXTRA_STREAM, uri) }
                else setDataAndType(uri, cached.mime)
                clipData = ClipData.newRawUri(media.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity((if (action == Intent.ACTION_SEND) Intent.createChooser(intent, null) else intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { status = S.output_open_failed }
    }
    Dialog(close, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Toolbar(media.name, onBack = close)
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    loaded.bitmap?.let { Image(it.asImageBitmap(), media.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                        ?: Text(if (cached != null) media.name else tr(if (loaded.error) S.output_failed else S.output_loading), Modifier.padding(24.dp))
                }
                status?.let { Text(tr(it), Modifier.padding(16.dp)) }
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton({ launch(Intent.ACTION_VIEW) }, enabled = cached != null, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr(S.open_attachment)) }
                    TextButton({ saver.launch(media.name) }, enabled = cached != null, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr(S.save_output)) }
                    TextButton({ launch(Intent.ACTION_SEND) }, enabled = cached != null, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr(S.share_output)) }
                }
            }
        }
    }
}
