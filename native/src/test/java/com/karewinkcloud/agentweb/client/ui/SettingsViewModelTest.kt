@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.karewinkcloud.agentweb.client.ui

import androidx.lifecycle.ViewModelStore
import com.karewinkcloud.agentweb.client.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.*
import org.junit.Assert.*

class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var lifecycle: ViewModelStore
    private lateinit var storage: FakeSettingsStorage
    private lateinit var vm: SettingsViewModel
    private var exchangeCalls = 0
    private var revokeFails = false
    private var revokeConfirmed = true
    private var pending: CompletableDeferred<DeviceCredential>? = null
    private var lastGrant: CodeGrant? = null
    private var browser: String? = null
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        storage = FakeSettingsStorage()
        val api = object : NativeAuthApi {
            override suspend fun exchange(grant: CodeGrant): DeviceCredential {
                exchangeCalls++; lastGrant = grant
                return pending?.await() ?: fakeCredential(grant.origin)
            }
            override suspend fun revoke(credential: DeviceCredential): Boolean {
                if (revokeFails) error("synthetic network failure")
                return revokeConfirmed
            }
        }
        vm = SettingsViewModel(storage, api, deviceName = "Phone", debug = true, io = dispatcher)
        lifecycle = ViewModelStore().apply { put("settings", vm) }
    }
    @After fun cleanup() { lifecycle.clear(); Dispatchers.resetMain() }
    private fun begin() { vm.signIn(storage.load().origin, "system") { browser = it } }
    private fun callback() = "$NATIVE_REDIRECT_URI?state=${browser!!.toHttpUrl().queryParameter("state")}&code=${Pkce.randomValue()}"

    @Test fun callbackStoresCredentialAndUnlocksConnectionWithoutExposingSecretsInState() = runTest(dispatcher) {
        begin(); runCurrent()
        val callback = callback()
        vm.callback(callback); runCurrent()
        assertEquals(1, exchangeCalls); assertEquals(SignInPhase.IDLE, vm.phase.value)
        assertTrue(vm.authentication.value.signedIn); assertFalse(vm.connection().signInRequired)
        assertEquals("Test account", vm.authentication.value.accountName)
        assertFalse(vm.state.value.toString().contains(storage.credentials.current()!!.token))
        assertFalse(vm.state.value.toString().contains(lastGrant!!.verifier))
        vm.callback(callback); runCurrent()
        assertEquals(1, exchangeCalls); assertNotNull(vm.error.value)
    }
    @Test fun callbackWithoutPendingOrWrongStateNeverExchanges() = runTest(dispatcher) {
        vm.callback("$NATIVE_REDIRECT_URI?state=${Pkce.randomValue()}&code=${Pkce.randomValue()}")
        begin(); runCurrent()
        vm.callback("$NATIVE_REDIRECT_URI?state=${Pkce.randomValue()}&code=${Pkce.randomValue()}"); runCurrent()
        assertEquals(0, exchangeCalls); assertFalse(vm.authentication.value.signedIn)
    }
    @Test fun duplicateCallbackDuringExchangeDoesNotStartAnotherRequest() = runTest(dispatcher) {
        pending = CompletableDeferred()
        begin(); runCurrent()
        val callback = callback()
        vm.callback(callback); runCurrent(); vm.callback(callback); runCurrent()
        assertEquals(1, exchangeCalls)
        pending!!.complete(fakeCredential()); runCurrent()
        assertTrue(vm.authentication.value.signedIn)
    }
    @Test fun cancelRejectsLaterCallbackAndAllowsNewSignIn() = runTest(dispatcher) {
        begin(); runCurrent(); val old = callback()
        vm.cancelPending(); vm.callback(old); runCurrent()
        assertEquals(0, exchangeCalls)
        begin(); runCurrent(); vm.callback(callback()); runCurrent()
        assertEquals(1, exchangeCalls)
    }
    @Test fun lateExchangeAfterCancellationCannotRestoreSignIn() = runTest(dispatcher) {
        pending = CompletableDeferred()
        begin(); runCurrent(); vm.callback(callback()); runCurrent()
        vm.cancelPending(); pending!!.complete(fakeCredential()); runCurrent()
        assertNull(storage.credentials.current())
    }
    @Test fun signOutAlwaysWipesEvenOfflineOrUnsupported() = runTest(dispatcher) {
        for (failure in listOf(false, true)) {
            storage.credentials.install(fakeCredential()); runCurrent()
            revokeFails = failure; revokeConfirmed = false
            vm.signOut(); runCurrent()
            assertFalse(vm.authentication.value.signedIn); assertNull(storage.persistence.value)
            assertTrue(vm.authentication.value.message!!.contains("Remote revocation could not be confirmed"))
        }
    }
    @Test fun signOutErasesBeforeLifecycleCanCancelItsNetworkJob() = runTest(dispatcher) {
        storage.credentials.install(fakeCredential()); runCurrent()
        vm.signOut()
        lifecycle.clear()
        assertNull(storage.credentials.current()); assertNull(storage.persistence.value)
        assertNull(storage.cipher.key)
    }
    @Test fun serverChangeErasesCredentialsButThemeChangeKeepsThem() = runTest(dispatcher) {
        val value = fakeCredential()
        storage.credentials.install(value); runCurrent()
        vm.save(value.origin, "dark"); runCurrent()
        assertEquals(value.token, storage.tokenFor(value.origin))
        vm.save("https://other.test", "dark"); runCurrent()
        assertNull(storage.credentials.current()); assertTrue(vm.connection().signInRequired)
    }
    @Test fun debugLocalhostWorksWithoutAuthentication() = runTest(dispatcher) {
        vm.save("http://127.0.0.1:18892", "system"); runCurrent()
        assertFalse(vm.connection().signInRequired)
        assertFalse(vm.localTesting("http://192.168.1.2"))
        assertFalse(vm.localTesting("https://agent.karewinkcloud.com"))
    }
    @Test fun invalidOriginDoesNotOpenBrowser() = runTest(dispatcher) {
        vm.signIn("http://evil.test", "system") { browser = it }; runCurrent()
        assertNull(browser); assertNotNull(vm.error.value)
    }
    @Test fun unavailableBrowserKeepsSettingsErrorAndRejectsPendingSignInCallback() = runTest(dispatcher) {
        begin(); runCurrent(); val pendingCallback = callback()
        assertEquals(SignInPhase.BROWSER, vm.phase.value)
        vm.browserUnavailable()
        assertEquals("Could not open a browser. Install or enable a browser and try again.", vm.error.value)
        assertEquals(SignInPhase.IDLE, vm.phase.value)
        vm.callback(pendingCallback); runCurrent()
        assertEquals(0, exchangeCalls)
        assertFalse(vm.authentication.value.signedIn)
    }
}
