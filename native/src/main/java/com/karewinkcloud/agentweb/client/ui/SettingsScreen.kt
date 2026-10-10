package com.karewinkcloud.agentweb.client.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.karewinkcloud.agentweb.client.R
import com.karewinkcloud.agentweb.client.R.string as S
import com.karewinkcloud.agentweb.client.core.ClientError
import com.karewinkcloud.agentweb.client.data.*

@Composable
internal fun ColumnScope.SettingsScreen(vm: SettingsViewModel, openBrowser: (String) -> Unit,
    passkey: PasskeyProvider, model: String, onModels: () -> Unit) {
    val prefs by vm.state.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val auth by vm.authentication.collectAsStateWithLifecycle()
    val phase by vm.phase.collectAsStateWithLifecycle()
    val failure by vm.passkeyFailure.collectAsStateWithLifecycle()
    var editor by rememberSaveable { mutableStateOf<String?>(null) }
    var origin by rememberSaveable(prefs.origin) { mutableStateOf(prefs.origin) }
    val busy = saving || phase != SignInPhase.IDLE
    ScreenTitle(tr(S.settings))
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = PageGutter, end = PageGutter, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsGroup(tr(S.account)) {
            SettingsRow(auth.accountName ?: tr(S.signed_out_row),
                if (auth.signedIn) auth.deviceName.orEmpty() else tr(S.sign_in_explanation), !busy, badge = true, position = RowPosition.FIRST) { editor = "account" }
            SettingsRow(tr(S.account_security), enabled = !busy, position = RowPosition.LAST) { openBrowser(vm.accountUrl()) }
        }
        if (!auth.signedIn) Column {
            Button({ if (signInMethod(failure) == SignInMethod.BROWSER) vm.signIn(prefs.origin, prefs.theme, openBrowser)
                else vm.signInWithPasskey(passkey) }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(tr(if (signInMethod(failure) == SignInMethod.BROWSER) S.use_browser else S.use_passkey))
            }
            TextButton({ if (signInMethod(failure) == SignInMethod.BROWSER) vm.signInWithPasskey(passkey)
                else vm.signIn(prefs.origin, prefs.theme, openBrowser) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(tr(if (signInMethod(failure) == SignInMethod.BROWSER) S.use_passkey else S.use_browser))
            }
        }
        if (phase != SignInPhase.IDLE) Column {
            StatusNotice(tr(when (phase) { SignInPhase.PASSKEY -> S.passkey_waiting; SignInPhase.BROWSER -> S.sign_in_browser
                SignInPhase.SIGNING_OUT -> S.signing_out; else -> S.sign_in_completing }), busy = true)
            if (phase != SignInPhase.SIGNING_OUT) TextButton(vm::cancelPending, Modifier.heightIn(min = 48.dp)) { Text(tr(S.cancel_sign_in)) }
        }
        error?.let { ErrorBlock(ClientError(localMessage(it))) }
        auth.message?.let { StatusNotice(localMessage(it)) }
        SettingsGroup(tr(S.preferences_group)) {
            SettingsRow(tr(S.appearance), tr(when (prefs.theme) { "light" -> S.light; "dark" -> S.dark; else -> S.system }), !busy, position = RowPosition.FIRST) { editor = "theme" }
            SettingsRow(tr(S.default_model), model.ifBlank { tr(S.choose_model) }, !busy, position = RowPosition.MIDDLE, onClick = onModels)
            SettingsRow(tr(S.language), when (prefs.language) { "zh" -> "中文"; "en" -> "English"; else -> tr(S.language_system) }, !busy, position = RowPosition.LAST) { editor = "language" }
        }
        SettingsGroup(tr(S.advanced_group)) {
            SettingsRow(tr(S.server), prefs.origin.removePrefix("https://"), !busy) { editor = "server" }
        }
        Text("AgentWeb App ${com.karewinkcloud.agentweb.client.BuildConfig.VERSION_NAME}", Modifier.align(Alignment.CenterHorizontally),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    when (editor) {
        "server" -> AlertDialog(onDismissRequest = { if (!busy) editor = null }, title = { Text(tr(S.server_url)) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextField(origin, { origin = it }, singleLine = true, enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(), label = { Text(tr(S.server_url)) })
                if (vm.localTesting(origin)) Text(tr(S.local_debug), style = MaterialTheme.typography.bodySmall)
                if (auth.signedIn && origin.trimEnd('/') != prefs.origin) Text(tr(S.server_signout_hint), style = MaterialTheme.typography.bodySmall)
                error?.let { Text(localMessage(it), color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { Button({ vm.save(origin, prefs.theme) { editor = null } }, enabled = !busy) { Text(tr(S.save_settings)) } },
            dismissButton = { TextButton({ editor = null; origin = prefs.origin }, enabled = !busy) { Text(tr(S.close)) } })
        "theme", "language" -> AlertDialog(onDismissRequest = { editor = null }, title = { Text(tr(if (editor == "theme") S.appearance else S.language)) }, text = {
            Column {
                val options = if (editor == "theme") listOf("system" to tr(S.system), "light" to tr(S.light), "dark" to tr(S.dark))
                    else listOf("system" to tr(S.language_system), "zh" to "中文", "en" to "English")
                options.forEach { (value, label) -> ChoiceRow(label, value == if (editor == "theme") prefs.theme else prefs.language, !busy) {
                    if (editor == "theme") vm.save(prefs.origin, value) else vm.language(value)
                    editor = null
                } }
            }
        }, confirmButton = { TextButton({ editor = null }) { Text(tr(S.close)) } })
        "account" -> AlertDialog(onDismissRequest = { editor = null }, title = { Text(auth.accountName ?: tr(S.signed_out_row)) },
            text = { Text(if (auth.signedIn) tr(S.device, auth.deviceName.orEmpty()) else tr(S.sign_in_explanation)) },
            confirmButton = {
                if (auth.signedIn) TextButton({ vm.signOut(); editor = null }, enabled = !busy) { Text(tr(S.sign_out)) }
                else Button({ vm.signInWithPasskey(passkey); editor = null }, enabled = !busy) { Text(tr(S.use_passkey)) }
            }, dismissButton = { TextButton({ editor = null }) { Text(tr(S.close)) } })
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        GroupLabel(title)
        Column(content = content)
    }
}

@Composable
private fun SettingsRow(title: String, subtitle: String? = null, enabled: Boolean = true, badge: Boolean = false,
    position: RowPosition = RowPosition.ONLY, onClick: () -> Unit) {
    ListRow(title, subtitle, position, enabled, leading = if (badge) ({ Avatar(title.take(1).uppercase(), accent = true) }) else null,
        trailing = { Box(Modifier.size(20.dp)) { AppIcon(R.drawable.aw_chevron) } }, grouped = true, onClick = onClick)
}
