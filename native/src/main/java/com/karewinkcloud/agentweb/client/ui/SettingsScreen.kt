package com.karewinkcloud.agentweb.client.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import android.content.Context
import android.content.Intent
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.karewinkcloud.agentweb.client.BuildConfig
import java.io.File
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
    val support by vm.supportStatus.collectAsStateWithLifecycle()
    var note by rememberSaveable { mutableStateOf("") }
    var needsInstallPermission by remember { mutableStateOf(false) }
    val context = LocalActivity.current ?: LocalContext.current
    // Signed in, or a debug build talking to a local test server without sign-in.
    val reachable = auth.signedIn || vm.localTesting()
    LaunchedEffect(reachable) { if (reachable) vm.checkUpdate(quiet = true) }
    // A verified download goes straight to the system installer (or to its permission page first), once.
    LaunchedEffect(support.installNow) {
        val ready = support.ready
        if (support.installNow && ready != null) { vm.installStarted(); needsInstallPermission = !installApk(context, ready) }
    }
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
        if (reachable) SettingsGroup(tr(S.support_group)) {
            SettingsRow(tr(S.upload_log), support.uploadId?.let { tr(S.upload_log_last, it) } ?: tr(S.upload_log_hint),
                position = RowPosition.FIRST) { editor = "logs" }
            SettingsRow(tr(S.check_update), when {
                support.checking -> tr(S.update_checking)
                support.update != null -> tr(S.update_available, support.update!!.builtAt)
                support.checked && support.updateProblem == null -> tr(S.update_latest)
                else -> tr(S.update_current, BuildConfig.BUILD_LABEL)
            }, badge = false, position = RowPosition.LAST) { editor = "update"; if (support.update == null) vm.checkUpdate() }
        }
        Text("AgentWeb App ${com.karewinkcloud.agentweb.client.BuildConfig.VERSION_NAME} · ${com.karewinkcloud.agentweb.client.BuildConfig.BUILD_LABEL}", Modifier.align(Alignment.CenterHorizontally),
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
        "logs" -> {
            // Each opening starts fresh: the result shown is from an upload started in this dialog.
            var sent by remember { mutableStateOf(false) }
            val finished = sent && support.uploadId != null && !support.uploading
            AlertDialog(onDismissRequest = { if (!support.uploading) editor = null }, title = { Text(tr(S.upload_log)) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tr(S.upload_log_explain), style = MaterialTheme.typography.bodyMedium)
                TextField(note, { note = it.take(1000) }, enabled = !support.uploading, minLines = 2, maxLines = 5,
                    modifier = Modifier.fillMaxWidth(), label = { Text(tr(S.upload_log_note)) })
                when {
                    support.uploading -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LinearProgressIndicator({ if (support.totalParts == 0) 0f else support.sentParts / support.totalParts.toFloat() },
                            Modifier.fillMaxWidth())
                        Text(tr(S.upload_log_sending, support.sentParts, support.totalParts), style = MaterialTheme.typography.bodySmall)
                    }
                    finished -> SelectionContainer { Text(tr(S.upload_log_done, support.uploadId!!),
                        style = MaterialTheme.typography.titleSmall) }
                    sent && support.uploadProblem != null -> Text(supportMessage(support.uploadProblem!!), color = MaterialTheme.colorScheme.error)
                }
            }
        }, confirmButton = {
            if (finished) TextButton({ editor = null; note = "" }) { Text(tr(S.done)) }
            else Button({ sent = true; vm.uploadLogs(note) }, enabled = !support.uploading) { Text(tr(S.upload_log_send)) }
        }, dismissButton = {
            if (!finished) TextButton({ editor = null }, enabled = !support.uploading) { Text(tr(S.close)) }
        })
        }
        "update" -> AlertDialog(onDismissRequest = { editor = null }, title = { Text(tr(S.check_update)) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tr(S.update_current, "${BuildConfig.VERSION_NAME} · ${BuildConfig.BUILD_LABEL}"), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                val update = support.update
                when {
                    support.checking -> Text(tr(S.update_checking))
                    update != null -> {
                        Text(tr(S.update_available_detail, update.versionName, update.builtAt, "%.1f".format(update.size / 1_048_576.0)))
                        if (update.notes.isNotBlank()) Text(update.notes, style = MaterialTheme.typography.bodyMedium)
                    }
                    support.checked && support.updateProblem == null -> Text(tr(S.update_latest))
                }
                if (support.downloading) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LinearProgressIndicator({ if (support.downloadBytes <= 0) 0f else (support.downloadedBytes.toFloat() / support.downloadBytes).coerceIn(0f, 1f) },
                        Modifier.fillMaxWidth())
                    Text(tr(S.update_downloading, "%.1f".format(support.downloadedBytes / 1_048_576.0), "%.1f".format(support.downloadBytes / 1_048_576.0)),
                        style = MaterialTheme.typography.bodySmall)
                }
                if (needsInstallPermission && support.ready != null) Text(tr(S.update_permission), style = MaterialTheme.typography.titleSmall)
                support.updateProblem?.let { Text(supportMessage(it), color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = {
            val ready = support.ready
            when {
                ready != null -> Button({ needsInstallPermission = !installApk(context, ready) }) { Text(tr(S.update_install)) }
                support.update != null -> Button({ vm.downloadUpdate(File(context.cacheDir, "updates")) }, enabled = !support.downloading) {
                    Text(tr(S.update_download)) }
                else -> TextButton({ vm.checkUpdate() }, enabled = !support.checking) { Text(tr(S.update_check_again)) }
            }
        }, dismissButton = { TextButton({ editor = null }) { Text(tr(S.close)) } })
        "account" -> AlertDialog(onDismissRequest = { editor = null }, title = { Text(auth.accountName ?: tr(S.signed_out_row)) },
            text = { Text(if (auth.signedIn) tr(S.device, auth.deviceName.orEmpty()) else tr(S.sign_in_explanation)) },
            confirmButton = {
                if (auth.signedIn) TextButton({ vm.signOut(); editor = null }, enabled = !busy) { Text(tr(S.sign_out)) }
                else Button({ vm.signInWithPasskey(passkey); editor = null }, enabled = !busy) { Text(tr(S.use_passkey)) }
            }, dismissButton = { TextButton({ editor = null }) { Text(tr(S.close)) } })
    }
}

@Composable
private fun supportMessage(problem: SupportProblem) = tr(when (problem) {
    SupportProblem.NOT_AVAILABLE -> S.support_not_available
    SupportProblem.SIGN_IN -> S.support_sign_in
    SupportProblem.NETWORK -> S.support_network
    SupportProblem.SERVER -> S.support_server
    SupportProblem.MISMATCH -> S.support_mismatch
    SupportProblem.NO_LOG -> S.support_no_log
})

/**
 * Hands a downloaded build to the system installer. Returns false when Android first needs the user to allow
 * this app to install apps; its settings page is opened and the user taps Install again afterwards.
 */
private fun installApk(context: Context, file: File): Boolean {
    if (!context.packageManager.canRequestPackageInstalls()) {
        runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            "package:${context.packageName}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        return false
    }
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.comfy-output", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, HttpAppSupport.APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { Diagnostics.warn("support", "installer did not open", "error" to Diagnostics.describe(it)) }
    return true
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
