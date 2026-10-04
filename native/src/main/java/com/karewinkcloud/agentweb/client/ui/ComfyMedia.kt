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
private fun mediaState(job: ComfyJob, output: ComfyOutput, vm: ComfyViewModel, size: Int, revision: Int = 0): State<MediaState> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(MediaState(), job.requestId, output, vm, revision, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (value.file == null || value.failed) {
                value = MediaState()
                try {
                    val file = vm.output(job, output)
                    val bitmap = if (output.mime.startsWith("image/")) withContext(Dispatchers.IO) { decodePreview(file, size) } else null
                    value = MediaState(file, bitmap)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { value = MediaState(failed = true) }
            }
        }
    }
}

@Composable
internal fun ComfyThumbnail(job: ComfyJob, vm: ComfyViewModel, modifier: Modifier = Modifier) {
    val output = job.outputs.firstOrNull { it.mime.startsWith("image/") }
    val bitmap = if (output != null) mediaState(job, output, vm, 480).value.bitmap else null
    Box(modifier.size(52.dp).clip(RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
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
    val media by mediaState(job, output, vm, 1280, revision)
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
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(output.mime)) { uri ->
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
    fun mediaIntent(action: String) {
        if (file == null) return
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            media.failed -> TextButton({ revision++ }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(tr(S.output_failed)) }
            file == null -> Text(tr(S.output_loading), Modifier.padding(vertical = 24.dp), style = MaterialTheme.typography.bodyMedium)
            media.bitmap != null -> Image(media.bitmap!!.asImageBitmap(), tr(S.output_preview),
                Modifier.fillMaxWidth().heightIn(max = 480.dp).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Fit)
            output.mime.startsWith("video/") -> FilledTonalButton({ mediaIntent(Intent.ACTION_VIEW) }, Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                AppIcon(R.drawable.aw_play); Spacer(Modifier.width(8.dp)); Text(tr(S.open_video))
            }
            else -> AudioOutput(file)
        }
        if (file != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
            FilledTonalButton(onClick = {
                if (Build.VERSION.SDK_INT < 29) {
                    try { saver.launch("AgentWeb-${job.requestId.take(8)}-${output.index}.${file.extension}") }
                    catch (_: Exception) { actionMessage = S.save_failed }
                }
                else scope.launch {
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
            }, enabled = !saving, modifier = Modifier.weight(1f)) {
                AppIcon(R.drawable.aw_save); Spacer(Modifier.width(8.dp)); Text(tr(when { saving -> S.saving; saved -> S.output_saved_to_gallery; else -> S.save_output }))
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton({ mediaIntent(Intent.ACTION_SEND) }, Modifier.weight(1f)) {
                AppIcon(R.drawable.aw_share); Spacer(Modifier.width(8.dp)); Text(tr(S.share_output))
            }
        }
        if (file != null) TextButton(onClick = {
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
