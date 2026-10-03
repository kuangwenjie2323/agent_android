@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.karewinkcloud.agentweb.client.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
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
    var prompt by remember { mutableStateOf(TextFieldValue(state.prompt)) }
    LaunchedEffect(state.prompt) { if (prompt.text != state.prompt) prompt = TextFieldValue(state.prompt) }
    ScreenTitle(tr(S.studio), tr(S.studio_subtitle)) { ActionIcon(R.drawable.aw_refresh, tr(S.refresh), !state.loading && !state.submitting, vm::refresh) }
    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    val tileWidth = 156.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.5f)
    LazyVerticalGrid(GridCells.Adaptive(tileWidth), Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("recent-title", span = { GridItemSpan(maxLineSpan) }) { SectionLabel(tr(S.recent_works), Modifier.padding(bottom = 4.dp)) }
        if (state.error != null) item("error", span = { GridItemSpan(maxLineSpan) }) {
            ErrorBlock(ClientError(comfyError(state.error!!)), if (state.pending != null) ({ vm.check() }) else vm::refresh,
                tr(if (state.pending != null) S.check_status else S.refresh))
        }
        val jobs = ((state.pending?.let { id -> listOf(state.selected?.takeIf { it.requestId == id }
            ?: state.jobs.find { it.requestId == id } ?: ComfyJob(id, "", "unknown")) } ?: emptyList()) + state.jobs).distinctBy { it.requestId }.take(12)
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
            { settingsOpen = true }, vm::submit, vm::choose, vm::size, composerHeight)
    }
    if (settingsOpen) CreationSettings(state, vm) { settingsOpen = false }
    if (detailOpen && state.selected != null) CreationDetail(state, vm) { detailOpen = false }
}

@Composable
private fun CreationTile(job: ComfyJob, title: String, vm: ComfyViewModel, onClick: () -> Unit) {
    val active = job.status in comfyActive
    val observed by produceState(0L, job.requestId, active) {
        val start = System.nanoTime()
        while (active) { value = (System.nanoTime() - start) / 1_000_000_000; delay(1000) }
    }
    Surface(onClick, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
        Box {
            if (job.outputs.isNotEmpty()) ComfyThumbnail(job, vm, Modifier.fillMaxSize())
            if (active) Column(Modifier.align(Alignment.Center).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(comfyStatus(job.status), style = MaterialTheme.typography.bodyMedium)
                if (job.progress != null) LinearProgressIndicator(progress = { job.progress.toFloat() }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(tr(S.job_elapsed, observed), style = MaterialTheme.typography.bodySmall)
            } else if (job.outputs.isEmpty()) Text(comfyStatus(job.status), Modifier.align(Alignment.Center), style = MaterialTheme.typography.bodyMedium)
            Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = .9f), shape = RoundedCornerShape(16.dp),
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val video = job.outputs.firstOrNull { it.mime.startsWith("video/") }
                    if (video != null) AppIcon(R.drawable.aw_play, tr(S.open_video))
                    Text(title.ifBlank { comfyStatus(job.status) }, Modifier.weight(1f, false), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall)
                    video?.durationSeconds?.let { Text(" · ${it.toInt()}s", style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }
}

@Composable
private fun CreationDetail(state: ComfyState, vm: ComfyViewModel, onClose: () -> Unit) {
    val job = state.selected ?: return
    ModalBottomSheet(onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("title") { Text(state.workflows.find { it.id == job.workflowId }?.title ?: tr(S.job_detail), style = MaterialTheme.typography.titleLarge) }
            item("status") {
                Text(comfyStatus(job.status), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (job.status in comfyActive || state.checking) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (job.status in setOf("unknown", "query_failed")) Text(tr(S.uncertain_hint), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ vm.check(job.requestId) }, enabled = !state.checking && !state.submitting) { Text(tr(S.check_status)) }
                    if (job.status in comfyActive || state.polling || state.pollingPaused) TextButton(
                        { if (state.pollingPaused) vm.resumePolling() else vm.stopPolling() }) { Text(tr(if (state.pollingPaused) S.resume_polling else S.pause_polling)) }
                }
                if (job.status in comfyActive || state.pollingPaused) Text(tr(S.polling_hint), style = MaterialTheme.typography.bodySmall)
                job.errorCode?.let { Text(comfyError(it), color = MaterialTheme.colorScheme.error) }
                state.error?.let { ErrorBlock(ClientError(comfyError(it))) }
            }
            items(job.outputs, key = { it.index }) { output -> ComfyOutputView(job, output, vm) }
        }
    }
}

@Composable
internal fun CreationComposer(state: ComfyState, prompt: TextFieldValue, onPrompt: (TextFieldValue) -> Unit,
    onFilter: (String, String) -> Unit, onSettings: () -> Unit, onSubmit: () -> Unit,
    onSuggestion: (String) -> Unit = {}, onSize: (ComfySize) -> Unit = {}, maxHeight: Dp = 320.dp) {
    var kindMenu by remember { mutableStateOf(false) }
    var sizeMenu by remember { mutableStateOf(false) }
    val submitDescription = tr(if (state.submitting) S.submitting else S.generate)
    ComposerSurface(maxHeight, actions = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
            FilledIconButton(onSubmit, enabled = state.canSubmit, modifier = Modifier.size(48.dp).semantics { contentDescription = submitDescription }) {
                if (state.submitting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else AppIcon(R.drawable.aw_create)
            }
        }
    }) {
        Text(tr(S.creation_prompt), Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        state.workflow?.let { workflow ->
            if (workflow.unavailable != null) Text(tr(S.workflow_unavailable), Modifier.padding(8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            val missing = workflow.missingModels(state.resources)
            if (missing.isNotEmpty()) Text(tr(S.missing_models, missing.joinToString()), Modifier.padding(8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (state.needsResources && state.resources?.ready != true) Text(tr(if (state.resourcesError) S.resources_failed else S.resources_loading),
            Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)

    }
}

@Composable
private fun CreationSettings(state: ComfyState, vm: ComfyViewModel, onClose: () -> Unit) {
    var choosingWorkflow by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    ModalBottomSheet(onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f)) {
            Row(Modifier.padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr(S.creation_settings), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                ActionIcon(R.drawable.aw_check, tr(S.done), onClick = onClose)
            }
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item("channel") {
                    Text(tr(S.channel), style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("cloud_gpu" to S.channel_gpu, "partner_api" to S.channel_api).forEach { (channel, label) ->
                            ComposerChip(tr(label), { vm.filter(state.kind, channel) }, selected = state.channel == channel)
                        }
                    }
                    Text(tr(if (state.channel == "partner_api") S.channel_api_hint else S.channel_gpu_hint), style = MaterialTheme.typography.bodySmall)
                }
                item("workflow") {
                    FilledTonalButton({ choosingWorkflow = !choosingWorkflow }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(state.workflow?.title ?: tr(S.workflow), Modifier.weight(1f)); AppIcon(R.drawable.aw_down)
                    }
                    state.workflow?.description?.takeIf { it.isNotBlank() }?.let { Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall) }
                }
                if (choosingWorkflow || state.workflow == null) {
                    item("search") { TextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(tr(S.workflow_search)) }) }
                    items(state.choices.filter { it.title.contains(query, true) || it.id.contains(query, true) }, key = { "workflow-${it.id}" }) { workflow ->
                        val unavailable = workflow.unavailable != null || workflow.missingModels(state.resources).isNotEmpty()
                        ChoiceRow(workflow.title, workflow.id == state.workflowId, subtitle = if (unavailable) tr(S.workflow_unavailable) else workflow.description) {
                            vm.choose(workflow.id); choosingWorkflow = false
                        }
                    }
                } else state.workflow?.let { workflow ->
                    val sizes = workflow.sizes()
                    if (sizes.isNotEmpty()) item("size") {
                        ValuePicker(tr(S.size), sizes.firstOrNull { size -> size.parameters.all { state.values[it.key] == it.value } }?.label.orEmpty(),
                            sizes.map { it.label to it.label }) { label -> sizes.find { it.label == label }?.let(vm::size) }
                    }
                    if (state.needsResources) item("resources") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tr(S.resources), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            ActionIcon(R.drawable.aw_refresh, tr(S.resources_refresh), !state.resourcesLoading) { vm.ensureResources(true) }
                        }
                        if (state.resourcesLoading) Text(tr(S.resources_loading), style = MaterialTheme.typography.bodySmall)
                        if (state.resourcesError || (state.resources?.ready != true && !state.resourcesLoading)) Text(tr(S.resources_failed), color = MaterialTheme.colorScheme.error)
                        if (state.resources?.stale == true) Text(tr(S.resources_stale), style = MaterialTheme.typography.bodySmall)
                    }
                    items(workflow.inputs.filter { it.key != workflow.promptKey && (sizes.isEmpty() || it.key !in setOf("width", "height", "size")) }, key = { "field-${workflow.id}-${it.key}" }) { input ->
                        SchemaField(input, state.values[input.key] ?: input.default, state, { vm.parameter(input.key, it) })
                    }
                }
                if (state.invalidField != null) item("validation") { Text(comfyError("invalid_parameters"), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun SchemaField(input: ComfyInput, value: String, state: ComfyState, onChange: (String) -> Unit) {
    val title = fieldLabel(input)
    when {
        input.resource != null -> {
            val resources = state.resources?.items.orEmpty().filter { it.category == input.resource }
                .sortedByDescending { input.family != null && it.family == input.family }
            ValuePicker(title, value, resources.map { it.name to "${it.name}\n${it.family.orEmpty()}" }, enabled = state.resources?.ready == true, onChange = onChange)
            Text(tr(S.resource_compatibility), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        input.type == "asset" -> {
            val options = state.jobs.filter { it.status == "succeeded" && it.jobId != null }.flatMap { job ->
                job.outputs.filter { it.mime.startsWith("image/") }.map { output ->
                    buildJsonObject { put("job_id", job.jobId); put("output_index", output.index) }.toString() to
                        ((state.workflows.find { it.id == job.workflowId }?.title ?: job.workflowId) + " · ${output.index + 1}")
                }
            }
            ValuePicker(title, value, options, onChange = onChange)
            Text(tr(S.asset_hint), style = MaterialTheme.typography.bodySmall)
        }
        input.options.isNotEmpty() -> ValuePicker(title, value, input.options.map { (it as JsonPrimitive).content.let { raw -> raw to raw } }, onChange = onChange)
        input.type == "boolean" -> Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(value.toBoolean(), { onChange(it.toString()) }, Modifier.semantics { contentDescription = title })
        }
        else -> TextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(title) },
            isError = state.invalidField == input.key, minLines = 1, maxLines = if (input.type == "string") 4 else 1,
            keyboardOptions = KeyboardOptions(keyboardType = if (input.type == "integer") KeyboardType.Number else if (input.type == "number") KeyboardType.Decimal else KeyboardType.Text),
            supportingText = { if (input.min != null || input.max != null) Text(tr(S.bounds, input.min?.toString() ?: "…", input.max?.toString() ?: "…")) })
    }
}

@Composable
private fun ValuePicker(title: String, value: String, options: List<Pair<String, String>>, enabled: Boolean = true, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    FilledTonalButton({ open = true; search = "" }, Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = enabled, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelMedium)
            Text(options.find { it.first == value }?.second?.substringBefore('\n') ?: value.ifBlank { tr(S.select_value) },
                maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        }
        AppIcon(R.drawable.aw_down)
    }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                if (options.size > 8) TextField(search, { search = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(tr(S.resource_search)) })
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    val filtered = options.filter { it.second.contains(search, true) }
                    if (filtered.isEmpty()) item { Text(tr(S.resource_empty), Modifier.padding(vertical = 16.dp)) }
                    items(filtered.distinctBy { it.first }, key = { it.first }) { (key, label) ->
                        ChoiceRow(label, value == key) { onChange(key); open = false }
                    }
                }
            }
        }, confirmButton = { TextButton({ open = false }, Modifier.heightIn(min = 48.dp)) { Text(tr(S.close)) } })
}
