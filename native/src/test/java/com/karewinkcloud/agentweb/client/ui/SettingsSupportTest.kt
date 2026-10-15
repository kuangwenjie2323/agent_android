@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.karewinkcloud.agentweb.client.ui

import androidx.lifecycle.ViewModelStore
import com.karewinkcloud.agentweb.client.core.ApiException
import com.karewinkcloud.agentweb.client.core.ClientError
import com.karewinkcloud.agentweb.client.core.DiagnosticLog
import com.karewinkcloud.agentweb.client.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** Settings > Diagnostics & updates against a fake server. */
class SettingsSupportTest {
    @get:Rule val folder = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private lateinit var lifecycle: ViewModelStore
    private lateinit var log: DiagnosticLog
    private val fake = FakeSupport()

    private class FakeSupport : AppSupport {
        var uploaded: Pair<List<ByteArray>, JsonObject>? = null
        var uploadFailure: Exception? = null
        var published: AppUpdate? = null
        var checkFailure: Exception? = null
        var downloadFailure: Exception? = null
        var checks = 0
        var origins = mutableListOf<String>()
        override suspend fun uploadLogs(parts: List<ByteArray>, meta: JsonObject, onProgress: (Int, Int) -> Unit): String {
            uploadFailure?.let { throw it }
            parts.indices.forEach { onProgress(it + 1, parts.size) }
            uploaded = parts to meta
            return "20261015-143012-ab12cd34"
        }
        override suspend fun latestUpdate(): AppUpdate? { checks++; checkFailure?.let { throw it }; return published }
        override suspend fun downloadUpdate(update: AppUpdate, destination: File, onProgress: (Long, Long) -> Unit) {
            downloadFailure?.let { throw it }
            destination.parentFile?.mkdirs(); destination.writeBytes(byteArrayOf(1, 2, 3)); onProgress(3, 3)
        }
    }

    private fun vm(installed: Int = 100): SettingsViewModel {
        val api = object : NativeAuthApi {
            override suspend fun exchange(grant: CodeGrant) = fakeCredential(grant.origin)
            override suspend fun revoke(credential: DeviceCredential) = true
        }
        return SettingsViewModel(FakeSettingsStorage(), api, deviceName = "CPH2723", debug = true, io = dispatcher,
            support = { origin -> fake.origins += origin; fake }, logs = { log }, installedVersion = installed)
            .also { lifecycle.put("settings", it) }
    }

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        lifecycle = ViewModelStore()
        log = DiagnosticLog(folder.root.resolve("log"))
    }
    @After fun cleanup() { lifecycle.clear(); Dispatchers.resetMain() }

    @Test fun uploadSendsTheLogWithTheNoteAndShowsTheNumber() = runTest(dispatcher) {
        log.write('I', "app", "start")
        val vm = vm()
        vm.uploadLogs("  list was slow  "); runCurrent()
        val state = vm.supportStatus.value
        assertFalse(state.uploading)
        assertEquals("20261015-143012-ab12cd34", state.uploadId)
        assertEquals(1 to 1, state.sentParts to state.totalParts)
        val (parts, meta) = fake.uploaded!!
        assertEquals(1, parts.size)
        assertEquals("list was slow", meta["note"]!!.jsonPrimitive.content)
        assertEquals("CPH2723", meta["device"]!!.jsonPrimitive.content)
        assertEquals(100, meta["versionCode"]!!.jsonPrimitive.int)
        assertEquals(listOf("https://agent.karewinkcloud.com"), fake.origins)
    }

    @Test fun anEmptyLogIsNotSent() = runTest(dispatcher) {
        val vm = vm()
        vm.uploadLogs(""); runCurrent()
        assertEquals(SupportProblem.NO_LOG, vm.supportStatus.value.uploadProblem)
        assertNull(fake.uploaded)
    }

    @Test fun uploadProblemsAreNamed() = runTest(dispatcher) {
        log.write('I', "app", "start")
        val vm = vm()
        fake.uploadFailure = ApiException(404, ClientError("not found"))
        vm.uploadLogs(""); runCurrent()
        assertEquals(SupportProblem.NOT_AVAILABLE, vm.supportStatus.value.uploadProblem)
        fake.uploadFailure = IOException("offline")
        vm.uploadLogs(""); runCurrent()
        assertEquals(SupportProblem.NETWORK, vm.supportStatus.value.uploadProblem)
        fake.uploadFailure = null
        vm.uploadLogs(""); runCurrent()
        assertNull(vm.supportStatus.value.uploadProblem)
    }

    @Test fun onlyANewerBuildIsOffered() = runTest(dispatcher) {
        val vm = vm(installed = 100)
        fake.published = AppUpdate(100, "0.8.0", 3, "x", "")
        vm.checkUpdate(); runCurrent()
        assertNull(vm.supportStatus.value.update)
        assertTrue(vm.supportStatus.value.checked)
        fake.published = AppUpdate(101, "0.8.0", 3, "x", "notes")
        vm.checkUpdate(); runCurrent()
        assertEquals(101, vm.supportStatus.value.update?.versionCode)
    }

    @Test fun aQuietCheckHidesErrorsAndLeavesNoVerdict() = runTest(dispatcher) {
        val vm = vm()
        fake.checkFailure = ApiException(404, ClientError("not found"))
        vm.checkUpdate(quiet = true); runCurrent()
        assertNull(vm.supportStatus.value.updateProblem)
        assertFalse(vm.supportStatus.value.checked)  // the row must not claim "latest"
        vm.checkUpdate(); runCurrent()
        assertEquals(SupportProblem.NOT_AVAILABLE, vm.supportStatus.value.updateProblem)
        assertTrue(vm.supportStatus.value.checked)
    }

    @Test fun aQuietCheckIsSkippedAfterOneSucceeded() = runTest(dispatcher) {
        val vm = vm()
        vm.checkUpdate(quiet = true); runCurrent()
        vm.checkUpdate(quiet = true); runCurrent()
        assertEquals(1, fake.checks)
        vm.checkUpdate(); runCurrent()
        assertEquals(2, fake.checks)
    }

    @Test fun aVerifiedDownloadIsReadyToInstall() = runTest(dispatcher) {
        val vm = vm()
        fake.published = AppUpdate(200, "0.8.0", 3, "x", "")
        vm.checkUpdate(); runCurrent()
        val dir = folder.root.resolve("updates").apply { mkdirs(); resolve("old.apk").writeText("old") }
        vm.downloadUpdate(dir); runCurrent()
        val state = vm.supportStatus.value
        assertFalse(state.downloading)
        assertEquals(dir.resolve("agentweb-200.apk"), state.ready)
        assertTrue(state.installNow)
        assertEquals(listOf("agentweb-200.apk"), dir.list()!!.toList())
        vm.installStarted()
        assertFalse(vm.supportStatus.value.installNow)
        assertEquals(dir.resolve("agentweb-200.apk"), vm.supportStatus.value.ready)
    }

    @Test fun aMismatchedDownloadIsNotOffered() = runTest(dispatcher) {
        val vm = vm()
        fake.published = AppUpdate(200, "0.8.0", 3, "x", "")
        vm.checkUpdate(); runCurrent()
        fake.downloadFailure = UpdateMismatchException()
        vm.downloadUpdate(folder.root.resolve("updates")); runCurrent()
        assertEquals(SupportProblem.MISMATCH, vm.supportStatus.value.updateProblem)
        assertNull(vm.supportStatus.value.ready)
    }
}
