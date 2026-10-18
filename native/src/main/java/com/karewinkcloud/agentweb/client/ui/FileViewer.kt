package com.karewinkcloud.agentweb.client.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.karewinkcloud.agentweb.client.R
import com.karewinkcloud.agentweb.client.R.string as S
import com.karewinkcloud.agentweb.client.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File

/*
 * The file pane of Claude's desktop app, for the phone: a file the chat names opens at full length, at the
 * line it was named with. Markdown and web pages show rendered (their source a tap away), pictures zoom, PDFs
 * show page by page, and the file can be shared or opened in another app.
 */

/** What the chat screen lends its messages: opening a file they name. Null where no chat can read files. */
internal val LocalOpenFile = staticCompositionLocalOf<((FileRef) -> Unit)?> { null }

/** Lines longer than this are shown in pieces, so one minified line cannot stall the screen. */
private const val ROW_CHARS = 2000
/** Markdown longer than this opens as text: the rendered view lays all of it out at once. */
private const val MAX_RENDERED_MARKDOWN = 300_000

@Composable
internal fun FileViewer(ref: FileRef, load: suspend (String) -> FileContent,
    editsFor: (String) -> List<List<DiffRow>> = { emptyList() }, onClose: () -> Unit) {
    var revision by remember(ref) { mutableIntStateOf(0) }
    var content by remember(ref) { mutableStateOf<FileContent?>(null) }
    var failure by remember(ref) { mutableStateOf<Throwable?>(null) }
    var busy by remember(ref) { mutableStateOf(true) }
    LaunchedEffect(ref, revision) {
        busy = true
        try { content = load(ref.path); failure = null }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { failure = e }
        busy = false
    }
    // The chat's edits to this file, by the path it was opened with or the one the computer resolved it to.
    val asked = remember(ref) { editsFor(ref.path) }
    val edits = remember(asked, content?.path) { asked.ifEmpty { content?.path?.let(editsFor).orEmpty() } }
    val preview = content?.let(::filePreview)
    val rendered = preview == FilePreview.WEB || (preview == FilePreview.MARKDOWN && (content?.bytes?.size ?: 0) <= MAX_RENDERED_MARKDOWN)
    // A file opened at a line shows that line in its source, and one this chat edited shows its edits there.
    var source by rememberSaveable(ref) { mutableStateOf(ref.line != null || asked.isNotEmpty()) }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                FileHeader(ref, content, busy, rendered, source, { source = !source }, { revision++ }, onClose)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val shown = content
                val error = failure
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        shown != null -> Column(Modifier.fillMaxSize()) {
                            if (shown.truncated) Text(tr(S.file_truncated, fileSize(shown.bytes.size.toLong()), fileSize(shown.size)),
                                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 16.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (error != null) Text(fileErrorText(error), Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                when {
                                    preview == FilePreview.IMAGE -> ZoomableImage(shown)
                                    preview == FilePreview.PDF -> PdfPages(shown)
                                    rendered && !source && preview == FilePreview.MARKDOWN -> SelectionContainer {
                                        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                                            Markdown(remember(shown) { markdownForPreview(shown.text) })
                                        }
                                    }
                                    rendered && !source -> WebPreview(shown)
                                    else -> TextLines(shown, ref.line, edits)
                                }
                            }
                        }
                        error != null -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(fileErrorText(error), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                            if (retryable(error)) TextButton({ revision++ }, Modifier.heightIn(min = 48.dp)) { Text(tr(S.retry)) }
                        }
                        else -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), strokeWidth = 2.5.dp)
                    }
                }
            }
        }
    }
}

@Suppress("DEPRECATION")
@Composable
private fun FileHeader(ref: FileRef, content: FileContent?, busy: Boolean, rendered: Boolean, source: Boolean, onSource: () -> Unit,
    onReload: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var menu by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<Int?>(null) }
    val path = content?.path ?: ref.path
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        ActionIcon(R.drawable.aw_back, tr(S.back), onClick = onClose)
        Column(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(fileName(path), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            // Size and time stay whole; a long folder after them is shortened in its middle.
            val facts = listOfNotNull(content?.size?.let(::fileSize),
                content?.modifiedAt?.takeIf { it > 0 }?.let { tr(S.file_modified, conversationTime(it / 1000)) }).joinToString(" · ")
            val folder = path.substringBeforeLast('/', "").ifBlank { null }
            val small = MaterialTheme.typography.bodySmall
            Row {
                if (note != null || facts.isNotEmpty()) Text(note?.let { tr(it) } ?: facts, maxLines = 1, style = small,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (note == null && folder != null) Text((if (facts.isEmpty()) "" else " · ") + folder, Modifier.weight(1f, fill = false),
                    maxLines = 1, overflow = TextOverflow.MiddleEllipsis, style = small, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (rendered) TextButton(onSource, Modifier.heightIn(min = 48.dp)) { Text(tr(if (source) S.file_show_preview else S.file_show_source)) }
        if (busy && content != null) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else ActionIcon(R.drawable.aw_refresh, tr(S.refresh), enabled = !busy, onClick = onReload)
        Box {
            ActionIcon(R.drawable.aw_more, tr(S.more)) { menu = true }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text(tr(S.file_copy_path)) }, onClick = {
                    menu = false; clipboard.setText(AnnotatedString(path)); note = S.copied
                })
                if (content != null && content.kind == "text") DropdownMenuItem(text = { Text(tr(S.file_copy_text)) }, onClick = {
                    menu = false; clipboard.setText(AnnotatedString(content.text)); note = S.copied
                })
                if (content != null) {
                    DropdownMenuItem(text = { Text(tr(S.file_share)) }, onClick = {
                        menu = false; if (!handOver(context, content, Intent.ACTION_SEND)) note = S.output_open_failed
                    })
                    DropdownMenuItem(text = { Text(tr(S.file_open_with)) }, onClick = {
                        menu = false; if (!handOver(context, content, Intent.ACTION_VIEW)) note = S.output_open_failed
                    })
                }
            }
        }
    }
    LaunchedEffect(note) { if (note != null) { kotlinx.coroutines.delay(2000); note = null } }
}

private fun retryable(error: Throwable) = (error as? ApiException)?.problem?.code !in
    setOf("file_private", "file_not_in_chat", "not_previewable", "file_too_large", "not_a_file", "bad_path")

@Composable
private fun fileErrorText(error: Throwable): String = when ((error as? ApiException)?.problem?.code) {
    "file_private" -> tr(S.file_private)
    "file_not_in_chat" -> tr(S.file_not_in_chat)
    "file_missing", "not_found" -> tr(S.file_missing)
    "not_previewable" -> tr(S.file_not_previewable)
    "file_too_large" -> tr(S.file_too_large)
    "not_a_file" -> tr(S.file_not_a_file)
    "file_unreadable" -> tr(S.file_unreadable)
    else -> if (error is ApiException && error.status != 404) localMessage(error.problem.message) else tr(S.file_load_failed)
}

/** Shares the file, or opens it in another app; false when no app could take it. */
private fun handOver(context: Context, content: FileContent, action: String): Boolean = runCatching {
    val folder = File(context.cacheDir, "chat-files").apply { mkdirs() }
    val file = File(folder, content.name.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").ifBlank { "file" })
    file.writeBytes(content.bytes)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.comfy-output", file)
    val mime = when {
        content.kind != "text" -> content.mime
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(content.name.substringAfterLast('.', "").lowercase())
            ?.takeIf { it.startsWith("text/") || it == "image/svg+xml" } ?: "text/plain"
    }
    val intent = Intent(action).apply {
        if (action == Intent.ACTION_SEND) { type = mime; putExtra(Intent.EXTRA_STREAM, uri) } else setDataAndType(uri, mime)
        clipData = ClipData.newRawUri(content.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity((if (action == Intent.ACTION_SEND) Intent.createChooser(intent, null) else intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}.isSuccess

/**
 * One row of the text view: a line's number (none for the rest of a long line), its text, whether this chat
 * added or rewrote it, and for a note row, how many lines the chat removed there.
 */
private data class TextRow(val number: Int?, val text: String, val added: Boolean = false, val removed: Int = 0)

@OptIn(ExperimentalSerializationApi::class)
private val prettyJson = Json { prettyPrint = true; prettyPrintIndent = "  " }

internal fun readableText(name: String, text: String): String {
    // A JSON file written on one line reads better laid out.
    if (name.endsWith(".json", true) && text.length in 200..2_000_000 && text.lineSequence().take(4).count() <= 3)
        runCatching { return prettyJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(text)) }
    return text
}

private fun textRows(lines: List<String>, changes: FileChanges): List<TextRow> = buildList {
    lines.forEachIndexed { index, raw ->
        val number = index + 1
        changes.removedBefore[number]?.let { add(TextRow(null, "", removed = it)) }
        val line = raw.replace("\t", "    ")
        val added = number in changes.added
        if (line.length <= ROW_CHARS) add(TextRow(number, line, added))
        else line.chunked(ROW_CHARS).forEachIndexed { piece, part -> add(TextRow(if (piece == 0) number else null, part, added)) }
    }
    changes.removedBefore[lines.size + 1]?.let { add(TextRow(null, "", removed = it)) }
}

/**
 * The file's text with line numbers; the line it was opened at is marked and in view. What this chat changed
 * shows as a diff does: added and rewritten lines green, a red note where lines were removed, with a bar to
 * step from one change to the next.
 */
@Composable
private fun TextLines(content: FileContent, line: Int?, edits: List<List<DiffRow>>) {
    val lines = remember(content) { readableText(content.name, content.text).removeSuffix("\n").split('\n').map { it.removeSuffix("\r") } }
    val changes = remember(lines, edits) { fileChanges(lines, edits) }
    val rows = remember(lines, changes) { textRows(lines, changes) }
    // Where each change starts, in row order: the "next change" stops.
    val stops = remember(rows) { rows.indices.filter { i -> rows[i].removed > 0 || (rows[i].added && (i == 0 || !rows[i - 1].added)) } }
    val target = line?.let { wanted -> rows.indexOfFirst { it.number == wanted } }?.takeIf { it >= 0 }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = ((target ?: stops.firstOrNull() ?: 0) - 3).coerceAtLeast(0))
    val scope = rememberCoroutineScope()
    val width = (rows.lastOrNull { it.number != null }?.number ?: 1).toString().length
    val mono = TextStyle(fontFamily = CodeFont, fontSize = 12.sp, lineHeight = 18.sp)
    val mark = MaterialTheme.colorScheme.primary.copy(alpha = .14f)
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    val addedBackground = if (dark) Color(0xff16341f) else Color(0xffe6f6e9)
    val addedInk = if (dark) Color(0xff7ee29a) else Color(0xff1f6b34)
    val removedBackground = if (dark) Color(0xff3c1618) else Color(0xffffebe9)
    val removedInk = if (dark) Color(0xffff9c97) else Color(0xffb3261e)
    if (rows.size == 1 && rows[0].text.isEmpty()) {
        Text(tr(S.file_empty), Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Column(Modifier.fillMaxSize()) {
        if (changes.any) Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr(S.file_changes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            DiffStats(changes.added.size, changes.removedBefore.values.sum())
            Spacer(Modifier.weight(1f))
            TextButton({
                val from = list.firstVisibleItemIndex + 3
                val next = stops.firstOrNull { it > from } ?: stops.first()
                scope.launch { list.animateScrollToItem((next - 3).coerceAtLeast(0)) }
            }, Modifier.heightIn(min = 40.dp)) { Text(tr(S.file_next_change)) }
        }
        SelectionContainer(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(vertical = 8.dp)) {
                itemsIndexed(rows) { index, row ->
                    val background = when {
                        index == target -> mark
                        row.removed > 0 -> removedBackground
                        row.added -> addedBackground
                        else -> Color.Transparent
                    }
                    Row(Modifier.fillMaxWidth().background(background).padding(end = 12.dp)) {
                        DisableSelection {
                            Text((row.number?.toString() ?: "").padStart(width), Modifier.padding(start = 10.dp, end = 4.dp), style = mono,
                                color = if (row.added) addedInk else MaterialTheme.colorScheme.outline)
                            Text(when { row.removed > 0 -> "−"; row.added -> "+"; else -> " " }, Modifier.width(12.dp), style = mono,
                                color = if (row.removed > 0) removedInk else addedInk)
                        }
                        if (row.removed > 0) DisableSelection {
                            Text(countLabel(R.plurals.file_lines_removed, row.removed), Modifier.weight(1f), style = mono, color = removedInk)
                        } else Text(row.text.ifEmpty { " " }, Modifier.weight(1f), style = mono,
                            color = if (row.added) addedInk else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

/**
 * A web page or SVG the agent made, drawn as a browser would: scripts run (charts, interactive pages), but the
 * page cannot read the phone's files, links open in the browser, and nothing it does reaches the chat.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebPreview(content: FileContent) {
    val openLink = LocalOpenLink.current
    val page = remember(content) {
        if (content.name.endsWith(".svg", true)) "<!doctype html><meta name=viewport content=\"width=device-width,initial-scale=1\">" +
            "<style>body{margin:0;padding:12px;display:flex;justify-content:center}svg{max-width:100%;height:auto}</style>" +
            content.text.replace(Regex("""^\s*<\?xml[^>]*>"""), "")
        else content.text
    }
    AndroidView(factory = { context ->
        WebView(context).apply {
            // A WebView sized to its content treats "100vh" as almost nothing; this one fills the viewer.
            layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.domStorageEnabled = false
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url.toString()
                    if (request.isForMainFrame && (url.startsWith("https://") || url.startsWith("http://"))) openLink(url)
                    return true
                }
            }
        }
    }, modifier = Modifier.fillMaxSize(), update = { view ->
        if (view.tag != page) { view.tag = page; view.loadDataWithBaseURL(null, page, "text/html", "utf-8", null) }
    }, onRelease = { it.destroy() })
}

private fun decodeBitmap(bytes: ByteArray, size: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth !in 1..65536 || bounds.outHeight !in 1..65536) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > size) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}

/** A picture fitted to the screen; pinch or double-tap to zoom. */
@Composable
private fun ZoomableImage(content: FileContent) {
    // (decoded, picture): a picture that would not decode says so instead of spinning on.
    val decoded by produceState<Pair<Boolean, Bitmap?>>(false to null, content) {
        value = true to withContext(Dispatchers.Default) { runCatching { decodeBitmap(content.bytes, 2048) }.getOrNull() }
    }
    var scale by remember(content) { mutableFloatStateOf(1f) }
    var offset by remember(content) { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize().clipToBounds()
        .pointerInput(content) {
            detectTransformGestures { _, pan, zoom, _ ->
                scale = (scale * zoom).coerceIn(1f, 6f)
                offset = if (scale == 1f) Offset.Zero else offset + pan
            }
        }
        .pointerInput(content) {
            detectTapGestures(onDoubleTap = { if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f })
        }, contentAlignment = Alignment.Center) {
        val shown = decoded.second
        when {
            shown != null -> Image(shown.asImageBitmap(), content.name, Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
            }, contentScale = ContentScale.Fit)
            decoded.first -> Text(tr(S.file_not_previewable), Modifier.padding(24.dp))
            else -> CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
        }
    }
}

/** A PDF opened from a private copy; pages are drawn one at a time (the renderer allows one open page). */
private class PdfFile(private val file: File, private val descriptor: ParcelFileDescriptor, private val renderer: PdfRenderer) {
    private val lock = Mutex()
    val pages: Int get() = renderer.pageCount
    suspend fun render(index: Int, width: Int): Bitmap = lock.withLock {
        withContext(Dispatchers.IO) {
            val page = renderer.openPage(index)
            try {
                val height = (width.toFloat() * page.height / page.width.coerceAtLeast(1)).toInt().coerceIn(1, width * 4)
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            } finally { page.close() }
        }
    }
    fun close() { runCatching { renderer.close() }; runCatching { descriptor.close() }; file.delete() }

    companion object {
        fun open(context: Context, bytes: ByteArray): PdfFile {
            val folder = File(context.cacheDir, "chat-files").apply { mkdirs() }
            val file = File.createTempFile("view", ".pdf", folder).apply { writeBytes(bytes) }
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            return try { PdfFile(file, descriptor, PdfRenderer(descriptor)) }
            catch (e: Exception) { descriptor.close(); file.delete(); throw e }
        }
    }
}

@Composable
private fun PdfPages(content: FileContent) {
    val context = LocalContext.current
    var failed by remember(content) { mutableStateOf(false) }
    val pdf by produceState<PdfFile?>(null, content) {
        val opened = withContext(Dispatchers.IO) { runCatching { PdfFile.open(context, content.bytes) }.getOrNull() }
        if (opened == null) failed = true
        value = opened
        awaitDispose { opened?.close() }
    }
    val document = pdf
    when {
        document != null -> BoxWithConstraints(Modifier.fillMaxSize()) {
            val width = with(LocalDensity.current) { (maxWidth - 24.dp).roundToPx() }.coerceIn(320, 1600)
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(document.pages) { index -> PdfPage(document, index, width) }
            }
        }
        failed -> Text(tr(S.file_not_previewable), Modifier.padding(24.dp))
        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp) }
    }
}

@Composable
private fun PdfPage(document: PdfFile, index: Int, width: Int) {
    val bitmap by produceState<Bitmap?>(null, document, index, width) { value = runCatching { document.render(index, width) }.getOrNull() }
    val shown = bitmap
    Surface(shape = RoundedCornerShape(4.dp), color = Color.White, shadowElevation = 1.dp) {
        if (shown != null) Image(shown.asImageBitmap(), tr(S.file_page, index + 1), Modifier.fillMaxWidth(),
            contentScale = ContentScale.FillWidth)
        else Box(Modifier.fillMaxWidth().aspectRatio(1 / 1.414f), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        }
    }
}

/** The files this chat's steps created or changed, newest first; tap one to open it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatFilesSheet(files: List<ChangedFile>, onOpen: (String) -> Unit, onClose: () -> Unit) {
    AppModalBottomSheet(onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = groupedPageColor()) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.8f)) {
            SheetHeader(tr(S.files_title), onClose)
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                if (files.isEmpty()) item("none") {
                    Text(tr(S.files_empty), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                itemsIndexed(files, key = { _, file -> file.path }) { index, file ->
                    val first = index == 0; val last = index == files.lastIndex
                    Surface(color = groupedCardColor(), shape = RoundedCornerShape(if (first) 12.dp else 0.dp, if (first) 12.dp else 0.dp,
                        if (last) 12.dp else 0.dp, if (last) 12.dp else 0.dp)) {
                        Column {
                            if (!first) RowDivider(52.dp)
                            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .clickable(enabled = !file.deleted, role = Role.Button) { onOpen(file.path) }
                                .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                                    AppIcon(if (file.created) R.drawable.aw_file else R.drawable.aw_edit)
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(fileName(file.path), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyLarge, fontFamily = CodeFont)
                                    Text(file.path.substringBeforeLast('/', ""), maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                when {
                                    file.deleted -> Text(tr(S.files_deleted), style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    else -> Column(horizontalAlignment = Alignment.End) {
                                        if (file.created) Text(tr(S.files_created), style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        DiffStats(file.added, file.removed)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** "Open" at the end of a file's row: the whole row opens the file. */
@Composable
internal fun OpenFileLabel() {
    Text(tr(S.file_open), Modifier.clip(RoundedCornerShape(6.dp)).padding(horizontal = 4.dp), maxLines = 1,
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
}
