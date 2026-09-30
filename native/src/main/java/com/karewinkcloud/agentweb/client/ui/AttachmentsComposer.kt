package com.karewinkcloud.agentweb.client.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.karewinkcloud.agentweb.client.R
import com.karewinkcloud.agentweb.client.R.string as S
import com.karewinkcloud.agentweb.client.core.*
import com.karewinkcloud.agentweb.client.data.readAttachment
import kotlinx.coroutines.*
import java.io.File

@Composable
internal fun AttachmentChips(chat: ChatState, vm: AgentViewModel) {
    if (chat.attachments.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(chat.attachments, key = { it.id }) { attachment ->
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (attachment.kind == AttachmentKind.IMAGE) ChatMediaCard(attachment.media(), thumbnail = true)
                    else Text(attachment.name, Modifier.widthIn(max = 160.dp), maxLines = 2, style = MaterialTheme.typography.bodySmall)
                    ActionIcon(R.drawable.aw_close, tr(S.remove_attachment, attachment.name)) { vm.removeAttachment(attachment.id) }
                }
            }
        }
    }
    if (chat.attachmentLoading) Text(tr(S.reading_attachment), Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun AttachmentPicker(chat: ChatState, supportsImages: Boolean, vm: AgentViewModel) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var pickerOwner by rememberSaveable { mutableStateOf("") }
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    fun consume(uris: List<Uri>, image: Boolean, camera: String? = null) {
        val owner = pickerOwner
        if (uris.isEmpty()) { camera?.let { File(it).delete() }; return }
        if (uris.size + chat.attachments.size > MAX_ATTACHMENTS) { vm.attachmentFailure(owner, "每条消息最多添加 4 个附件"); return }
        vm.readAttachments(owner) {
            val selected = mutableListOf<Attachment>()
            try {
                for (uri in uris) selected += readAttachment(context.applicationContext, uri, image)
                selected
            } catch (e: Exception) { selected.forEach { it.previewPath?.let { path -> File(path).delete() } }; throw e }
            finally { camera?.let { File(it).delete() } }
        }
    }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_ATTACHMENTS)) { consume(it, true) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { consume(listOfNotNull(it), false) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val path = cameraPath; cameraPath = null
        if (path != null && ok) consume(listOf(FileProvider.getUriForFile(context, "${context.packageName}.comfy-output", File(path))), true, path)
        else path?.let { File(it).delete() }
    }
    Box {
        ActionIcon(R.drawable.aw_plus, tr(S.add_attachment), !chat.attachmentLoading && chat.attachments.size < MAX_ATTACHMENTS) { expanded = true }
        DropdownMenu(expanded, { expanded = false }) {
            if (supportsImages) {
                DropdownMenuItem(text = { Text(tr(S.photos)) }, onClick = {
                    expanded = false; pickerOwner = vm.attachmentOwner()
                    try { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    catch (_: Exception) { vm.attachmentFailure(pickerOwner, "无法打开照片选择器") }
                })
                DropdownMenuItem(text = { Text(tr(S.take_photo)) }, onClick = {
                    expanded = false; pickerOwner = vm.attachmentOwner()
                    try {
                        val file = File(File(context.cacheDir, "chat-camera").apply { mkdirs() }, "${newControlId()}.jpg")
                        cameraPath = file.absolutePath
                        camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.comfy-output", file))
                    } catch (_: Exception) { cameraPath?.let { File(it).delete() }; cameraPath = null; vm.attachmentFailure(pickerOwner, "无法打开相机") }
                })
            }
            DropdownMenuItem(text = { Text(tr(S.files)) }, onClick = {
                expanded = false; pickerOwner = vm.attachmentOwner()
                try { files.launch(arrayOf("*/*")) } catch (_: Exception) { vm.attachmentFailure(pickerOwner, "无法打开文件选择器") }
            })
        }
    }
}
