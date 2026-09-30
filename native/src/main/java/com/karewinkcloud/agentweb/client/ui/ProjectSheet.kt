@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.karewinkcloud.agentweb.client.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.karewinkcloud.agentweb.client.R.string as S

@Composable
internal fun ProjectSheet(state: ClientState, vm: AgentViewModel, close: () -> Unit) {
    var root by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var registering by remember { mutableStateOf(false) }
    LaunchedEffect(state.creating) { if (state.creating) close() }
    ModalBottomSheet(close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr(S.project), style = MaterialTheme.typography.titleLarge)
            Text(tr(S.project_hint), style = MaterialTheme.typography.bodySmall)
            val selected = if (state.chat != null) state.chat.conversation.projectId else state.projects.selectedId
            ChoiceRow(tr(S.default_project), selected == null, !state.creating && !state.projectLoading) { vm.selectProject(null); close() }
            state.projects.projects.forEach { project ->
                ChoiceRow(project.name, selected == project.id, !state.creating && !state.projectLoading,
                    listOf(project.rootLabel, project.defaultBranch).filter { it.isNotBlank() }.joinToString(" · ")) { vm.selectProject(project.id); close() }
            }
            state.projectError?.let { ErrorBlock(it) }
            TextButton(vm::refresh, enabled = !state.refreshing, modifier = Modifier.heightIn(min = 48.dp)) { Text(tr(S.refresh)) }
            TextButton({ registering = !registering }, Modifier.heightIn(min = 48.dp)) { Text(tr(S.register_project)) }
            if (registering) {
                OutlinedTextField(root, { root = it }, Modifier.fillMaxWidth(), label = { Text(tr(S.repository_path)) }, singleLine = true)
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(tr(S.project_name)) }, singleLine = true)
                Button({ vm.registerProject(root, name) }, enabled = root.isNotBlank() && !state.projectLoading && !state.creating, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(tr(if (state.projectLoading) S.loading else S.register_project))
                }
            }
        }
    }
}
