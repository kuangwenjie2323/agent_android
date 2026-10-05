@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.karewinkcloud.agentweb.client.ui

import android.animation.ValueAnimator
import androidx.compose.animation.Crossfade
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.karewinkcloud.agentweb.client.R
import com.karewinkcloud.agentweb.client.R.string as S
import com.karewinkcloud.agentweb.client.core.*
import kotlinx.serialization.json.*

@Composable
internal fun ColumnScope.ComfyScreen(vm: ComfyViewModel, composerHeight: Dp = 320.dp) {
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleStartEffect(vm) { vm.setForeground(true); onStopOrDispose { vm.setForeground(false) } }
    var settingsOpen by remember { mutableStateOf(false) }
    var detailOpen by remember { mutableStateOf(false) }
    var galleryOpen by remember { mutableStateOf(false) }
    var showFailed by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var returnToSettings by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf(TextFieldValue(state.prompt)) }
    // Sync only external replacements. Echoes of our own typing arrive late and would reset the
    // cursor (scrambling fast typing and dictation), so the field owns its text otherwise.
    LaunchedEffect(state.promptRevision) {
        if (prompt.text != state.prompt) prompt = TextFieldValue(state.prompt, androidx.compose.ui.text.TextRange(state.prompt.length))
    }
    var composerOpen by rememberSaveable { mutableStateOf(false) }
    var allOpen by rememberSaveable { mutableStateOf(false) }
    val pendingJob = state.pending?.let { id -> state.selected?.takeIf { it.requestId == id }
        ?: state.jobs.find { it.requestId == id } ?: ComfyJob(id, "", "unknown") }
    val jobs = (if (pendingJob != null && state.jobs.none { it.requestId == pendingJob.requestId }) listOf(pendingJob) else emptyList()) +
        state.jobs.map { job -> pendingJob?.takeIf { it.requestId == job.requestId } ?: job }
    val recent = jobs.filter { it.phase() != ComfyJobPhase.FAILED }
    fun titleOf(job: ComfyJob) = state.workflows.find { it.id == job.workflowId }?.title ?: job.workflowId
    val openJob: (ComfyJob) -> Unit = { vm.open(it); detailOpen = true }
    ScreenTitle(tr(S.studio)) { ActionIcon(R.drawable.aw_refresh, tr(S.refresh), !state.loading && !state.submitting, vm::refresh) }
    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.error != null) item("error") {
            Box(Modifier.padding(horizontal = PageGutter)) {
                ErrorBlock(ClientError(comfyError(state.error!!)), if (state.pending != null) ({ vm.check() }) else vm::refresh,
                    tr(if (state.pending != null) S.check_status else S.refresh))
            }
        }
        item("recent") {
            ShelfHeader(tr(S.recent_works), if (jobs.isNotEmpty()) tr(S.see_all) else null) { allOpen = true }
            when {
                recent.isNotEmpty() -> LazyRow(contentPadding = PaddingValues(horizontal = PageGutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(recent.take(20), key = { it.requestId }) { job -> CreationCard(job, titleOf(job), vm) { openJob(job) } }
                }
                state.loading -> LoadingState(tr(S.loading))
                else -> EmptyState(R.drawable.aw_create, tr(S.history_empty), tr(S.creation_empty_hint), tr(S.creation_start)) { composerOpen = true }
            }
        }
        val featured = featuredWorkflows(state.workflows, state.kind)
        if (featured.isNotEmpty()) item("featured") {
            ShelfHeader(tr(S.featured_workflows), tr(S.see_all)) { galleryOpen = true }
            LazyRow(contentPadding = PaddingValues(horizontal = PageGutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(featured, key = { it.id }) { workflow ->
                    WorkflowCard(workflow, workflow.id == state.workflowId, vm) { vm.choose(workflow.id); composerOpen = true }
                }
            }
        }
        if (state.kind != "audio") item("styles") {
            ShelfHeader(tr(S.start_with_style), null) {}
            LazyRow(contentPadding = PaddingValues(horizontal = PageGutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(comfyStyles, key = { it.key }) { style ->
                    StyleCard(style) { vm.replacePrompt(applyStyle(prompt.text, style)); composerOpen = true }
                }
            }
        }
    }
    MiniComposer(state, prompt.text, pendingJob, vm, onOpen = { composerOpen = true },
        onSubmit = { focus.clearFocus(); keyboard?.hide(); vm.submit() })
    if (composerOpen) AppModalBottomSheet({ composerOpen = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().imePadding().padding(bottom = 8.dp)) {
            CreationComposer(state, prompt, { prompt = it; vm.prompt(it.text, it.composition != null) }, vm::filter,
                { settingsOpen = true }, { focus.clearFocus(); keyboard?.hide(); vm.submit(); composerOpen = false }, vm::choose, vm::size,
                composerHeight.coerceAtLeast(260.dp), { galleryOpen = true },
                onStyle = { vm.replacePrompt(applyStyle(prompt.text, it)) }, onInspire = { vm.replacePrompt(comfyInspirations.random()) },
                autoFocus = true, onParameter = vm::parameter)
        }
    }
    if (allOpen) AllCreations(jobs, ::titleOf, vm, showFailed, { showFailed = it }, openJob) { allOpen = false }
    if (settingsOpen) CreationSettings(state, vm, onGallery = {
        settingsOpen = false; returnToSettings = true; galleryOpen = true
    }, onClose = { settingsOpen = false })
    if (galleryOpen) WorkflowGallery(state, vm, onClose = {
        galleryOpen = false; settingsOpen = returnToSettings; returnToSettings = false
    })
    if (detailOpen && state.selected != null) CreationDetail(state, vm, onEdit = {
        detailOpen = false; settingsOpen = true
    }, onClose = { detailOpen = false })
}

/** Section heading with an optional trailing action ("See all"), Apple Music style. */
@Composable
private fun ShelfHeader(title: String, action: String?, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = PageGutter + 4.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        if (action != null) TextButton(onAction) { Text(action) }
    }
}

/** A large square artwork with its title and subtitle underneath, like an album. */
@Composable
internal fun ArtworkCard(title: String, subtitle: String?, width: Dp?, onClick: () -> Unit, enabled: Boolean = true, artwork: @Composable BoxScope.() -> Unit) {
    Column((if (width != null) Modifier.width(width) else Modifier.fillMaxWidth()).clip(RoundedCornerShape(12.dp))
        .clickable(enabled = enabled, onClick = onClick).graphicsLayer { alpha = if (enabled) 1f else .45f }) {
        Box((if (width != null) Modifier.size(width) else Modifier.fillMaxWidth().aspectRatio(1f)).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center, content = artwork)
        Text(title, Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium)
        if (!subtitle.isNullOrBlank()) Text(subtitle, Modifier.padding(horizontal = 2.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CreationCard(job: ComfyJob, title: String, vm: ComfyViewModel, onClick: () -> Unit) {
    val video = job.outputs.any { it.mime.startsWith("video/") }
    ArtworkCard(title.ifBlank { comfyStatus(job.status) }, if (job.phase() == ComfyJobPhase.COMPLETE) null else comfyStatus(job.status),
        168.dp, onClick) {
        when (job.phase()) {
            ComfyJobPhase.COMPLETE -> ComfyThumbnail(job, vm, Modifier.fillMaxSize())
            ComfyJobPhase.STARTING, ComfyJobPhase.QUEUED, ComfyJobPhase.RUNNING, ComfyJobPhase.FINISHING ->
                CreationProgress(job, vm, Modifier.padding(12.dp))
            else -> Text(comfyStatus(job.status), Modifier.padding(12.dp), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (video) Surface(shape = androidx.compose.foundation.shape.CircleShape, color = Color.Black.copy(alpha = .5f), contentColor = Color.White,
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)) { Box(Modifier.padding(6.dp)) { AppIcon(R.drawable.aw_play) } }
    }
}

@Composable
private fun WorkflowCard(workflow: ComfyWorkflow, selected: Boolean, vm: ComfyViewModel, onClick: () -> Unit) {
    ArtworkCard(workflow.title.substringBefore(" · "), tr(comfyChannelResource(workflow.channel)), 148.dp, onClick) {
        RemoteImage(workflow.coverUrl, vm, Modifier.fillMaxSize()) {
            Text(workflow.modelFamily().ifBlank { kindLabel(workflow.kind) }, style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary)
        }
        if (selected) Surface(shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            Box(Modifier.padding(4.dp)) { AppIcon(R.drawable.aw_check, tr(S.workflow_selected)) }
        }
    }
}

private val styleColors = mapOf(
    "portrait" to listOf(0xFFB8875F, 0xFF5B3A29), "fashion" to listOf(0xFFE0457B, 0xFF6A1B9A), "anime" to listOf(0xFF4FC3F7, 0xFF7E57C2),
    "cartoon3d" to listOf(0xFFFFB74D, 0xFFEF5350), "cinematic" to listOf(0xFF263238, 0xFF00897B), "product" to listOf(0xFFECEFF1, 0xFF90A4AE),
    "poster" to listOf(0xFFFFD54F, 0xFFD84315), "watercolor" to listOf(0xFF80DEEA, 0xFFF48FB1), "guofeng" to listOf(0xFF8D6E63, 0xFF212121),
    "cyberpunk" to listOf(0xFFFF00A8, 0xFF00E5FF))

@Composable
private fun StyleCard(style: ComfyStyle, onClick: () -> Unit) {
    val colors = (styleColors[style.key] ?: listOf(0xFF607D8B, 0xFF263238)).map { Color(it) }
    Box(Modifier.size(132.dp, 84.dp).clip(RoundedCornerShape(12.dp))
        .background(androidx.compose.ui.graphics.Brush.linearGradient(colors)).clickable(onClick = onClick),
        contentAlignment = Alignment.BottomStart) {
        Text(styleLabel(style.key), Modifier.padding(10.dp), color = Color.White, style = MaterialTheme.typography.titleSmall,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
    }
}

/** Collapsed composer above the navigation bar, like a mini player: tap to expand, spark to generate. */
@Composable
private fun MiniComposer(state: ComfyState, prompt: String, pending: ComfyJob?, vm: ComfyViewModel, onOpen: () -> Unit, onSubmit: () -> Unit) {
    Surface(onOpen, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 6.dp) {
        Row(Modifier.padding(start = 8.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                RemoteImage(state.workflow?.coverUrl, vm, Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { AppIcon(R.drawable.aw_create) } }
            }
            Column(Modifier.weight(1f)) {
                Text(prompt.ifBlank { tr(S.creation_hint) }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                    color = if (prompt.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                Text(pending?.let { comfyStatus(it.status) } ?: state.workflow?.title ?: tr(S.workflow), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val description = tr(if (state.submitting) S.submitting else S.generate)
            FilledIconButton(onSubmit, enabled = state.canSubmit, modifier = Modifier.size(44.dp).semantics { contentDescription = description }) {
                // Spin only while a task is really in flight; an unconfirmed one just shows its status text.
                if (state.submitting || pending?.needsPolling() == true) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else AppIcon(R.drawable.aw_create)
            }
        }
    }
}

/** Every creation in a grid, with failed ones behind a toggle. */
@Composable
private fun AllCreations(jobs: List<ComfyJob>, titleOf: (ComfyJob) -> String, vm: ComfyViewModel, showFailed: Boolean,
    onShowFailed: (Boolean) -> Unit, onOpen: (ComfyJob) -> Unit, onClose: () -> Unit) {
    AppModalBottomSheet(onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        val failed = jobs.count { it.phase() == ComfyJobPhase.FAILED }
        val shown = if (showFailed) jobs else jobs.filter { it.phase() != ComfyJobPhase.FAILED }
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr(S.recent_works), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            if (failed > 0) TextButton({ onShowFailed(!showFailed) }) { Text(if (showFailed) tr(S.failed_hide) else tr(S.failed_show, failed)) }
            ActionIcon(R.drawable.aw_close, tr(S.close), onClick = onClose)
        }
        val tileWidth = 136.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.5f)
        LazyVerticalGrid(GridCells.Adaptive(tileWidth), Modifier.fillMaxWidth().fillMaxHeight(.92f), contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(shown, key = { it.requestId }) { job -> CreationTile(job, titleOf(job), vm) { onOpen(job) } }
        }
    }
}

/** Loads a public cover image once (cached); shows [placeholder] until it arrives or when there is none. */
@Composable
internal fun RemoteImage(url: String?, vm: ComfyViewModel, modifier: Modifier = Modifier, placeholder: @Composable () -> Unit) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, url) {
        value = url?.let { runCatching { vm.image(it) }.getOrNull() }?.let { file ->
            withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { android.graphics.BitmapFactory.decodeFile(file.absolutePath) }.getOrNull() }
        }
    }
    Crossfade(bitmap, label = "cover", modifier = modifier) { image ->
        if (image != null) Image(image.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { placeholder() }
    }
}

/** Workflows to feature: the free/on-demand GPU channels first, then the rest, preferring ones with covers. */
internal fun featuredWorkflows(workflows: List<ComfyWorkflow>, kind: String): List<ComfyWorkflow> =
    workflows.filter { it.unavailable == null && (kind == "image" || it.kind == kind) }
        .sortedWith(compareBy<ComfyWorkflow>({ if (it.kind == kind) 0 else 1 },
            { when (it.channel) { "kaggle_gpu" -> 0; "runpod_gpu" -> 1; "cloud_gpu" -> 2; else -> 3 } }, { if (it.coverUrl != null) 0 else 1 }))
        .take(14)

@Composable
private fun CreationTile(job: ComfyJob, title: String, vm: ComfyViewModel, onClick: () -> Unit) {
    Surface(onClick, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
        Box {
            Crossfade(targetState = job.phase(), label = "creation result", modifier = Modifier.fillMaxSize()) { phase ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (phase == ComfyJobPhase.COMPLETE) ComfyThumbnail(job, vm, Modifier.fillMaxSize())
                    else if (phase in setOf(ComfyJobPhase.STARTING, ComfyJobPhase.QUEUED, ComfyJobPhase.RUNNING, ComfyJobPhase.FINISHING))
                        CreationProgress(job, vm, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 36.dp))
                    else Text(comfyStatus(job.status), Modifier.padding(16.dp), style = MaterialTheme.typography.labelLarge,
                        color = if (phase == ComfyJobPhase.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = .9f), shape = RoundedCornerShape(16.dp),
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val video = job.outputs.firstOrNull { it.mime.startsWith("video/") }
                    if (video != null) AppIcon(R.drawable.aw_play, tr(S.open_video))
                    Text(title.ifBlank { comfyStatus(job.status) }, Modifier.weight(1f, false), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall)
                    video?.durationSeconds?.let { Text(" · ${tr(S.seconds, it.toInt())}", style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }
}

@Composable
internal fun CreationProgress(job: ComfyJob, vm: ComfyViewModel, modifier: Modifier = Modifier) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val motionScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]
    var observed by remember(job.requestId) { mutableLongStateOf(vm.observedMillis(job.requestId)) }
    var animate by remember { mutableStateOf(false) }
    LaunchedEffect(job.requestId, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                while (true) {
                    observed = vm.observedMillis(job.requestId)
                    animate = ValueAnimator.areAnimatorsEnabled()
                    delay(1000)
                }
            } finally { animate = false }
        }
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WorkingSpark(animate && motionScale?.scaleFactor != 0f)
        Text(tr(comfyProgressResource(job)), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        job.waitingQueuePosition()?.let { Text(tr(S.kaggle_queue_position, it), style = MaterialTheme.typography.bodySmall) }
        job.progress?.takeIf { it.isFinite() && it in 0.0..1.0 }?.let {
            LinearProgressIndicator(progress = { it.toFloat() }, modifier = Modifier.fillMaxWidth())
            Text(tr(S.job_progress, (it * 100).toInt()), style = MaterialTheme.typography.bodySmall)
        }
        val sinceSubmit = job.createdAt?.let { (System.currentTimeMillis() / 1000 - it).takeIf { s -> s >= 0 } }
        Text(if (sinceSubmit != null) tr(S.job_elapsed_total, spanLabel(sinceSubmit)) else tr(S.job_elapsed, observed / 1000),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CreationDetail(state: ComfyState, vm: ComfyViewModel, onEdit: () -> Unit, onClose: () -> Unit) {
    val job = state.selected ?: return
    val workflow = state.workflows.find { it.id == job.workflowId }
    val clipboard = LocalClipboardManager.current
    val jobPrompt = job.parameters.string(workflow?.promptKey ?: "prompt") ?: job.parameters.string("text").orEmpty()
    var copied by remember(job.requestId) { mutableStateOf(false) }
    var actionFailed by remember(job.requestId) { mutableStateOf(false) }
    val availability = workflow?.availability(state.resources)
    val blockedReason = when {
        state.submitting || state.pending != null -> tr(S.creation_action_busy)
        workflow == null -> tr(S.creation_workflow_missing)
        state.signInRequired -> tr(S.error_auth)
        state.loading || !state.initialized -> tr(S.loading)
        availability?.kind != ComfyAvailabilityKind.AVAILABLE -> availability?.let { availabilityLabel(it) }.orEmpty()
        else -> null
    }
    val editBlocked = when {
        workflow == null -> tr(S.creation_workflow_missing)
        state.signInRequired -> tr(S.error_auth)
        state.submitting -> tr(S.creation_action_busy)
        state.loading || !state.initialized -> tr(S.loading)
        else -> null
    }
    val active = job.phase() != ComfyJobPhase.COMPLETE
    val scope = rememberCoroutineScope()
    var confirmDelete by remember(job.requestId) { mutableStateOf(false) }
    var deleting by remember(job.requestId) { mutableStateOf(false) }
    var deleteError by remember(job.requestId) { mutableStateOf<String?>(null) }
    AppModalBottomSheet(onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("title") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(workflow?.title ?: tr(S.job_detail), Modifier.weight(1f).semantics { heading() }, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    ActionIcon(R.drawable.aw_close, tr(S.close), onClick = onClose)
                }
            }
            // A finished result leads with the media; status controls only matter while a job is open or failed.
            if (active) item("status") {
                if (job.phase() in setOf(ComfyJobPhase.STARTING, ComfyJobPhase.QUEUED, ComfyJobPhase.RUNNING, ComfyJobPhase.FINISHING))
                    CreationProgress(job, vm, Modifier.fillMaxWidth().padding(vertical = 16.dp))
                else Text(comfyStatus(job.status), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.titleSmall, color = if (job.phase() == ComfyJobPhase.FAILED) MaterialTheme.colorScheme.error else Color.Unspecified)
                job.errorCode?.let { Text(comfyError(it), style = MaterialTheme.typography.bodyMedium) }
                if (job.status in setOf("unknown", "query_failed")) Text(tr(S.uncertain_hint), style = MaterialTheme.typography.bodySmall)
                if (job.phase() != ComfyJobPhase.FAILED) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ vm.check(job.requestId) }, enabled = !state.checking && !state.submitting) { Text(tr(S.check_status)) }
                    if (job.needsPolling() || state.polling || state.pollingPaused) TextButton(
                        { if (state.pollingPaused) vm.resumePolling() else vm.stopPolling() }) { Text(tr(if (state.pollingPaused) S.resume_polling else S.pause_polling)) }
                }
                if (job.needsPolling() || state.pollingPaused) Text(tr(S.polling_hint), style = MaterialTheme.typography.bodySmall)
            }
            state.error?.let { item("error") { ErrorBlock(ClientError(comfyError(it))) } }
            items(job.outputs, key = { it.index }) { output -> ComfyOutputView(job, output, vm) }
            job.totalSeconds?.takeIf { !active }?.let { total -> item("timing") {
                Text(listOfNotNull(tr(S.job_time_total, spanLabel(total)),
                    job.queueMs?.let { tr(S.job_time_queue, spanLabel(it / 1000)) },
                    job.runMs?.let { tr(S.job_time_run, spanLabel(it / 1000)) }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
            if (jobPrompt.isNotBlank()) item("prompt") {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, end = 4.dp)) {
                        Text(jobPrompt, Modifier.padding(end = 8.dp), maxLines = 6, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium)
                        TextButton({ clipboard.setText(AnnotatedString(jobPrompt)); copied = true }, Modifier.align(Alignment.End), colors = quietButton()) {
                            Text(tr(if (copied) S.copied else S.copy_prompt))
                        }
                    }
                }
            }
            item("actions") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppleButton({ if (vm.again(job)) onClose() else actionFailed = true }, Modifier.weight(1f), enabled = blockedReason == null) {
                        Box(Modifier.size(18.dp)) { AppIcon(R.drawable.aw_refresh) }; Spacer(Modifier.width(6.dp)); Text(tr(S.creation_again))
                    }
                    AppleButton({ if (vm.edit(job)) onEdit() else actionFailed = true }, Modifier.weight(1f), enabled = editBlocked == null) {
                        Box(Modifier.size(18.dp)) { AppIcon(R.drawable.aw_settings) }; Spacer(Modifier.width(6.dp)); Text(tr(S.creation_edit))
                    }
                }
                (blockedReason ?: editBlocked)?.let { Text(it, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (actionFailed) Text(tr(S.creation_action_failed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (job.phase() in setOf(ComfyJobPhase.COMPLETE, ComfyJobPhase.FAILED)) item("delete") {
                TextButton({ confirmDelete = true }, Modifier.fillMaxWidth(), enabled = !deleting,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    AppIcon(R.drawable.aw_delete); Spacer(Modifier.width(8.dp)); Text(tr(if (deleting) S.deleting else S.delete_creation))
                }
                deleteError?.let { Text(comfyError(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text(tr(S.delete_confirm_title)) },
        text = { Text(tr(if (job.outputs.any { it.publicUrl != null }) S.delete_confirm_public else S.delete_confirm_body)) },
        confirmButton = { TextButton({
            confirmDelete = false; deleting = true; deleteError = null
            scope.launch {
                try { vm.delete(job); onClose() }
                catch (e: ComfyFailure) { deleteError = e.code }
                finally { deleting = false }
            }
        }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(tr(S.delete)) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text(tr(S.cancel)) } })
}

@Composable
internal fun CreationComposer(state: ComfyState, prompt: TextFieldValue, onPrompt: (TextFieldValue) -> Unit,
    onFilter: (String, String) -> Unit, onSettings: () -> Unit, onSubmit: () -> Unit,
    onSuggestion: (String) -> Unit = {}, onSize: (ComfySize) -> Unit = {}, maxHeight: Dp = 320.dp, onGallery: () -> Unit = onSettings,
    onStyle: (ComfyStyle) -> Unit = {}, onInspire: () -> Unit = {}, autoFocus: Boolean = false,
    onParameter: (String, String) -> Unit = { _, _ -> }) {
    val promptFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { delay(250); runCatching { promptFocus.requestFocus() } }
    var kindMenu by remember { mutableStateOf(false) }
    var sizeMenu by remember { mutableStateOf(false) }
    // The prompt scrolls; the workflow and action rows stay pinned (the channel lives in the gallery and settings).
    ComposerSurface(maxHeight, actions = {
        ComposerChip(state.workflow?.title ?: tr(S.workflow), onGallery, Modifier.fillMaxWidth(), selected = true)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box {
                ComposerChip(kindLabel(state.kind), { kindMenu = true })
                DropdownMenu(kindMenu, { kindMenu = false }) { listOf("image", "video", "audio").forEach { kind ->
                    DropdownMenuItem(text = { Text(kindLabel(kind)) }, onClick = { onFilter(kind, state.channel); kindMenu = false })
                } }
            }
            Box(Modifier.weight(1f)) {
                val sizes = state.workflow?.sizes().orEmpty()
                val size = sizes.firstOrNull { it.parameters.all { (key, value) -> state.values[key] == value } }
                // On a narrow phone the row has room for one more button: video folds its frame shape
                // and duration into it ("16:9 · 5 s"), and the sheet offers both.
                val duration = state.workflow?.inputs?.find { it.key == "duration" && it.options.isNotEmpty() }
                val durationValue = duration?.let { state.values[it.key] ?: it.default }
                val label = if (state.workflow?.kind == "video" && duration != null)
                    listOfNotNull(size?.label?.substringBefore(" · "), durationLabel(durationValue.orEmpty())).joinToString(" · ")
                else size?.label?.let(::sizeChipLabel)
                    ?: listOfNotNull(state.values["size"], state.values["aspect_ratio"], state.values["resolution"]).joinToString(" · ").ifBlank { tr(S.size) }
                ComposerChip(label, { if (sizes.isEmpty() && duration == null) onSettings() else sizeMenu = true }, arrow = duration == null)
                if (sizeMenu) SizeSheet(sizes, size?.label, onSize,
                    durations = duration?.options?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(), duration = durationValue,
                    onDuration = { onParameter("duration", it) }) { sizeMenu = false }
            }
            ActionIcon(R.drawable.aw_settings, tr(S.creation_settings), onClick = onSettings)
            val submitDescription = tr(if (state.submitting) S.submitting else S.generate)
            FilledIconButton(onSubmit, enabled = state.canSubmit, modifier = Modifier.size(48.dp).semantics { contentDescription = submitDescription }) {
                if (state.submitting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else AppIcon(R.drawable.aw_create)
            }
        }
    }) {
        TextField(prompt, onPrompt, Modifier.fillMaxWidth().focusRequester(promptFocus), placeholder = { Text(tr(S.creation_hint)) }, minLines = 2, maxLines = 4,
            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
        if (state.kind != "audio") LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
            if (prompt.text.isBlank()) item("inspire") {
                AssistChip(onInspire, label = { Text(tr(S.style_inspire)) }, leadingIcon = { AppIcon(R.drawable.aw_create) })
            }
            items(comfyStyles, key = { it.key }) { style ->
                PillChip(prompt.text.contains(style.phrase), { onStyle(style) }, label = { Text(styleLabel(style.key)) })
            }
        }
        if (state.suggestions.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.suggestions, key = { it.workflowId }) { suggestion ->
                ComposerChip(tr(S.recommendation, state.workflows.find { it.id == suggestion.workflowId }?.title ?: suggestion.workflowId),
                    { onSuggestion(suggestion.workflowId) }, Modifier.widthIn(max = 240.dp), selected = suggestion.workflowId == state.workflowId)
            }
        }
        if (state.initialized && state.choices.isEmpty()) Text(tr(S.no_workflows), Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
        state.workflow?.availability(state.resources)?.takeIf { it.kind != ComfyAvailabilityKind.AVAILABLE }?.let {
            Text(availabilityLabel(it), Modifier.padding(8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (state.needsResources && state.resources?.ready != true) Text(tr(if (state.resourcesError) S.resources_failed else S.resources_loading),
            Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
    }
}
