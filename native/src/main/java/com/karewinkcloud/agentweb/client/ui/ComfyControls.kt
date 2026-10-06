@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.karewinkcloud.agentweb.client.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
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
                    PillChip(kind == candidate, { kind = candidate }, label = { Text(kindLabel(candidate)) }, modifier = Modifier.weight(1f))
                }
            }
            TextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), singleLine = true,
                shape = RoundedCornerShape(12.dp), placeholder = { Text(tr(S.workflow_search)) }, leadingIcon = { AppIcon(R.drawable.aw_search) },
                colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent))
            if (state.resourcesLoading || state.resourcesError) Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr(if (state.resourcesError) S.resources_failed else S.resources_loading), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                if (state.resourcesError) TextButton({ vm.ensureResources(true) }) { Text(tr(S.refresh)) }
            }
            LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (groups.isEmpty()) item("empty", span = { GridItemSpan(maxLineSpan) }) {
                    Text(tr(S.workflow_no_matches), Modifier.padding(vertical = 24.dp), style = MaterialTheme.typography.bodyMedium)
                }
                groups.forEach { group ->
                    item("group-${group.channel}-${group.family}", span = { GridItemSpan(maxLineSpan) }) {
                        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(group.family.ifBlank { tr(S.workflow_other_family) }, style = MaterialTheme.typography.titleLarge)
                            Text(tr(comfyChannelResource(group.channel)), Modifier.padding(bottom = 3.dp), style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(group.workflows, key = { it.id }) { workflow ->
                        val availability = workflow.availability(state.resources)
                        val chosen = workflow.id == state.workflowId
                        ArtworkCard(workflow.title.substringBefore(" · "),
                            workflow.title.substringAfter(" · ", "").ifBlank { null } ?: availabilityLabel(availability).ifBlank { null },
                            null, { vm.choose(workflow.id); onClose() }, enabled = availability.kind == ComfyAvailabilityKind.AVAILABLE) {
                            RemoteImage(workflow.coverUrl, vm, Modifier.fillMaxSize()) {
                                Text(workflow.modelFamily().ifBlank { kindLabel(workflow.kind) }, style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                            if (chosen) Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).semantics { selected = true }) {
                                Box(Modifier.padding(4.dp)) { AppIcon(R.drawable.aw_check, tr(S.workflow_selected)) }
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
                        listOf("kaggle_gpu", "runpod_gpu", "cloud_gpu", "partner_api").forEach { channel ->
                            PillChip(state.channel == channel, { vm.filter(channel = channel) },
                                enabled = state.workflows.any { it.channel == channel }, label = { Text(tr(comfyChannelResource(channel))) })
                        }
                    }
                }
                item("workflow") {
                    // The chosen workflow as a cover row, like a "now playing" track.
                    Surface(onGallery, Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                                RemoteImage(workflow?.coverUrl, vm, Modifier.fillMaxSize()) { AppIcon(R.drawable.aw_create) }
                            }
                            Column(Modifier.weight(1f)) {
                                Text(workflow?.title?.substringBefore(" · ") ?: tr(S.select_value), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface)
                                Text(workflow?.let { tr(comfyChannelResource(it.channel)) } ?: tr(S.workflow), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Box(Modifier.size(20.dp)) { CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) { AppIcon(R.drawable.aw_chevron) } }
                        }
                    }
                    workflow?.let {
                        Text(tr(comfyChannelHintResource(it.channel)), Modifier.padding(top = 8.dp, start = 4.dp), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (it.channel == "kaggle_gpu") Text(tr(S.channel_kaggle_terms), Modifier.padding(top = 2.dp, start = 4.dp), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val availability = it.availability(state.resources)
                        availabilityLabel(availability).takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                            color = if (availability.kind == ComfyAvailabilityKind.AVAILABLE) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error) }
                    }
                }
                if (sizes.isNotEmpty()) item("size") {
                    var sizeSheet by remember { mutableStateOf(false) }
                    val current = sizes.firstOrNull { size -> size.parameters.all { state.values[it.key] == it.value } }?.label
                    PickerButton(tr(S.size), current ?: tr(S.select_value)) { sizeSheet = true }
                    if (sizeSheet) SizeSheet(sizes, current, vm::size) { sizeSheet = false }
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
                        Surface({ advanced = !advanced }, Modifier.fillMaxWidth().heightIn(min = 52.dp).semantics { stateDescription = description },
                            shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(tr(S.advanced_group), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                Box(Modifier.size(20.dp).graphicsLayer { rotationZ = if (advanced) 90f else 0f }) {
                                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) { AppIcon(R.drawable.aw_chevron) }
                                }
                            }
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
        input.options.isNotEmpty() -> ValuePicker(title, value, input.options.mapNotNull { (it as? JsonPrimitive)?.content?.let { raw ->
            raw to if (input.key in setOf("duration", "duration_seconds")) durationLabel(raw) else raw } }, onChange = onChange)
        input.type == "boolean" -> Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(value.toBoolean(), { onChange(it.toString()) }, Modifier.semantics { contentDescription = title })
        }
        input.key == "seed" -> NumericEntry(input, value, state, onChange)  // nobody drags to a seed
        range != null -> {
            var exact by remember(input.key) { mutableStateOf(false) }
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton({ exact = !exact }, colors = quietButton()) { Text(tr(if (exact) S.done else S.creation_exact), style = MaterialTheme.typography.labelLarge) }
                }
                Slider(range.fraction(value), { onChange(range.value(it)) }, Modifier.fillMaxWidth().semantics { contentDescription = title },
                    colors = appSliderColors())
                if (exact || state.invalidField == input.key) NumericEntry(input, value, state, onChange)
            }
        }
        else -> NumericEntry(input, value, state, onChange)
    }
}

@Composable
private fun NumericEntry(input: ComfyInput, value: String, state: ComfyState, onChange: (String) -> Unit) {
    TextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(fieldLabel(input)) }, shape = RoundedCornerShape(12.dp),
        colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer, errorContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent),
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

private val SizeLabel = Regex("""^(\d+:\d+)(?: (1080p|2K|4K))? · (\d+)×(\d+)$""")
/** Short chip text: "4K · 16:9" for upscaled presets, "1:1 · 1024" for native ones; other labels unchanged. */
internal fun sizeChipLabel(label: String): String {
    val match = SizeLabel.matchEntire(label) ?: return label
    val (ratio, tier, width, height) = match.destructured
    return if (tier.isNotEmpty()) "$tier · $ratio" else "$ratio · ${maxOf(width.toInt(), height.toInt())}"
}
internal fun sizeIsUpscaled(label: String) = SizeLabel.matchEntire(label)?.groupValues?.get(2)?.isNotEmpty() == true

/** Size and aspect choice, styled like the model sheet: native and AI-upscaled groups of radio rows. */
@Composable
internal fun SizeSheet(sizes: List<ComfySize>, selected: String?, onPick: (ComfySize) -> Unit,
    quick: List<ComfyQuickChoice> = emptyList(), current: (ComfyInput) -> String = { it.default },
    onQuick: (String, String) -> Unit = { _, _ -> }, onClose: () -> Unit) {
    AppModalBottomSheet(onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr(if (quick.isEmpty()) S.size else S.quick_settings), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                ActionIcon(R.drawable.aw_check, tr(S.done), onClick = onClose)
            }
            val (upscaled, native) = sizes.partition { sizeIsUpscaled(it.label) }
            // A combined sheet keeps open after a pick, so every shortcut can be set in one visit.
            val closeOnPick = quick.isEmpty()
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                quick.forEach { choice ->
                    item("title-${choice.input.key}") {
                        Text(fieldLabel(choice.input), Modifier.padding(top = 12.dp, start = 4.dp).semantics { heading() },
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(choice.values, key = { "${choice.input.key}-$it" }) { value ->
                        ChoiceRow(quickLabel(choice.input, value), sameChoice(value, current(choice.input))) { onQuick(choice.input.key, value) }
                    }
                }
                if (quick.isNotEmpty() && sizes.isNotEmpty()) item("title-frame") {
                    Text(tr(S.size), Modifier.padding(top = 12.dp, start = 4.dp).semantics { heading() },
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                listOf(S.size_native to native, S.size_upscaled to upscaled).filter { it.second.isNotEmpty() }.forEach { (title, group) ->
                    if (upscaled.isNotEmpty()) item("title-$title") {
                        Text(tr(title), Modifier.padding(top = 12.dp, start = 4.dp).semantics { heading() },
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(group, key = { it.label }) { size ->
                        ChoiceRow(size.label, size.label == selected, subtitle = if (title == S.size_upscaled) tr(S.size_upscaled_hint) else null) {
                            onPick(size); if (closeOnPick) onClose()
                        }
                    }
                }
            }
        }
    }
}

/** The tonal field button used for every setting in the creation sheet. */
@Composable
internal fun PickerButton(title: String, value: String, enabled: Boolean = true, onClick: () -> Unit) {
    // A settings row: name on the left, current value on the right, then a chevron.
    Surface(onClick, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = enabled, shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(value, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            Box(Modifier.size(20.dp)) { CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) { AppIcon(R.drawable.aw_chevron) } }
        }
    }
}

/** Grey track with an accent fill, white thumb. */
@Composable
internal fun appSliderColors() = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary,
    activeTrackColor = MaterialTheme.colorScheme.primary, inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    activeTickColor = androidx.compose.ui.graphics.Color.Transparent, inactiveTickColor = androidx.compose.ui.graphics.Color.Transparent)

@Composable
private fun ValuePicker(title: String, value: String, options: List<Pair<String, String>>, enabled: Boolean = true, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    PickerButton(title, options.find { it.first == value }?.second?.substringBefore('\n') ?: value.ifBlank { tr(S.select_value) }, enabled) {
        open = true; search = ""
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
