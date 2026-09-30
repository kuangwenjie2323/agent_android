@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.karewinkcloud.agentweb.client.ui

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
internal fun ChatMediaCard(media: MessageMedia, thumbnail: Boolean = false) {
    val open = LocalOpenMedia.current
    var revision by remember(media) { mutableIntStateOf(0) }
    if (!media.mime.startsWith("image/")) {
        OutlinedButton({ open(media) }, Modifier.heightIn(min = 48.dp).widthIn(max = 300.dp)) {
            Text("▤ ${media.name}", maxLines = 2)
        }
        return
    }
    val loaded by loadMedia(media, if (thumbnail) 160 else 800, revision)
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        // Reserve the stage before decoding so completed Markdown cannot shift when an image loads.
        modifier = (if (thumbnail) Modifier.size(64.dp) else Modifier.fillMaxWidth().height(240.dp))
            .combinedClickable(onClick = { if (loaded.error) revision++ else open(media) }, onLongClick = { open(media) })) {
        Box(contentAlignment = Alignment.Center) {
            loaded.bitmap?.let { Image(it.asImageBitmap(), media.name, if (thumbnail) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
                contentScale = if (thumbnail) ContentScale.Crop else ContentScale.Fit) }
                ?: Text(tr(if (loaded.error) S.output_failed else S.output_loading), Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
        }
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
