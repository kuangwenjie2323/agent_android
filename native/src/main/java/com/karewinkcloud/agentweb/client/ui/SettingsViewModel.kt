package com.karewinkcloud.agentweb.client.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.karewinkcloud.agentweb.client.BuildConfig
import com.karewinkcloud.agentweb.client.core.ApiException
import com.karewinkcloud.agentweb.client.core.DiagnosticLog
import com.karewinkcloud.agentweb.client.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.io.IOException

enum class SignInPhase { IDLE, PASSKEY, BROWSER, EXCHANGING, SIGNING_OUT }
data class ServerConnection(val origin: String, val revision: Long, val signInRequired: Boolean)

enum class SupportProblem { NOT_AVAILABLE, SIGN_IN, NETWORK, SERVER, MISMATCH, NO_LOG }

/** Settings > Diagnostics & updates: the log upload and the update check, download and install. */
data class SupportState(
    val uploading: Boolean = false, val sentParts: Int = 0, val totalParts: Int = 0,
    val uploadId: String? = null, val uploadProblem: SupportProblem? = null,
    val checking: Boolean = false, val checked: Boolean = false, val update: AppUpdate? = null,
    val updateProblem: SupportProblem? = null,
    val downloading: Boolean = false, val downloadedBytes: Long = 0, val downloadBytes: Long = 0,
    /** A verified download waiting for the system installer. */
    val ready: File? = null,
    /** Hand [ready] to the installer once, right after the download; later the Install button does it. */
    val installNow: Boolean = false,
)

class SettingsViewModel(
    val store: SettingsStorage,
    private val api: NativeAuthApi = HttpNativeAuthApi(),
    private val signInFlow: NativeSignInFlow = NativeSignInFlow(),
    private val deviceName: String = "Android phone",
    private val debug: Boolean = BuildConfig.DEBUG,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val support: (String) -> AppSupport = { origin -> HttpAppSupport(HttpAgentRepository(origin, store)) },
    private val logs: () -> DiagnosticLog? = Diagnostics::flushed,
    private val installedVersion: Int = BuildConfig.VERSION_CODE,
) : ViewModel() {
    private val mutable = MutableStateFlow(store.load())
    val state = mutable.asStateFlow()
    val authentication = store.credentials.status
    private val errorState = MutableStateFlow<String?>(null)
    val error = errorState.asStateFlow()
    private val savingState = MutableStateFlow(false)
    val saving = savingState.asStateFlow()
    private val phaseState = MutableStateFlow(SignInPhase.IDLE)
    val phase = phaseState.asStateFlow()
    private val showSettingsState = MutableStateFlow(false)
    val showSettings = showSettingsState.asStateFlow()
    private val passkeyFailureState = MutableStateFlow<PasskeyFailure?>(null)
    val passkeyFailure = passkeyFailureState.asStateFlow()
    val preferredSignIn get() = signInMethod(passkeyFailureState.value)
    private var exchangeJob: Job? = null
    private val operationLock = Any()
    @Volatile private var operation = 0L

    fun localTesting(origin: String = state.value.origin): Boolean = origin.toHttpUrlOrNull()?.let {
        debug && it.scheme == "http" && it.host in setOf("localhost", "127.0.0.1")
    } == true
    fun connection() = ServerConnection(state.value.origin, authentication.value.revision,
        !localTesting() && authentication.value.origin != state.value.origin)
    fun settingsSeen() { showSettingsState.value = false }

    init { viewModelScope.launch { authentication.collect { mutable.value = store.load() } } }

    fun save(origin: String, theme: String, onSaved: () -> Unit = {}) {
        if (savingState.value || phaseState.value != SignInPhase.IDLE) return
        savingState.value = true
        errorState.value = null
        viewModelScope.launch {
            try {
                val changedServer = state.value.origin != origin.trimEnd('/')
                withContext(io) { store.save(origin, theme) }
                mutable.value = store.load()
                if (changedServer) passkeyFailureState.value = null
                onSaved()
            } catch (e: IllegalArgumentException) { errorState.value = e.message }
            catch (_: Exception) { errorState.value = "Settings could not be saved. Try again." }
            finally { savingState.value = false }
        }
    }

    fun signIn(origin: String, theme: String, openBrowser: (String) -> Unit) {
        if (savingState.value || phaseState.value != SignInPhase.IDLE) return
        savingState.value = true
        errorState.value = null
        val owner = ++operation
        viewModelScope.launch {
            try {
                withContext(io) { store.save(origin, theme) }
                mutable.value = store.load()
                if (owner != operation) return@launch
                val url = signInFlow.begin(mutable.value.origin, deviceName)
                phaseState.value = SignInPhase.BROWSER
                openBrowser(url)
            } catch (e: IllegalArgumentException) { errorState.value = e.message; cancelPending() }
            catch (_: Exception) {
                errorState.value = "Could not open a browser. Install or enable a browser and try again."
                cancelPending()
            } finally { savingState.value = false }
        }
    }

    fun signInWithPasskey(provider: PasskeyProvider) {
        if (savingState.value || phaseState.value != SignInPhase.IDLE) return
        val origin = state.value.origin
        val owner = ++operation
        phaseState.value = SignInPhase.PASSKEY
        passkeyFailureState.value = null
        errorState.value = null
        exchangeJob = viewModelScope.launch {
            try {
                val challenge = withContext(io) { api.passkeyOptions(origin, deviceName) }
                // Must run on Main with the current Activity; never invoke a browser automatically.
                val response = passkeyCredential(provider.authenticate(challenge.options.toString()))
                if (owner != operation || state.value.origin != origin) return@launch
                phaseState.value = SignInPhase.EXCHANGING
                val credential = withContext(io) { api.verifyPasskey(origin, deviceName, challenge, response) }
                if (owner != operation || state.value.origin != origin) return@launch
                withContext(io) { synchronized(operationLock) { if (owner == operation) store.credentials.install(credential) } }
                mutable.value = store.load()
            } catch (e: CancellationException) { throw e }
            catch (e: PasskeyException) {
                if (owner == operation) { passkeyFailureState.value = e.failure; errorState.value = passkeyMessage(e.failure) }
            } catch (e: SignInException) {
                if (owner == operation) { passkeyFailureState.value = PasskeyFailure.PROVIDER; errorState.value = e.message }
            } catch (_: Exception) {
                if (owner == operation) { passkeyFailureState.value = PasskeyFailure.PROVIDER; errorState.value = passkeyMessage(PasskeyFailure.PROVIDER) }
            } finally { if (owner == operation) phaseState.value = SignInPhase.IDLE }
        }
    }

    fun language(value: String) {
        if (savingState.value || phaseState.value != SignInPhase.IDLE) return
        viewModelScope.launch {
            try { withContext(io) { store.saveLanguage(value) }; mutable.value = store.load() }
            catch (_: Exception) { errorState.value = "Settings could not be saved. Try again." }
        }
    }

    fun browserUnavailable() {
        errorState.value = "Could not open a browser. Install or enable a browser and try again."
        cancelPending()
    }

    fun cancelPending() {
        synchronized(operationLock) { operation++ }
        if (phaseState.value == SignInPhase.PASSKEY) {
            passkeyFailureState.value = PasskeyFailure.CANCELLED
            errorState.value = passkeyMessage(PasskeyFailure.CANCELLED)
        }
        signInFlow.cancel()
        exchangeJob?.cancel()
        phaseState.value = SignInPhase.IDLE
    }

    /** Callback arrives via Activity only; never retained in UI state or saved-instance state. */
    fun callback(value: String) {
        showSettingsState.value = true
        if (phaseState.value == SignInPhase.PASSKEY || phaseState.value == SignInPhase.EXCHANGING || phaseState.value == SignInPhase.SIGNING_OUT) return
        val grant = try { signInFlow.consume(value, mutable.value.origin) }
        catch (e: SignInException) {
            phaseState.value = SignInPhase.IDLE
            errorState.value = e.message
            return
        }
        val owner = ++operation
        phaseState.value = SignInPhase.EXCHANGING
        errorState.value = null
        exchangeJob = viewModelScope.launch {
            try {
                val credential = withContext(io) { api.exchange(grant) }
                // On navigation/cancellation, never install a result from an older operation.
                if (owner != operation || mutable.value.origin != grant.origin) return@launch
                withContext(io) { synchronized(operationLock) { if (owner == operation) store.credentials.install(credential) } }
                mutable.value = store.load()
            } catch (e: CancellationException) { throw e }
            catch (e: SignInException) { if (owner == operation) errorState.value = e.message }
            catch (_: Exception) { if (owner == operation) errorState.value = "Could not complete sign-in. Start again." }
            finally { if (owner == operation) phaseState.value = SignInPhase.IDLE }
        }
    }

    fun signOut() {
        if (phaseState.value == SignInPhase.SIGNING_OUT || savingState.value) return
        cancelPending()
        val credential = store.credentials.current()
        phaseState.value = SignInPhase.SIGNING_OUT
        errorState.value = null
        // Erase durably before waiting for the network, including if the Activity's
        // ViewModel is cleared before its coroutine starts. Revocation uses only
        // this captured, in-memory grant, never a credential read back from disk.
        store.credentials.clear("Signed out on this phone. Remote revocation is pending.")
        mutable.value = store.load()
        viewModelScope.launch {
            var revoked = credential == null
            try { if (credential != null) revoked = withContext(io) { withTimeout(10_000) { api.revoke(credential) } } }
            catch (_: Exception) { /* Offline/unsupported revocation must still erase the local grant. */ }
            finally {
                withContext(NonCancellable + io) {
                    store.credentials.clear(if (revoked) "Signed out." else
                        "Signed out on this phone. Remote revocation could not be confirmed; use Account security to revoke this device.")
                }
                mutable.value = store.load()
                phaseState.value = SignInPhase.IDLE
            }
        }
    }

    fun accountUrl() = mutable.value.origin + "/_access/start?manage=1"
    override fun onCleared() { signInFlow.cancel() }

    private val supportState = MutableStateFlow(SupportState())
    val supportStatus = supportState.asStateFlow()

    /** Sends the whole diagnostic log, with the user's [note], to the server this app uses. */
    fun uploadLogs(note: String) {
        if (supportState.value.uploading) return
        val origin = state.value.origin
        supportState.update { it.copy(uploading = true, sentParts = 0, totalParts = 0, uploadId = null, uploadProblem = null) }
        viewModelScope.launch {
            try {
                val log = withContext(io) { logs() }
                val parts = withContext(io) { log?.gzipParts(HttpAppSupport.PART_BYTES, HttpAppSupport.MAX_PARTS).orEmpty() }
                if (parts.isEmpty()) { supportState.update { it.copy(uploading = false, uploadProblem = SupportProblem.NO_LOG) }; return@launch }
                val meta = buildJsonObject {
                    put("note", note.trim().take(HttpAppSupport.NOTE_CHARS))
                    put("app", BuildConfig.VERSION_NAME); put("versionCode", installedVersion); put("build", BuildConfig.BUILD_LABEL)
                    put("device", deviceName); put("timezone", java.util.TimeZone.getDefault().id)
                    put("clientTime", System.currentTimeMillis()); put("logBytes", withContext(io) { log?.sizeBytes() ?: 0 })
                }
                supportState.update { it.copy(totalParts = parts.size) }
                val id = withContext(io) {
                    support(origin).uploadLogs(parts, meta) { sent, total -> supportState.update { it.copy(sentParts = sent, totalParts = total) } }
                }
                Diagnostics.event("support", "log uploaded", "id" to id, "parts" to parts.size)
                supportState.update { it.copy(uploading = false, uploadId = id) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Diagnostics.warn("support", "log upload failed", "error" to Diagnostics.describe(e))
                supportState.update { it.copy(uploading = false, uploadProblem = supportProblem(e)) }
            }
        }
    }

    /** Asks the server for a newer build. A [quiet] check (opening Settings) is skipped once one succeeded and shows no error. */
    fun checkUpdate(quiet: Boolean = false) {
        val current = supportState.value
        if (current.checking || current.downloading || (quiet && current.checked)) return
        supportState.update { it.copy(checking = true, updateProblem = null) }
        viewModelScope.launch {
            try {
                val update = withContext(io) { support(state.value.origin).latestUpdate() }?.takeIf { it.versionCode > installedVersion }
                supportState.update { it.copy(checking = false, checked = true, update = update, ready = it.ready?.takeIf { update != null }) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                // A failed quiet check leaves no verdict ("latest") behind; an explicit one says why it failed.
                supportState.update { it.copy(checking = false, checked = !quiet, updateProblem = if (quiet) null else supportProblem(e)) }
            }
        }
    }

    /** Downloads the offered build into [dir] (replacing older downloads) and checks it before offering install. */
    fun downloadUpdate(dir: File) {
        val update = supportState.value.update ?: return
        if (supportState.value.downloading) return
        supportState.update { it.copy(downloading = true, downloadedBytes = 0, downloadBytes = update.size, updateProblem = null, ready = null) }
        viewModelScope.launch {
            val file = File(dir, "agentweb-${update.versionCode}.apk")
            try {
                var shown = 0L
                withContext(io) {
                    dir.listFiles()?.forEach { it.delete() }
                    support(state.value.origin).downloadUpdate(update, file) { done, total ->
                        if (done - shown >= 256 * 1024 || done == total) {
                            shown = done
                            supportState.update { it.copy(downloadedBytes = done, downloadBytes = if (total > 0) total else it.downloadBytes) }
                        }
                    }
                }
                Diagnostics.event("support", "update downloaded", "versionCode" to update.versionCode)
                supportState.update { it.copy(downloading = false, ready = file, installNow = true) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Diagnostics.warn("support", "update download failed", "error" to Diagnostics.describe(e))
                supportState.update { it.copy(downloading = false, updateProblem = supportProblem(e)) }
            }
        }
    }

    fun installStarted() = supportState.update { it.copy(installNow = false) }

    private fun supportProblem(e: Exception) = when {
        e is UpdateMismatchException -> SupportProblem.MISMATCH
        e is ApiException && e.status == 401 -> SupportProblem.SIGN_IN
        e is ApiException && e.status == 404 -> SupportProblem.NOT_AVAILABLE
        e is IOException -> SupportProblem.NETWORK
        else -> SupportProblem.SERVER
    }
}
