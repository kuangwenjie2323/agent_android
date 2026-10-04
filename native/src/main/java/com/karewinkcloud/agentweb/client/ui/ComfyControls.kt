@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.karewinkcloud.agentweb.client.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.karewinkcloud.agentweb.client.R
import com.karewinkcloud.agentweb.client.R.string as S
import com.karewinkcloud.agentweb.client.core.*
import kotlinx.serialization.json.*

@Composable
internal fun availabilityLabel(availability: ComfyAvailability): String = when (availability.kind) {
    ComfyAvailabilityKind.AVAILABLE -> when {
        availability.missingModels.size > 1 -> tr(S.workflow_missing_count, availability.missingModels.first(), availability.missingModels.size)
        availability.missingModels.size == 1 -> tr(S.missing_models, availability.missingModels.first())
        else -> ""
    }
    ComfyAvailabilityKind.UNAVAILABLE -> tr(S.workflow_unavailable)
    ComfyAvailabilityKind.WAITING_RESOURCES -> tr(S.workflow_waiting_resources)
    ComfyAvailabilityKind.MISSING_MODELS -> if (availability.missingModels.size > 1)
        tr(S.workflow_missing_count, availability.missingModels.first(), availability.missingModels.size)
        else tr(S.missing_models, availability.missingModels.firstOrNull().orEmpty())
}

@Composable
internal fun WorkflowGallery(state: ComfyState, vm: ComfyViewModel, onClose: () -> Unit) {
    var kind by remember { mutableStateOf(state.kind) }
    var query by remember { mutableStateOf("") }
    val groups = remember(state.workflows, kind, query) { comfyGalleryGroups(state.workflows, kind, query) }
    LaunchedEffect(Unit) { vm.ensureResources() }
    AppModalBottomSheet(onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight()) {
            Row(Modifier.padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr(S.workflow_gallery), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                ActionIcon(R.drawable.aw_close, tr(S.close), onClick = onClose)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("image", "video", "audio").forEach { candidate ->
                    FilterChip(kind == candidate, { kind = candidate }, label = { Text(kindLabel(candidate)) }, modifier = Modifier.weight(1f))
                }
            }
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                singleLine = true, label = { Text(tr(S.workflow_search)) })
            if (state.resourcesLoading || state.resourcesError) Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr(if (state.resourcesError) S.resources_failed else S.resources_loading), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                if (state.resourcesError) TextButton({ vm.ensureResources(true) }) { Text(tr(S.refresh)) }
            }
            LazyVerticalGrid(GridCells.Adaptive(240.dp), Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (groups.isEmpty()) item("empty", span = { GridItemSpan(maxLineSpan) }) {
                    Text(tr(S.workflow_no_matches), Modifier.padding(vertical = 24.dp), style = MaterialTheme.typography.bodyMedium)
                }
                groups.forEach { group ->
                    item("group-${group.channel}-${group.family}", span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.padding(top = 8.dp)) {
                            Text(tr(comfyChannelResource(group.channel)),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text(group.family.ifBlank { tr(S.workflow_other_family) }, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    items(group.workflows, key = { it.id }) { workflow ->
                        val availability = workflow.availability(state.resources)
                        val chosen = workflow.id == state.workflowId
                        Surface(onClick = { vm.choose(workflow.id); onClose() }, enabled = availability.kind == ComfyAvailabilityKind.AVAILABLE,
                            shape = RoundedCornerShape(20.dp), color = if (chosen) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth().semantics { selected = chosen }) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(workflow.modelFamily().ifBlank { kindLabel(workflow.kind) }, Modifier.weight(1f), maxLines = 1,
                                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.headlineSmall,
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = .7f))
                                    if (chosen) AppIcon(R.drawable.aw_check, tr(S.workflow_selected))
                                }
                                Text(workflow.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                                Text(workflow.description.ifBlank { workflow.id }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (availability.kind != ComfyAvailabilityKind.AVAILABLE) Text(availabilityLabel(availability),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CreationSettings(state: ComfyState, vm: ComfyViewModel, onGallery: () -> Unit, onClose: () -> Unit) {
    var advanced by remember(state.workflowId) { mutableStateOf(false) }
    val workflow = state.workflow
    val groups = workflow?.fieldGroups()
    val sizes = workflow?.sizes().orEmpty()
    val sizeKeys = sizes.flatMap { it.parameters.keys }.toSet()
    LaunchedEffect(state.invalidField, state.workflowId) {
        if (groups?.advanced?.any { it.key == state.invalidField } == true) advanced = true
    }
    AppModalBottomSheet(onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.95f)) {
            Row(Modifier.padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr(S.creation_settings), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                ActionIcon(R.drawable.aw_check, tr(S.done), onClick = onClose)
            }
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item("basic") { Text(tr(S.creation_basic), style = MaterialTheme.typography.titleMedium) }
                item("channel") {
                    Text(tr(S.creation_channel), style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("kaggle_gpu", "cloud_gpu", "partner_api").forEach { channel ->
                            FilterChip(state.channel == channel, { vm.filter(channel = channel) },
                                enabled = state.workflows.any { it.channel == channel }, label = { Text(tr(comfyChannelResource(channel))) })
                        }
                    }
                }
                item("workflow") {
                    FilledTonalButton(onGallery, Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(tr(S.workflow), style = MaterialTheme.typography.labelMedium)
                            Text(workflow?.title ?: tr(S.select_value), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        AppIcon(R.drawable.aw_down)
                    }
                    workflow?.let {
                        Text(tr(comfyChannelHintResource(it.channel)), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                        if (it.channel == "kaggle_gpu") Text(tr(S.channel_kaggle_terms), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall)
                        val availability = it.availability(state.resources)
                        availabilityLabel(availability).takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                            color = if (availability.kind == ComfyAvailabilityKind.AVAILABLE) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error) }
                    }
                }
                if (sizes.isNotEmpty()) item("size") {
                    ValuePicker(tr(S.size), sizes.firstOrNull { size -> size.parameters.all { state.values[it.key] == it.value } }?.label.orEmpty(),
                        sizes.map { it.label to it.label }) { label -> sizes.find { it.label == label }?.let(vm::size) }
                }
                items(groups?.basic.orEmpty().filter { it.key !in sizeKeys }, key = { "basic-${workflow?.id}-${it.key}" }) { input ->
                    SchemaField(input, state.values[input.key] ?: input.default, state, vm) { vm.parameter(input.key, it) }
                }
                if (state.needsResources) item("resources") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr(S.resources), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                        TextButton({ vm.ensureResources(true) }, enabled = !state.resourcesLoading) { Text(tr(S.resources_refresh)) }
                    }
                    if (state.resourcesLoading) Text(tr(S.resources_loading), style = MaterialTheme.typography.bodySmall)
                    if (state.resourcesError || (state.resources?.ready != true && !state.resourcesLoading)) Text(tr(S.resources_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (state.resources?.stale == true) Text(tr(S.resources_stale), style = MaterialTheme.typography.bodySmall)
                    Text(tr(S.resource_compatibility), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!groups?.advanced.isNullOrEmpty()) {
                    item("advanced") {
                        val description = tr(if (advanced) S.expanded else S.collapsed)
                        OutlinedButton({ advanced = !advanced }, Modifier.fillMaxWidth().semantics { stateDescription = description }) {
                            Text(tr(S.advanced_group), Modifier.weight(1f)); Text(tr(if (advanced) S.collapse else S.creation_expand))
                        }
                    }
                    if (advanced) items(groups?.advanced.orEmpty().filter { it.key !in sizeKeys }, key = { "advanced-${workflow?.id}-${it.key}" }) { input ->
                        SchemaField(input, state.values[input.key] ?: input.default, state, vm) { vm.parameter(input.key, it) }
                    }
                }
                if (state.invalidField != null) item("validation") { Text(comfyError("invalid_parameters"), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun SchemaField(input: ComfyInput, value: String, state: ComfyState, vm: ComfyViewModel, onChange: (String) -> Unit) {
    val title = fieldLabel(input)
    val range = input.sliderRange()
    when {
        input.resource != null -> {
            val resources = state.resources?.items.orEmpty().filter { it.category == input.resource }
                .sortedByDescending { input.family != null && it.family == input.family }
            ValuePicker(title, value, resources.map { it.name to "${it.name}\n${it.family.orEmpty()}" }, enabled = state.resources?.ready == true, onChange = onChange)
        }
        input.type == "asset" -> ReferenceImagePicker(input, value, state, vm, onChange)
        input.options.isNotEmpty() -> ValuePicker(title, value, input.options.mapNotNull { (it as? JsonPrimitive)?.content?.let { raw -> raw to raw } }, onChange = onChange)
        input.type == "boolean" -> Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(value.toBoolean(), { onChange(it.toString()) }, Modifier.semantics { contentDescription = title })
        }
        range != null -> {
            var exact by remember(input.key) { mutableStateOf(input.key == "seed") }
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(value, style = MaterialTheme.typography.labelLarge)
                    TextButton({ exact = !exact }) { Text(tr(if (exact) S.done else S.creation_exact)) }
                }
                Slider(range.fraction(value), { onChange(range.value(it)) }, Modifier.fillMaxWidth().semantics { contentDescription = title })
                if (exact || state.invalidField == input.key) NumericEntry(input, value, state, onChange)
            }
        }
        else -> NumericEntry(input, value, state, onChange)
    }
}

@Composable
private fun NumericEntry(input: ComfyInput, value: String, state: ComfyState, onChange: (String) -> Unit) {
    TextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(fieldLabel(input)) },
        isError = state.invalidField == input.key, minLines = 1, maxLines = if (input.type == "string") 4 else 1,
        keyboardOptions = KeyboardOptions(keyboardType = if ((input.min ?: 0.0) < 0) KeyboardType.Text else if (input.type == "integer") KeyboardType.Number else if (input.type == "number") KeyboardType.Decimal else KeyboardType.Text),
        supportingText = {
            if (input.min != null || input.max != null) {
                fun bound(key: String) = (input.spec[key] as? JsonPrimitive)?.content?.toBigDecimalOrNull()
                    ?.stripTrailingZeros()?.toPlainString() ?: "…"
                Text(tr(S.bounds, bound("minimum"), bound("maximum")))
            }
        })
}

@Composable
private fun ReferenceImagePicker(input: ComfyInput, value: String, state: ComfyState, vm: ComfyViewModel, onChange: (String) -> Unit) {
    val options = state.jobs.filter { it.canReferenceInCloud(state.workflows) }.flatMap { job ->
        job.outputs.filter { it.mime.startsWith("image/") }.map { output -> job to output }
    }
    val chosen = runCatching { wireJson.parseToJsonElement(value).jsonObject }.getOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(fieldLabel(input), style = MaterialTheme.typography.bodyMedium)
        Text(tr(if (options.isEmpty()) S.asset_empty else S.asset_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (chosen != null && options.none { (job, output) -> chosen.string("job_id") == job.jobId && chosen.long("output_index") == output.index.toLong() })
            Text(tr(S.asset_retained), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        if (options.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(options, key = { "${it.first.requestId}-${it.second.index}" }) { (job, output) ->
                val selected = chosen?.string("job_id") == job.jobId && chosen?.long("output_index") == output.index.toLong()
                Surface(onClick = {
                    onChange(buildJsonObject { put("job_id", job.jobId); put("output_index", output.index) }.toString())
                }, shape = RoundedCornerShape(16.dp), border = BorderStroke(if (selected) 2.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.width(132.dp).semantics { this.selected = selected }) {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ComfyThumbnail(job.copy(outputs = listOf(output)), vm, Modifier.fillMaxWidth().aspectRatio(1f))
                        Text(state.workflows.find { it.id == job.workflowId }?.title ?: job.workflowId,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                        Text(tr(if (selected) S.asset_selected_number else S.output_number, output.index + 1), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
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
