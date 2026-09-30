@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.karewinkcloud.agentweb.client.ui

import androidx.lifecycle.ViewModelStore
import com.karewinkcloud.agentweb.client.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.JsonObject
import org.junit.*
import org.junit.Assert.*

class PasskeyViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var lifetime: ViewModelStore
    private lateinit var storage: FakeSettingsStorage
    private lateinit var vm: SettingsViewModel
    private var optionsCalls = 0
    private var verifyCalls = 0
    private var providerCalls = 0
    private var captured: String? = null
    private var pending: CompletableDeferred<DeviceCredential>? = null
    private var cancelledFailure: PasskeyFailure? = null
    private var responseJson = authenticationJson
    private val provider = PasskeyProvider { json ->
        providerCalls++; captured = json
        cancelledFailure?.let { throw PasskeyException(it) }
        responseJson
    }
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        storage = FakeSettingsStorage()
        val api = object : NativeAuthApi {
            override suspend fun exchange(grant: CodeGrant) = fakeCredential(grant.origin)
            override suspend fun revoke(credential: DeviceCredential) = true
            override suspend fun passkeyOptions(origin: String, deviceName: String): PasskeyChallenge { optionsCalls++; return challenge() }
            override suspend fun verifyPasskey(origin: String, deviceName: String, challenge: PasskeyChallenge, credential: JsonObject): DeviceCredential {
                verifyCalls++
                return pending?.let { withContext(NonCancellable) { it.await() } } ?: fakeCredential(origin)
            }
        }
        vm = SettingsViewModel(storage, api, io = dispatcher)
        lifetime = ViewModelStore().apply { put("vm", vm) }
    }
    @After fun cleanup() { lifetime.clear(); Dispatchers.resetMain() }

    @Test fun passkeySuccessUsesSameCredentialStoreAndIsSingleFlight() = runTest(dispatcher) {
        vm.signInWithPasskey(provider); vm.signInWithPasskey(provider); runCurrent()
        assertEquals(1, optionsCalls); assertEquals(1, providerCalls); assertEquals(1, verifyCalls)
        assertEquals(challenge().options.toString(), captured)
        assertTrue(vm.authentication.value.signedIn); assertFalse(vm.connection().signInRequired)
        assertEquals(SignInPhase.IDLE, vm.phase.value)
        assertFalse(vm.state.value.toString().contains("signature"))
        assertFalse(vm.state.value.toString().contains("synthetic-ceremony"))
    }
    @Test fun providerFailuresOfferFallbackWithoutVerifyingOrLooping() = runTest(dispatcher) {
        for (failure in PasskeyFailure.entries) {
            cancelledFailure = failure
            vm.signInWithPasskey(provider); runCurrent(); advanceTimeBy(10_000); runCurrent()
            assertEquals(SignInPhase.IDLE, vm.phase.value)
            assertEquals(failure, vm.passkeyFailure.value)
            assertEquals(SignInMethod.BROWSER, vm.preferredSignIn)
            assertEquals(passkeyMessage(failure), vm.error.value)
        }
        assertEquals(PasskeyFailure.entries.size, providerCalls); assertEquals(0, verifyCalls)
        var browser: String? = null
        vm.signIn(storage.load().origin, "system") { browser = it }; runCurrent()
        assertNotNull(browser); assertEquals(SignInPhase.BROWSER, vm.phase.value)
        assertEquals(PasskeyFailure.entries.size, providerCalls)
    }
    @Test fun malformedAuthenticatorResponseNeverReachesVerify() = runTest(dispatcher) {
        responseJson = "[]"
        vm.signInWithPasskey(provider); runCurrent()
        assertEquals(0, verifyCalls); assertEquals(PasskeyFailure.INVALID, vm.passkeyFailure.value)
    }
    @Test fun cancelledVerificationCannotInstallAfterServerSwitch() = runTest(dispatcher) {
        pending = CompletableDeferred()
        vm.signInWithPasskey(provider); runCurrent()
        assertEquals(SignInPhase.EXCHANGING, vm.phase.value)
        vm.cancelPending(); vm.save("https://second.test", "dark"); runCurrent()
        pending!!.complete(fakeCredential()); runCurrent()
        assertNull(storage.credentials.current()); assertEquals("https://second.test", vm.state.value.origin)
        assertEquals(SignInPhase.IDLE, vm.phase.value)
    }
    @Test fun cancelledSystemRequestIsNotReportedAsProviderFailure() = runTest(dispatcher) {
        vm.signInWithPasskey(PasskeyProvider { awaitCancellation() }); runCurrent()
        vm.cancelPending(); runCurrent()
        assertEquals(SignInPhase.IDLE, vm.phase.value); assertEquals(0, verifyCalls); assertEquals(PasskeyFailure.CANCELLED, vm.passkeyFailure.value)
    }
    @Test fun browserCallbackCannotInterruptAPasskeyCeremony() = runTest(dispatcher) {
        vm.signInWithPasskey(PasskeyProvider { awaitCancellation() }); runCurrent()
        vm.callback("$NATIVE_REDIRECT_URI?state=invalid&code=invalid"); runCurrent()
        assertEquals(SignInPhase.PASSKEY, vm.phase.value); assertNull(vm.error.value)
        vm.cancelPending()
    }
    @Test fun languagePersistsIndependentlyWithoutRemovingCredential() = runTest(dispatcher) {
        storage.credentials.install(fakeCredential()); runCurrent()
        vm.language("zh"); runCurrent()
        assertEquals("zh", vm.state.value.language); assertTrue(vm.authentication.value.signedIn)
        vm.language("invalid"); runCurrent()
        assertEquals("zh", vm.state.value.language); assertNotNull(vm.error.value)
    }
}
