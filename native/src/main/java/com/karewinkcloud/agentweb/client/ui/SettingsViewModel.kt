package com.karewinkcloud.agentweb.client.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.karewinkcloud.agentweb.client.BuildConfig
import com.karewinkcloud.agentweb.client.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class SignInPhase { IDLE, PASSKEY, BROWSER, EXCHANGING, SIGNING_OUT }
data class ServerConnection(val origin: String, val revision: Long, val signInRequired: Boolean)

class SettingsViewModel(
    val store: SettingsStorage,
    private val api: NativeAuthApi = HttpNativeAuthApi(),
    private val signInFlow: NativeSignInFlow = NativeSignInFlow(),
    private val deviceName: String = "Android phone",
    private val debug: Boolean = BuildConfig.DEBUG,
    private val io: CoroutineDispatcher = Dispatchers.IO,
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
                errorState.value = "A browser with Custom Tabs is required. Install or enable Chrome and try again."
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
        errorState.value = "A browser with Custom Tabs is required. Install or enable Chrome and try again."
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
}
