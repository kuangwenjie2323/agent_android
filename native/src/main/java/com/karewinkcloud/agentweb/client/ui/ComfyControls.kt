@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.karewinkcloud.agentweb.client.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.karewinkcloud.agentweb.client.R
import com.karewinkcloud.agentweb.client.R.string as S
import com.karewinkcloud.agentweb.client.core.*
import com.karewinkcloud.agentweb.client.data.decodeChatBitmap
import com.karewinkcloud.agentweb.client.data.readPhoto
import com.karewinkcloud.agentweb.client.data.readReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    // Apple Music's settings layout: inset grouped cards under grey titles, red checks, a centred title bar.
    AppModalBottomSheet(onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = groupedPageColor()) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.95f)) {
            SheetHeader(tr(S.creation_settings), onClose)
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp)) {
                item("channel") {
                    GroupedSection(tr(S.creation_channel)) {
                        listOf("kaggle_gpu", "runpod_gpu", "cloud_gpu", "partner_api").forEachIndexed { index, channel ->
                            if (index > 0) RowDivider()
                            CheckRow(tr(comfyChannelResource(channel)), state.channel == channel, state.workflows.any { it.channel == channel },
                                tr(comfyChannelHintResource(channel))) { vm.filter(channel = channel) }
                        }
                    }
                }
                item("workflow") {
                    val availability = workflow?.availability(state.resources)
                    val note = availability?.let { availabilityLabel(it) }?.takeIf { it.isNotEmpty() }
                    val unavailable = availability != null && availability.kind != ComfyAvailabilityKind.AVAILABLE
                    GroupedSection(tr(S.workflow), footer = listOfNotNull(tr(S.channel_kaggle_terms).takeIf { workflow?.channel == "kaggle_gpu" },
                        note.takeIf { !unavailable }).joinToString("\n").ifEmpty { null }) {
                        // The chosen workflow as a cover row, like a "now playing" track.
                        Row(Modifier.fillMaxWidth().clickable(onClick = onGallery).padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                                RemoteImage(workflow?.coverUrl, vm, Modifier.fillMaxSize()) { AppIcon(R.drawable.aw_create) }
                            }
                            Column(Modifier.weight(1f)) {
                                Text(workflow?.title?.substringBefore(" · ") ?: tr(S.select_value), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface)
                                Text(workflow?.title?.substringAfter(" · ", "")?.ifBlank { null } ?: workflow?.let { tr(comfyChannelResource(it.channel)) } ?: tr(S.workflow),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(androidx.compose.ui.res.painterResource(R.drawable.aw_chevron), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
                        }
                    }
                    if (unavailable && note != null) Text(note, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                // LoRA and its strength sit under the "Models & LoRA" heading with the other resources,
                // not among the size and seed controls; reference pictures get their own card.
                val (loraFields, basicFields) = groups?.basic.orEmpty().filter { it.key !in sizeKeys }.partition(ComfyInput::isLoraControl)
                val (assetFields, plainFields) = basicFields.partition { it.type == "asset" }
                items(assetFields, key = { "asset-${workflow?.id}-${it.key}" }) { input ->
                    Box(Modifier.padding(bottom = 20.dp)) { SchemaField(input, state.values[input.key] ?: input.default, state, vm) { vm.parameter(input.key, it) } }
                }
                if (sizes.isNotEmpty() || plainFields.isNotEmpty()) item("basic") {
                    GroupedSection(tr(S.creation_basic)) {
                        if (sizes.isNotEmpty()) {
                            var sizeSheet by remember { mutableStateOf(false) }
                            val current = sizes.firstOrNull { size -> size.parameters.all { state.values[it.key] == it.value } }?.label
                            PickerButton(tr(S.size), current ?: tr(S.select_value)) { sizeSheet = true }
                            if (sizeSheet) SizeSheet(sizes, current, vm::size) { sizeSheet = false }
                        }
                        plainFields.forEachIndexed { index, input ->
                            if (index > 0 || sizes.isNotEmpty()) RowDivider()
                            key(input.key) { SchemaField(input, state.values[input.key] ?: input.default, state, vm) { vm.parameter(input.key, it) } }
                        }
                    }
                }
                if (state.needsResources || loraFields.isNotEmpty()) item("resources") {
                    val status = when {
                        state.resourcesLoading -> tr(S.resources_loading)
                        state.resourcesError || (state.needsResources && state.resources?.ready != true) -> tr(S.resources_failed)
                        state.resources?.stale == true -> tr(S.resources_stale)
                        else -> null
                    }
                    GroupedSection(tr(S.resources), footer = if (state.needsResources) tr(S.resource_compatibility) else null) {
                        if (state.needsResources) ValueRow(tr(S.resources_refresh), status.orEmpty(), enabled = !state.resourcesLoading, chevron = false) {
                            vm.ensureResources(true)
                        }
                        loraFields.forEachIndexed { index, input ->
                            if (index > 0 || state.needsResources) RowDivider()
                            key(input.key) { SchemaField(input, state.values[input.key] ?: input.default, state, vm) { vm.parameter(input.key, it) } }
                        }
                    }
                }
                val advancedFields = groups?.advanced.orEmpty().filter { it.key !in sizeKeys }
                if (advancedFields.isNotEmpty()) item("advanced") {
                    val description = tr(if (advanced) S.expanded else S.collapsed)
                    GroupedSection(null) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { advanced = !advanced }
                            .semantics { stateDescription = description }.padding(start = 16.dp, end = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(tr(S.advanced_group), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            Icon(androidx.compose.ui.res.painterResource(R.drawable.aw_chevron), null,
                                Modifier.size(18.dp).graphicsLayer { rotationZ = if (advanced) 90f else 0f }, tint = MaterialTheme.colorScheme.outline)
                        }
                        if (advanced) advancedFields.forEach { input ->
                            RowDivider()
                            key(input.key) { SchemaField(input, state.values[input.key] ?: input.default, state, vm) { vm.parameter(input.key, it) } }
                        }
                    }
                }
                if (state.invalidField != null) item("validation") {
                    Text(comfyError("invalid_parameters"), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** One parameter as a row of a grouped card: a menu, a switch, a slider, or a value typed in place. */
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
        input.type == "boolean" -> Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(value.toBoolean(), { onChange(it.toString()) }, Modifier.semantics { contentDescription = title },
                colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary))
        }
        input.key == "seed" -> NumericEntry(input, value, state, onChange)  // nobody drags to a seed
        range != null -> {
            var exact by remember(input.key) { mutableStateOf(false) }
            Column(Modifier.padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton({ exact = !exact }, colors = quietButton()) { Text(tr(if (exact) S.done else S.creation_exact), style = MaterialTheme.typography.labelLarge) }
                }
                AppleSlider(range.fraction(value), { onChange(range.value(it)) }, Modifier.fillMaxWidth().semantics { contentDescription = title })
            }
            if (exact || state.invalidField == input.key) { RowDivider(); NumericEntry(input, value, state, onChange) }
        }
        else -> NumericEntry(input, value, state, onChange)
    }
}

/** A typed value in a card row: the name on the left, the value editable on the right (text spans the row). */
@Composable
private fun NumericEntry(input: ComfyInput, value: String, state: ComfyState, onChange: (String) -> Unit) {
    val error = state.invalidField == input.key
    val multiline = input.type == "string"
    val label = fieldLabel(input)
    val textStyle = MaterialTheme.typography.bodyLarge.copy(
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = if (multiline) androidx.compose.ui.text.style.TextAlign.Start else androidx.compose.ui.text.style.TextAlign.End)
    val keyboard = KeyboardOptions(keyboardType = if ((input.min ?: 0.0) < 0) KeyboardType.Text else if (input.type == "integer") KeyboardType.Number
        else if (input.type == "number") KeyboardType.Decimal else KeyboardType.Text)
    fun bound(key: String) = (input.spec[key] as? JsonPrimitive)?.content?.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: "…"
    val bounds = if (input.min != null || input.max != null) tr(S.bounds, bound("minimum"), bound("maximum")) else null
    val cursor = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary)
    if (multiline) Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.foundation.text.BasicTextField(value, onChange, Modifier.fillMaxWidth().semantics { contentDescription = label },
            textStyle = textStyle.copy(color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface),
            maxLines = 4, keyboardOptions = keyboard, cursorBrush = cursor)
    } else Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (bounds != null) Text(bounds, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        androidx.compose.foundation.text.BasicTextField(value, onChange, Modifier.widthIn(min = 64.dp, max = 180.dp)
            .semantics { contentDescription = label }, textStyle = textStyle, singleLine = true, keyboardOptions = keyboard, cursorBrush = cursor)
    }
}

@Composable
private fun ReferenceImagePicker(input: ComfyInput, value: String, state: ComfyState, vm: ComfyViewModel, onChange: (String) -> Unit) {
    val context = LocalContext.current
    // Cloud outputs can only feed Cloud workflows; the free GPU and RunPod take pictures uploaded from the phone.
    val cloudOnly = state.workflow?.channel in setOf("kaggle_gpu", "runpod_gpu")
    val options = if (input.media != "image" || cloudOnly) emptyList() else state.jobs.filter { it.canReferenceInCloud(state.workflows) }.flatMap { job ->
        job.outputs.filter { it.mime.startsWith("image/") }.map { output -> job to output }
    }
    val chosen = runCatching { wireJson.parseToJsonElement(value).jsonObject }.getOrNull()
    val uploadId = chosen?.string("upload_id")?.takeIf(::validUploadId)
    val uploading = state.uploading == input.key
    val media = input.media
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.uploadReference(input.key) {
            if (media == "image") readPhoto(context.applicationContext, uri) else readReference(context.applicationContext, uri, media)
        }
    }
    val audioFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.uploadReference(input.key) { readReference(context.applicationContext, uri, "audio") }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(fieldLabel(input), style = MaterialTheme.typography.bodyMedium)
        Text(tr(if (options.isEmpty()) S.asset_empty else S.asset_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (chosen != null && uploadId == null && options.none { (job, output) -> chosen.string("job_id") == job.jobId && chosen.long("output_index") == output.index.toLong() })
            Text(tr(S.asset_retained), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "photos") {
                // A photo from this device: picked, shrunk to 2048 px, then sent straight to cloud storage.
                val photo by produceState<android.graphics.Bitmap?>(null, uploadId) {
                    this.value = uploadId?.let { id -> withContext(Dispatchers.IO) { runCatching { decodeChatBitmap(vm.referenceFile(id), 360) }.getOrNull() } }
                }
                ReferenceTile(selected = uploadId != null, onClick = {
                    if (!uploading) runCatching { when (media) {
                        "video" -> photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                        "audio" -> audioFiles.launch(arrayOf("audio/*"))
                        else -> photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    } }
                }) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                        val image = photo
                        when {
                            uploading -> CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                            uploadId != null && image != null -> Image(image.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            media == "video" -> AppIcon(R.drawable.aw_play)
                            media == "audio" -> AppIcon(R.drawable.aw_file)
                            else -> AppIcon(R.drawable.aw_photos)
                        }
                    }
                    Text(tr(when {
                        uploading -> S.asset_uploading
                        media == "video" -> S.asset_from_videos
                        media == "audio" -> S.asset_from_audio
                        else -> S.asset_from_photos
                    }), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                    Text(tr(if (uploadId != null) (if (media == "image") S.asset_photo_selected else S.asset_material_selected)
                        else if (input.required) S.asset_photo_choose else S.asset_optional),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                }
            }
            items(options, key = { "${it.first.requestId}-${it.second.index}" }) { (job, output) ->
                val selected = chosen?.string("job_id") == job.jobId && chosen?.long("output_index") == output.index.toLong()
                ReferenceTile(selected, onClick = {
                    onChange(buildJsonObject { put("job_id", job.jobId); put("output_index", output.index) }.toString())
                }) {
                    ComfyThumbnail(job.copy(outputs = listOf(output)), vm, Modifier.fillMaxWidth().aspectRatio(1f))
                    Text(state.workflows.find { it.id == job.workflowId }?.title ?: job.workflowId,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                    Text(tr(if (selected) S.asset_selected_number else S.output_number, output.index + 1), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        state.uploadError?.takeIf { !uploading && it.first == input.key }?.let { (_, code) ->
            Text(comfyError(code), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ReferenceTile(selected: Boolean, onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), border = BorderStroke(if (selected) 2.dp else 1.dp,
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.width(132.dp).semantics { this.selected = selected }) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
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
    AppModalBottomSheet(onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = groupedPageColor()) {
        Column(Modifier.fillMaxWidth()) {
            SheetHeader(tr(if (quick.isEmpty()) S.size else S.quick_settings), onClose)
            val (upscaled, native) = sizes.partition { sizeIsUpscaled(it.label) }
            // A combined sheet keeps open after a pick, so every shortcut can be set in one visit.
            val closeOnPick = quick.isEmpty()
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp)) {
                items(quick, key = { "quick-${it.input.key}" }) { choice ->
                    GroupedSection(fieldLabel(choice.input)) {
                        choice.values.forEachIndexed { index, value ->
                            if (index > 0) RowDivider()
                            CheckRow(quickLabel(choice.input, value), sameChoice(value, current(choice.input))) { onQuick(choice.input.key, value) }
                        }
                    }
                }
                listOf(S.size_native to native, S.size_upscaled to upscaled).filter { it.second.isNotEmpty() }.forEach { (title, group) ->
                    item("size-$title") {
                        // With shortcuts above, the frame group needs its own title; alone, the sheet title says it.
                        GroupedSection(when {
                            upscaled.isNotEmpty() -> tr(title)
                            quick.isNotEmpty() -> tr(S.size)
                            else -> null
                        }, footer = if (title == S.size_upscaled) tr(S.size_upscaled_hint) else null) {
                            group.forEachIndexed { index, size ->
                                if (index > 0) RowDivider()
                                CheckRow(size.label, size.label == selected) { onPick(size); if (closeOnPick) onClose() }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The tonal field button used for every setting in the creation sheet. */
@Composable
internal fun PickerButton(title: String, value: String, enabled: Boolean = true, onClick: () -> Unit) =
    // A settings row of a grouped card: name on the left, current value on the right, then a chevron.
    ValueRow(title, value, enabled, onClick = onClick)

/** Grey track with an accent fill, white thumb. */
@Composable
internal fun appSliderColors() = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary,
    activeTrackColor = MaterialTheme.colorScheme.primary, inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    activeTickColor = androidx.compose.ui.graphics.Color.Transparent, inactiveTickColor = androidx.compose.ui.graphics.Color.Transparent)

@Composable
private fun ValuePicker(title: String, value: String, options: List<Pair<String, String>>, enabled: Boolean = true, onChange: (String) -> Unit) {
    // A short list opens as a menu at the row (Apple's pop-up button); a long one keeps a searchable list.
    if (options.size <= 12) { MenuValueRow(title, value, options, enabled, onChange); return }
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

/** Opens the right picker for a reference input and uploads the choice: photos, videos, or an audio file. */
@Composable
internal fun rememberReferencePicker(vm: ComfyViewModel): (ComfyInput) -> Unit {
    val context = LocalContext.current
    var target by remember { mutableStateOf<ComfyInput?>(null) }
    val visual = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val input = target ?: return@rememberLauncherForActivityResult
        if (uri != null) vm.uploadReference(input.key) {
            if (input.media == "image") readPhoto(context.applicationContext, uri) else readReference(context.applicationContext, uri, input.media)
        }
    }
    val audio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val input = target ?: return@rememberLauncherForActivityResult
        if (uri != null) vm.uploadReference(input.key) { readReference(context.applicationContext, uri, "audio") }
    }
    return { input ->
        target = input
        runCatching { when (input.media) {
            "video" -> visual.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
            "audio" -> audio.launch(arrayOf("audio/*"))
            else -> visual.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } }
    }
}

private fun referenceLabel(input: ComfyInput) = when (input.media) { "video" -> S.reference_video; "audio" -> S.reference_audio; else -> S.reference_image }

/** Composer button: add a reference picture, video or audio clip to the workflow when it takes one. */
@Composable
internal fun ReferenceButton(state: ComfyState, vm: ComfyViewModel) {
    val inputs = state.workflow?.inputs.orEmpty().filter { it.type == "asset" }
    if (inputs.isEmpty()) return
    val pick = rememberReferencePicker(vm)
    var menu by remember { mutableStateOf(false) }
    Box {
        AssistChip(onClick = { if (state.uploading == null) { if (inputs.size == 1) pick(inputs.single()) else menu = true } },
            label = { Text(tr(if (state.uploading != null) S.asset_uploading else S.reference_chip)) },
            leadingIcon = { if (state.uploading != null) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else AppIcon(R.drawable.aw_photos) })
        DropdownMenu(menu, { menu = false }) {
            inputs.forEach { input ->
                val set = (state.values[input.key] ?: "").isNotBlank()
                DropdownMenuItem(text = { Text(tr(referenceLabel(input)) + if (set) " ✓" else "") },
                    leadingIcon = { AppIcon(when (input.media) { "video" -> R.drawable.aw_play; "audio" -> R.drawable.aw_file; else -> R.drawable.aw_photos }) },
                    onClick = { menu = false; pick(input) })
            }
        }
    }
}

/** The references attached to this creation, each removable; an upload failure shows here too. */
@Composable
internal fun AttachedReferences(state: ComfyState, vm: ComfyViewModel) {
    val attached = state.workflow?.inputs.orEmpty().filter { it.type == "asset" && (state.values[it.key] ?: "").isNotBlank() }
    val failure = state.uploadError
    if (attached.isEmpty() && failure == null) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)) {
        items(attached, key = { it.key }) { input ->
            val uploadId = runCatching { wireJson.parseToJsonElement(state.values[input.key].orEmpty()).jsonObject.string("upload_id") }.getOrNull()
            val photo by produceState<android.graphics.Bitmap?>(null, uploadId) {
                this.value = uploadId?.takeIf { input.media == "image" }?.let { id ->
                    withContext(Dispatchers.IO) { runCatching { decodeChatBitmap(vm.referenceFile(id), 160) }.getOrNull() } }
            }
            InputChip(selected = true, onClick = {}, label = { Text(tr(referenceLabel(input)), maxLines = 1) },
                avatar = { photo?.let { Image(it.asImageBitmap(), null, Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop) }
                    ?: AppIcon(when (input.media) { "video" -> R.drawable.aw_play; "audio" -> R.drawable.aw_file; else -> R.drawable.aw_photos }) },
                trailingIcon = { Box(Modifier.size(18.dp).clickable { vm.parameter(input.key, "") }, contentAlignment = Alignment.Center) {
                    AppIcon(R.drawable.aw_close, tr(S.reference_remove)) } })
        }
        if (failure != null) item("failure") { Text(comfyError(failure.second), Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}
