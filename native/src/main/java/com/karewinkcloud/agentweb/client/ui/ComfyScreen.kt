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
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    var returnToSettings by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf(TextFieldValue(state.prompt)) }
    // Sync only external replacements. Echoes of our own typing arrive late and would reset the
    // cursor (scrambling fast typing and dictation), so the field owns its text otherwise.
    LaunchedEffect(state.promptRevision) {
        if (prompt.text != state.prompt) prompt = TextFieldValue(state.prompt, androidx.compose.ui.text.TextRange(state.prompt.length))
    }
    ScreenTitle(tr(S.studio)) { ActionIcon(R.drawable.aw_refresh, tr(S.refresh), !state.loading && !state.submitting, vm::refresh) }
    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    // Two columns even on narrow (~340dp) phones; larger text or wider screens adapt.
    val tileWidth = 136.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.5f)
    LazyVerticalGrid(GridCells.Adaptive(tileWidth), Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("recent-title", span = { GridItemSpan(maxLineSpan) }) { SectionLabel(tr(S.recent_works), Modifier.padding(bottom = 4.dp)) }
        if (state.error != null) item("error", span = { GridItemSpan(maxLineSpan) }) {
            ErrorBlock(ClientError(comfyError(state.error!!)), if (state.pending != null) ({ vm.check() }) else vm::refresh,
                tr(if (state.pending != null) S.check_status else S.refresh))
        }
        val pendingJob = state.pending?.let { id -> state.selected?.takeIf { it.requestId == id }
            ?: state.jobs.find { it.requestId == id } ?: ComfyJob(id, "", "unknown") }
        val jobs = (if (pendingJob != null && state.jobs.none { it.requestId == pendingJob.requestId }) listOf(pendingJob) else emptyList()) +
            state.jobs.map { job -> pendingJob?.takeIf { it.requestId == job.requestId } ?: job }
        if (jobs.isEmpty() && !state.loading && state.error == null) item("empty", span = { GridItemSpan(maxLineSpan) }) {
            EmptyState(R.drawable.aw_create, tr(S.history_empty), tr(S.creation_empty_hint))
        }
        if (jobs.isEmpty() && state.loading) item("loading", span = { GridItemSpan(maxLineSpan) }) { LoadingState(tr(S.loading)) }
        items(jobs, key = { it.requestId }) { job ->
            CreationTile(job, state.workflows.find { it.id == job.workflowId }?.title ?: job.workflowId, vm) { vm.open(job); detailOpen = true }
        }
    }
    Box {
        CreationComposer(state, prompt, { prompt = it; vm.prompt(it.text, it.composition != null) }, vm::filter,
            { settingsOpen = true }, vm::submit, vm::choose, vm::size, composerHeight, { galleryOpen = true })
    }
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
                    else Text(comfyStatus(job.status), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
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
        Text(tr(comfyProgressResource(job)), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
        job.waitingQueuePosition()?.let { Text(tr(S.kaggle_queue_position, it), style = MaterialTheme.typography.bodySmall) }
        job.progress?.takeIf { it.isFinite() && it in 0.0..1.0 }?.let {
            LinearProgressIndicator(progress = { it.toFloat() }, modifier = Modifier.fillMaxWidth())
            Text(tr(S.job_progress, (it * 100).toInt()), style = MaterialTheme.typography.bodySmall)
        }
        Text(tr(S.job_elapsed, observed / 1000), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    AppModalBottomSheet(onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("title") { Text(workflow?.title ?: tr(S.job_detail), style = MaterialTheme.typography.titleLarge) }
            item("status") {
                if (job.phase() in setOf(ComfyJobPhase.STARTING, ComfyJobPhase.QUEUED, ComfyJobPhase.RUNNING, ComfyJobPhase.FINISHING))
                    CreationProgress(job, vm, Modifier.fillMaxWidth().padding(vertical = 16.dp))
                else Text(comfyStatus(job.status), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (job.status in setOf("unknown", "query_failed")) Text(tr(S.uncertain_hint), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ vm.check(job.requestId) }, enabled = !state.checking && !state.submitting) { Text(tr(S.check_status)) }
                    if (job.needsPolling() || state.polling || state.pollingPaused) TextButton(
                        { if (state.pollingPaused) vm.resumePolling() else vm.stopPolling() }) { Text(tr(if (state.pollingPaused) S.resume_polling else S.pause_polling)) }
                }
                if (job.needsPolling() || state.pollingPaused) Text(tr(S.polling_hint), style = MaterialTheme.typography.bodySmall)
                job.errorCode?.let { Text(comfyError(it), color = MaterialTheme.colorScheme.error) }
                state.error?.let { ErrorBlock(ClientError(comfyError(it))) }
            }
            item("actions") {
                if (jobPrompt.isNotBlank()) {
                    Text(jobPrompt, maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    TextButton({ clipboard.setText(AnnotatedString(jobPrompt)); copied = true }) { Text(tr(if (copied) S.copied else S.copy_prompt)) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton({ if (vm.again(job)) onClose() else actionFailed = true },
                        Modifier.weight(1f), enabled = blockedReason == null) { Text(tr(S.creation_again)) }
                    OutlinedButton({ if (vm.edit(job)) onEdit() else actionFailed = true },
                        Modifier.weight(1f), enabled = editBlocked == null) { Text(tr(S.creation_edit)) }
                }
                (blockedReason ?: editBlocked)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (actionFailed) Text(tr(S.creation_action_failed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            items(job.outputs, key = { it.index }) { output -> ComfyOutputView(job, output, vm) }
        }
    }
}

@Composable
internal fun CreationComposer(state: ComfyState, prompt: TextFieldValue, onPrompt: (TextFieldValue) -> Unit,
    onFilter: (String, String) -> Unit, onSettings: () -> Unit, onSubmit: () -> Unit,
    onSuggestion: (String) -> Unit = {}, onSize: (ComfySize) -> Unit = {}, maxHeight: Dp = 320.dp, onGallery: () -> Unit = onSettings) {
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
                val label = size?.label ?: listOfNotNull(state.values["size"], state.values["aspect_ratio"], state.values["resolution"]).joinToString(" · ").ifBlank { tr(S.size) }
                ComposerChip(label, { if (sizes.isEmpty()) onSettings() else sizeMenu = true })
                DropdownMenu(sizeMenu, { sizeMenu = false }) { sizes.forEach { value ->
                    DropdownMenuItem(text = { Text(value.label) }, onClick = { onSize(value); sizeMenu = false })
                } }
            }
            ActionIcon(R.drawable.aw_settings, tr(S.creation_settings), onClick = onSettings)
            val submitDescription = tr(if (state.submitting) S.submitting else S.generate)
            FilledIconButton(onSubmit, enabled = state.canSubmit, modifier = Modifier.size(48.dp).semantics { contentDescription = submitDescription }) {
                if (state.submitting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else AppIcon(R.drawable.aw_create)
            }
        }
    }) {
        TextField(prompt, onPrompt, Modifier.fillMaxWidth(), placeholder = { Text(tr(S.creation_hint)) }, minLines = 2, maxLines = 4,
            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
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
