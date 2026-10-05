package com.karewinkcloud.agentweb.client.ui

import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.LocalActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.karewinkcloud.agentweb.client.R
import com.karewinkcloud.agentweb.client.R.string as S
import com.karewinkcloud.agentweb.client.core.*
import kotlinx.coroutines.*
import java.io.File

private data class MediaState(val file: File? = null, val bitmap: Bitmap? = null, val failed: Boolean = false)
private fun decodePreview(file: File, size: Int): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth !in 1..32768 || bounds.outHeight !in 1..32768) throw ComfyFailure("media")
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > size) sample *= 2
    return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: throw ComfyFailure("media")
}
@Composable
private fun mediaState(job: ComfyJob, output: ComfyOutput, vm: ComfyViewModel, size: Int, revision: Int = 0, preview: Int = 0): State<MediaState> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(MediaState(), job.requestId, output, vm, revision, lifecycle, preview) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (value.file == null || value.failed) {
                value = MediaState()
                try {
                    val file = if (preview > 0) vm.preview(job, output, preview) else vm.output(job, output)
                    // A preview is always a JPEG (an image or a video poster); originals decode only when they are images.
                    val bitmap = if (preview > 0 || output.mime.startsWith("image/")) withContext(Dispatchers.IO) { runCatching { decodePreview(file, size) }.getOrNull() } else null
                    value = MediaState(file, bitmap)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { value = MediaState(failed = true) }
            }
        }
    }
}

@Composable
internal fun ComfyThumbnail(job: ComfyJob, vm: ComfyViewModel, modifier: Modifier = Modifier) {
    val output = job.outputs.firstOrNull { it.mime.startsWith("image/") } ?: job.outputs.firstOrNull { it.thumbUrl != null || it.previewUrl != null }
    val bitmap = if (output != null) mediaState(job, output, vm, 480, preview = 512).value.bitmap else null
    Box(modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center) {
        Crossfade(bitmap, label = "creation preview", modifier = Modifier.fillMaxSize()) { image ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (image != null) Image(image.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else AppIcon(if (job.outputs.any { it.mime.startsWith("video/") }) R.drawable.aw_play else R.drawable.aw_create)
            }
        }
    }
}

@Composable
internal fun ComfyOutputView(job: ComfyJob, output: ComfyOutput, vm: ComfyViewModel) {
    var revision by remember(job.requestId, output.index) { mutableIntStateOf(0) }
    val image = output.mime.startsWith("image/")
    val video = output.mime.startsWith("video/")
    // Images and posters display a preview; the original downloads only when an action needs it.
    val usesPreview = image || output.previewUrl != null || output.thumbUrl != null
    val media by mediaState(job, output, vm, 1280, revision, preview = if (usesPreview) 1280 else 0)
    var playing by remember(job.requestId, output.index) { mutableStateOf(false) }
    // Images display a preview; the original is downloaded only when an action needs it.
    var original by remember(job.requestId, output.index) { mutableStateOf<File?>(null) }
    var fetching by remember(job.requestId, output.index) { mutableStateOf(false) }
    val downloads by vm.downloadProgress.collectAsStateWithLifecycle()
    val percent = downloads["${job.requestId}-${output.index}"]?.let { (it * 100).toInt() }
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    var actionMessage by remember(job.requestId, output.index) { mutableStateOf<Int?>(null) }
    var publicLink by remember(job.requestId, output.index) { mutableStateOf(output.publicUrl) }
    var publishing by remember(job.requestId, output.index) { mutableStateOf(false) }
    var saved by remember(job.requestId, output.index) { mutableStateOf(false) }
    LaunchedEffect(actionMessage) {
        actionMessage?.let { android.widget.Toast.makeText(context, context.getString(it), android.widget.Toast.LENGTH_SHORT).show() }
    }
    @Suppress("DEPRECATION") val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var saving by remember { mutableStateOf(false) }
    val file = media.file
    suspend fun originalFile(): File = original ?: (if (usesPreview) vm.output(job, output) else requireNotNull(file)).also { original = it }
    fun withOriginal(block: suspend (File) -> Unit) = scope.launch {
        fetching = true
        val source = try { originalFile() } catch (e: CancellationException) { throw e } catch (_: Exception) { null } finally { fetching = false }
        if (source == null) actionMessage = S.output_failed else block(source)
    }
    var viewing by remember(job.requestId, output.index) { mutableStateOf(false) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(output.mime)) { uri ->
        val file = original
        if (uri != null && file != null) scope.launch {
            saving = true
            try { withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("No destination")
            }; actionMessage = S.output_saved; saved = true }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { actionMessage = S.save_failed }
            finally { saving = false }
        }
    }
    fun mediaIntent(action: String, file: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.comfy-output", file)
            val intent = Intent(action).apply {
                if (action == Intent.ACTION_SEND) { type = output.mime; putExtra(Intent.EXTRA_STREAM, uri) }
                else setDataAndType(uri, output.mime)
                clipData = ClipData.newRawUri("AgentWeb", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val launch = if (action == Intent.ACTION_SEND) Intent.createChooser(intent, null) else intent
            if (activity == null) launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            (activity ?: context).startActivity(launch)
        } catch (_: Exception) { actionMessage = S.output_open_failed }
    }
    if (viewing) media.bitmap?.let { ZoomViewer(it.asImageBitmap()) { viewing = false } }
    // Videos stream from cloud storage when they have a link, otherwise from the downloaded file.
    if (playing) VideoPlayer(output.originalUrl?.let(android.net.Uri::parse) ?: original?.let(android.net.Uri::fromFile)) { playing = false }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            media.failed -> TextButton({ revision++ }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(tr(S.output_failed)) }
            // Show the tile's small preview (usually cached) while the original downloads.
            file == null -> {
                val preview = if (output.mime.startsWith("image/")) mediaState(job, output, vm, 640, preview = 512).value.bitmap else null
                val ratio = preview?.let { it.width.toFloat() / it.height.coerceAtLeast(1) }?.coerceIn(.5f, 2f) ?: 1f
                Box(Modifier.fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                if (preview != null) Image(preview.asImageBitmap(), tr(S.output_preview), Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
            } }
            video && media.bitmap != null -> Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .clickable(onClickLabel = tr(S.open_video)) {
                    if (output.originalUrl != null) playing = true else withOriginal { playing = true }
                }, contentAlignment = Alignment.Center) {
                Image(media.bitmap!!.asImageBitmap(), tr(S.output_preview), Modifier.fillMaxWidth().heightIn(max = 480.dp), contentScale = ContentScale.Fit)
                PlayBadge()
            }
            media.bitmap != null -> Image(media.bitmap!!.asImageBitmap(), tr(S.output_preview),
                Modifier.fillMaxWidth().heightIn(max = 480.dp).clip(RoundedCornerShape(16.dp))
                    .clickable(onClickLabel = tr(S.output_open)) { viewing = true }, contentScale = ContentScale.Fit)
            video -> AppleButton({ original = file; playing = true }, Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                AppIcon(R.drawable.aw_play); Spacer(Modifier.width(8.dp)); Text(tr(S.open_video))
            }
            else -> AudioOutput(file)
        }
        if (percent != null || fetching) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (percent != null) tr(S.output_fetching_original_percent, percent) else tr(S.output_fetching_original),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (percent != null) LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.weight(1f))
        }
        if (file != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
            AppleButton(onClick = {
                if (Build.VERSION.SDK_INT < 29) withOriginal { file ->
                    try { saver.launch("AgentWeb-${job.requestId.take(8)}-${output.index}.${file.extension}") }
                    catch (_: Exception) { actionMessage = S.save_failed }
                }
                else withOriginal { file ->
                    saving = true
                    try {
                        withContext(Dispatchers.IO) {
                            val collection = when {
                                output.mime.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                                output.mime.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                                else -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                            }
                            val directory = when {
                                output.mime.startsWith("image/") -> "Pictures"; output.mime.startsWith("video/") -> "Movies"; else -> "Music"
                            }
                            val values = ContentValues().apply {
                                put(MediaStore.MediaColumns.DISPLAY_NAME, "AgentWeb-${job.requestId.take(8)}-${output.index}.${file.extension}")
                                put(MediaStore.MediaColumns.MIME_TYPE, output.mime)
                                put(MediaStore.MediaColumns.RELATIVE_PATH, "$directory/AgentWeb")
                                put(MediaStore.MediaColumns.IS_PENDING, 1)
                            }
                            val resolver = context.contentResolver
                            val uri = resolver.insert(collection, values) ?: error("No destination")
                            try {
                                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("No output stream")
                                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                            } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
                        }
                        actionMessage = S.output_saved; saved = true
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { actionMessage = S.save_failed }
                    finally { saving = false }
                }
            }, enabled = !saving && !fetching, modifier = Modifier.weight(1f)) {
                AppIcon(R.drawable.aw_save); Spacer(Modifier.width(8.dp)); Text(tr(when { saving -> S.saving; saved -> S.output_saved_to_gallery; else -> S.save_output }))
            }
            Spacer(Modifier.width(8.dp))
            AppleButton({ withOriginal { mediaIntent(Intent.ACTION_SEND, it) } }, Modifier.weight(1f), enabled = !fetching) {
                AppIcon(R.drawable.aw_share); Spacer(Modifier.width(8.dp)); Text(tr(S.share_output))
            }
        }
        if (file != null) TextButton(colors = quietButton(), onClick = {
            scope.launch {
                publishing = true
                try {
                    val link = publicLink ?: vm.publish(job, output).also { publicLink = it }
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(link))
                    actionMessage = S.public_link_copied
                } catch (e: CancellationException) { throw e }
                catch (e: ComfyFailure) { actionMessage = if (e.code == "publish_unavailable") S.publish_unavailable else S.publish_failed }
                catch (_: Exception) { actionMessage = S.publish_failed }
                finally { publishing = false }
            }
        }, enabled = !publishing, modifier = Modifier.fillMaxWidth()) {
            Text(tr(when { publishing -> S.publishing; publicLink != null -> S.copy_public_link; else -> S.create_public_link }))
        }
        actionMessage?.let { Text(tr(it), style = MaterialTheme.typography.bodySmall) }
        if (actionMessage == S.public_link_copied) publicLink?.let { Text(it, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2) }
    }
}

@Composable
private fun PlayBadge() {
    Surface(shape = androidx.compose.foundation.shape.CircleShape, color = androidx.compose.ui.graphics.Color.Black.copy(alpha = .55f),
        contentColor = androidx.compose.ui.graphics.Color.White) {
        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) { AppIcon(R.drawable.aw_play) }
    }
}

/** In-app full-screen player: streams the video (sound on, loops) with the system transport controls. */
@Composable
private fun VideoPlayer(uri: android.net.Uri?, onClose: () -> Unit) {
    androidx.compose.ui.window.Dialog(onClose, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        var ready by remember(uri) { mutableStateOf(false) }
        var failed by remember(uri) { mutableStateOf(uri == null) }
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black), contentAlignment = Alignment.Center) {
            if (uri != null) androidx.compose.ui.viewinterop.AndroidView({ context ->
                android.widget.VideoView(context).apply {
                    val controls = android.widget.MediaController(context)
                    controls.setAnchorView(this)
                    setMediaController(controls)
                    setOnPreparedListener { player -> player.isLooping = true; ready = true; start(); controls.show(2500) }
                    setOnErrorListener { _, _, _ -> failed = true; true }
                    setVideoURI(uri)
                }
            }, Modifier.fillMaxWidth(), onRelease = { it.stopPlayback() })
            if (!ready && !failed) CircularProgressIndicator(color = androidx.compose.ui.graphics.Color.White)
            if (failed) Text(tr(S.output_failed), color = androidx.compose.ui.graphics.Color.White)
            IconButton(onClose, Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp),
                colors = IconButtonDefaults.iconButtonColors(contentColor = androidx.compose.ui.graphics.Color.White)) {
                AppIcon(R.drawable.aw_close, tr(S.close))
            }
        }
    }
}

/** Full-screen preview with pinch zoom, pan and double-tap zoom; never downloads the original. */
@Composable
private fun ZoomViewer(bitmap: androidx.compose.ui.graphics.ImageBitmap, onClose: () -> Unit) {
    androidx.compose.ui.window.Dialog(onClose, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    offset = if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero else offset + pan
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f; if (scale == 1f) offset = androidx.compose.ui.geometry.Offset.Zero },
                    onTap = { if (scale == 1f) onClose() })
            }) {
            Image(bitmap, tr(S.output_preview), Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
            }, contentScale = ContentScale.Fit)
            IconButton(onClose, Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp),
                colors = IconButtonDefaults.iconButtonColors(contentColor = androidx.compose.ui.graphics.Color.White)) {
                AppIcon(R.drawable.aw_close, tr(S.close))
            }
        }
    }
}

@Composable
private fun AudioOutput(file: File) {
    var playing by remember(file) { mutableStateOf(false) }
    var ready by remember(file) { mutableStateOf(false) }
    var failed by remember(file) { mutableStateOf(false) }
    var player by remember(file) { mutableStateOf<MediaPlayer?>(null) }
    LifecycleStartEffect(file) {
        val media = MediaPlayer()
        player = media
        failed = false
        try {
            media.setOnPreparedListener { if (player === media) ready = true }
            media.setOnCompletionListener { if (player === media) playing = false }
            media.setOnErrorListener { _, _, _ ->
                if (player === media) { failed = true; playing = false; ready = false }; true
            }
            media.setDataSource(file.absolutePath)
            media.prepareAsync()
        } catch (_: Exception) { failed = true }
        onStopOrDispose { player = null; ready = false; playing = false; media.release() }
    }
    FilledTonalButton({ player?.let { if (playing) it.pause() else it.start(); playing = !playing } },
        Modifier.fillMaxWidth().heightIn(min = 64.dp), enabled = ready && !failed) {
        AppIcon(if (playing) R.drawable.aw_pause else R.drawable.aw_play)
        Spacer(Modifier.width(8.dp)); Text(tr(if (failed) S.output_failed else if (playing) S.pause_audio else S.play_audio))
    }
}
