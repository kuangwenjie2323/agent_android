package com.karewinkcloud.agentweb.client.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.karewinkcloud.agentweb.client.core.*
import com.karewinkcloud.agentweb.client.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

// Unknown submission identity survives process death; drafts and downloaded output do not enter Bundles.
data class ComfyState(
    val workflows: List<ComfyWorkflow> = emptyList(), val workflowId: String? = null,
    val kind: String = "image", val channel: String = "cloud_gpu", val prompt: String = "",
    /** Bumped only when the prompt is replaced from outside the field (Edit prefill), never by typing. */
    val promptRevision: Int = 0,
    val values: Map<String, String> = emptyMap(), val suggestions: List<ComfySuggestion> = emptyList(),
    val resources: ComfyResources? = null, val resourcesLoading: Boolean = false, val resourcesError: Boolean = false,
    val jobs: List<ComfyJob> = emptyList(), val selected: ComfyJob? = null, val pending: String? = null,
    val initialized: Boolean = false, val loading: Boolean = false, val submitting: Boolean = false,
    val checking: Boolean = false, val polling: Boolean = false, val pollingPaused: Boolean = false,
    val error: String? = null, val invalidField: String? = null, val signInRequired: Boolean = false,
) {
    val workflow get() = workflows.find { it.id == workflowId }
    val choices get() = workflows.filter { it.kind == kind && it.channel == channel }
    val needsResources get() = workflow?.let { it.channel == "cloud_gpu" && (it.requiredModels.isNotEmpty() || it.inputs.any { field -> field.resource != null }) } == true
    val canSubmit get() = initialized && !signInRequired && !loading && !submitting && pending == null &&
        workflow != null && workflow?.availability(resources)?.kind == ComfyAvailabilityKind.AVAILABLE &&
        workflow?.inputs.orEmpty().filter { it.resource != null }.all { field ->
            val value = values[field.key] ?: field.default
            if (value.isBlank()) !field.required else resources?.ready == true &&
                resources.items.any { it.category == field.resource && it.name == value }
        } && (workflow?.promptKey == null || prompt.isNotBlank())
}

class ComfyViewModel(private var repository: ComfyRepository, private var pendingStore: ComfyPendingStore,
    private val mediaDirectory: File, initialSignInRequired: Boolean = false,
    private val io: CoroutineDispatcher = Dispatchers.IO, private val pollDelay: Long = 3000) : ViewModel() {
    private val mutable = MutableStateFlow(ComfyState(signInRequired = initialSignInRequired))
    val state = mutable.asStateFlow()
    internal var appliedConnection: ServerConnection? = null
    private var epoch = 0L
    private var foreground = false
    private var manualWorkflow = false
    private var manualChannel = false
    private var loadingJob: Job? = null
    private var recommendationJob: Job? = null
    private var resourceJob: Job? = null
    private var pollJob: Job? = null
    private var checkJob: Job? = null
    private var checkingGeneration = 0L
    private val observed = mutableMapOf<String, Long>()
    private var lastPolled: String? = null
    private val mediaLock = Mutex()
    private fun observe(job: ComfyJob) { observed.putIfAbsent(job.requestId, System.nanoTime()) }
    fun observedMillis(requestId: String): Long = ((System.nanoTime() - observed.getOrPut(requestId) { System.nanoTime() }) / 1_000_000).coerceAtLeast(0)
    private fun pollingIds(): List<String> = buildList {
        state.value.pending?.let { id ->
            val known = state.value.jobs.find { it.requestId == id } ?: state.value.selected?.takeIf { it.requestId == id }
            if (known?.needsPolling() == true) add(id)
        }
        state.value.jobs.filter { it.needsPolling() }.forEach { add(it.requestId) }
        state.value.selected?.takeIf { it.needsPolling() }?.let { add(it.requestId) }
    }.distinct()
    private fun nextPollingId(): String? {
        val ids = pollingIds()
        if (ids.isEmpty()) return null
        return ids[(ids.indexOf(lastPolled) + 1) % ids.size]
    }
    private val mediaSession = java.util.UUID.randomUUID().toString()

    init { observeAuth() }
    private fun observeAuth() {
        val owner = epoch
        viewModelScope.launch { repository.authenticationRequired.collect { required ->
            if (required && owner == epoch) {
                epoch++; viewModelScope.coroutineContext.cancelChildren()
                mutable.value = ComfyState(signInRequired = true, error = "auth")
            }
        } }
    }
    fun changeConnection(source: ComfyRepository, store: ComfyPendingStore, blocked: Boolean) {
        epoch++; viewModelScope.coroutineContext.cancelChildren()
        repository = source; pendingStore = store
        loadingJob = null; recommendationJob = null; resourceJob = null; pollJob = null; checkJob = null
        manualWorkflow = false; manualChannel = false; observed.clear(); lastPolled = null
        mutable.value = ComfyState(signInRequired = blocked)
        observeAuth()
        if (foreground) refresh()
    }
    fun setForeground(value: Boolean) {
        foreground = value
        if (!value) { stopPolling(user = false); recommendationJob?.cancel(); resourceJob?.cancel()
            mutable.update { it.copy(resourcesLoading = false) }; return }
        if (!state.value.initialized) refresh()
        else {
            val id = state.value.pending ?: nextPollingId()
            if (id != null && !state.value.pollingPaused && !state.value.submitting) check(id)
            ensureResources(); scheduleRecommendations()
        }
    }
    fun refresh() {
        if (state.value.signInRequired || loadingJob?.isActive == true || state.value.submitting) return
        val owner = epoch; val source = repository; val persistence = pendingStore
        mutable.update { it.copy(loading = true, error = null) }
        loadingJob = viewModelScope.launch {
            try {
                val pending = try { persistence.read() }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { throw ComfyFailure("storage") }
                if (owner != epoch) return@launch
                mutable.update { it.copy(pending = pending) }
                supervisorScope {
                    val history = async { runCatching { source.jobs() } }
                    val catalogResult = runCatching { source.workflows() }
                    currentCoroutineContext().ensureActive()
                    val catalog = catalogResult.getOrNull() ?: state.value.workflows
                    val jobs = history.await().getOrNull()
                    if (owner != epoch) return@supervisorScope
                    jobs?.forEach(::observe)
                    val old = state.value
                    val channel = if (!old.initialized && old.workflowId == null && !manualChannel && catalog.any {
                        it.kind == old.kind && it.channel == "kaggle_gpu" && it.available == true && it.unavailable == null
                    }) "kaggle_gpu" else old.channel
                    val selected = catalog.find { it.id == old.workflowId }
                        ?: catalog.firstOrNull { it.kind == old.kind && it.channel == channel && it.unavailable == null }
                        ?: catalog.firstOrNull { it.kind == old.kind && it.channel == channel }
                    mutable.update { it.copy(workflows = catalog, workflowId = selected?.id, channel = channel,
                        values = if (it.workflowId == selected?.id) it.values else selected?.defaults().orEmpty(),
                        jobs = jobs ?: it.jobs, initialized = true, loading = false,
                        error = catalogResult.exceptionOrNull()?.let { e -> (e as? ComfyFailure)?.code ?: "network" }
                            ?: if (jobs == null) "history_failed" else null) }
                }
                if (owner == epoch) {
                    ensureResources()
                    if (pending != null) {
                        mutable.update { it.copy(selected = it.jobs.find { j -> j.requestId == pending }
                            ?: ComfyJob(pending, "", "unknown")) }
                        if (foreground && !state.value.pollingPaused) check(pending)
                    } else if (foreground && !state.value.pollingPaused) nextPollingId()?.let { check(it) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (owner == epoch) mutable.update { it.copy(initialized = false, loading = false, error = failure(e)) } }
        }
    }
    fun prompt(value: String, composing: Boolean = false) {
        mutable.update { it.copy(prompt = value, error = null, invalidField = null, suggestions = emptyList()) }
        if (value.isBlank()) manualWorkflow = false
        recommendationJob?.cancel()
        if (!composing) scheduleRecommendations()
    }
    fun filter(kind: String = state.value.kind, channel: String = state.value.channel) {
        if (kind !in setOf("image", "video", "audio") || channel !in setOf("cloud_gpu", "partner_api", "kaggle_gpu")) return
        manualChannel = true
        val next = state.value.workflows.firstOrNull { it.kind == kind && it.channel == channel && it.unavailable == null }
            ?: state.value.workflows.firstOrNull { it.kind == kind && it.channel == channel }
        mutable.update { it.copy(kind = kind, channel = channel, workflowId = next?.id, values = next?.defaults().orEmpty(),
            suggestions = emptyList(), error = null, invalidField = null) }
        recommendationJob?.cancel(); manualWorkflow = false; ensureResources(); scheduleRecommendations()
    }
    fun choose(id: String) {
        val workflow = state.value.workflows.find { it.id == id } ?: return
        manualWorkflow = true; manualChannel = true; recommendationJob?.cancel()
        mutable.update { it.copy(workflowId = id, kind = workflow.kind, channel = workflow.channel, values = workflow.defaults(), suggestions = emptyList(),
            error = null, invalidField = null) }
        ensureResources()
    }
    fun parameter(key: String, value: String) { mutable.update { it.copy(values = it.values + (key to value), error = null, invalidField = null) } }
    fun size(value: ComfySize) { mutable.update { it.copy(values = it.values + value.parameters, error = null, invalidField = null) } }
    private fun scheduleRecommendations() {
        recommendationJob?.cancel()
        val current = state.value; val prompt = current.prompt.trim()
        if (!foreground || !current.initialized || current.signInRequired || manualWorkflow || prompt.codePointCount(0, prompt.length) !in 4..2000) return
        val owner = epoch; val source = repository
        recommendationJob = viewModelScope.launch {
            delay(700)
            try {
                val suggestions = source.recommend(prompt, current.channel)
                if (owner == epoch && !manualWorkflow && prompt == state.value.prompt.trim() && current.channel == state.value.channel) {
                    mutable.update { now -> now.copy(suggestions = suggestions.filter { suggestion -> now.workflows.any {
                        it.id == suggestion.workflowId && it.channel == now.channel && it.availability(now.resources).kind == ComfyAvailabilityKind.AVAILABLE
                    } }) }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Optional advice fails open. */ }
        }
    }
    fun ensureResources(force: Boolean = false) {
        if (!foreground || state.value.signInRequired || !state.value.workflows.any { workflow -> workflow.channel == "cloud_gpu" &&
            (workflow.requiredModels.isNotEmpty() || workflow.inputs.any { it.resource != null }) } || resourceJob?.isActive == true) return
        if (!force && state.value.resources?.let { it.ready && !it.refreshing } == true) return
        val owner = epoch; val source = repository
        mutable.update { it.copy(resourcesLoading = true, resourcesError = false) }
        resourceJob = viewModelScope.launch {
            try {
                repeat(6) { attempt ->
                    val result = source.resources(force && attempt == 0)
                    if (owner != epoch) return@launch
                    mutable.update { it.copy(resources = if (!result.ready && it.resources?.ready == true) it.resources else result) }
                    if (!result.refreshing) return@launch
                    delay(3000)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (owner == epoch) mutable.update { it.copy(resourcesError = true) } }
            finally { if (owner == epoch) mutable.update { it.copy(resourcesLoading = false) } }
        }
    }
    fun submit() {
        val current = state.value
        if (!current.canSubmit) return
        val workflow = current.workflow ?: return
        val values = current.values + (workflow.promptKey?.let { mapOf(it to current.prompt) } ?: emptyMap())
        val request = try { ComfySubmission(workflow.id, workflow.parameters(values, current.resources)) }
        catch (e: ComfyFailure) { mutable.update { it.copy(error = e.code, invalidField = e.field) }; return }
        dispatch(request)
    }
    fun edit(job: ComfyJob): Boolean {
        val workflow = state.value.workflows.find { it.id == job.workflowId }
        if (workflow == null) { mutable.update { it.copy(error = "invalid_workflow") }; return false }
        applyDraft(workflow.prefill(job)); return true
    }
    private fun applyDraft(draft: ComfyDraft) {
        manualWorkflow = true; manualChannel = true; recommendationJob?.cancel()
        mutable.update { it.copy(workflowId = draft.workflowId, kind = draft.kind, channel = draft.channel,
            prompt = draft.prompt, promptRevision = it.promptRevision + 1, values = draft.values, suggestions = emptyList(), error = null, invalidField = null) }
        ensureResources()
    }
    suspend fun publish(job: ComfyJob, output: ComfyOutput): String = repository.publish(job.requestId, output)

    fun again(job: ComfyJob): Boolean {
        if (state.value.pending != null || state.value.submitting || !state.value.initialized || state.value.signInRequired || state.value.loading) return false
        val workflow = state.value.workflows.find { it.id == job.workflowId }
        if (workflow == null) { mutable.update { it.copy(error = "invalid_workflow") }; return false }
        try {
            val parameters = workflow.againParameters(job)
            val draft = workflow.prefill(job.copy(parameters = parameters))
            // Validate against current catalog/resources, while dispatching the original snapshot.
            val validated = workflow.parameters(draft.values, state.value.resources)
            parameters.forEach { (key, value) ->
                if (validated[key] != value) throw ComfyFailure("invalid_parameters", key)
            }
            applyDraft(draft)
            dispatch(ComfySubmission(workflow.id, parameters))
            return true
        } catch (e: ComfyFailure) {
            mutable.update { it.copy(error = e.code, invalidField = e.field) }; return false
        }
    }
    private fun dispatch(request: ComfySubmission) {
        val owner = epoch; val source = repository; val persistence = pendingStore
        mutable.update { it.copy(submitting = true, error = null, suggestions = emptyList(), pollingPaused = false) }
        recommendationJob?.cancel()
        viewModelScope.launch {
            var persisted = false
            try {
                persistence.write(request.requestId) // Durable BEFORE the only POST; a failed write forbids dispatch.
                persisted = true
                if (owner != epoch) return@launch
                val submitted = ComfyJob(request.requestId, request.workflowId, "submitting", request.parameters,
                    billingChannel = state.value.workflows.find { it.id == request.workflowId }?.channel)
                observe(submitted)
                mutable.update { it.copy(pending = request.requestId, selected = submitted,
                    jobs = (listOf(submitted) + it.jobs.filterNot { existing -> existing.requestId == request.requestId }).take(50)) }
                accept(source.submit(request), owner, persistence)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (owner == epoch) {
                    val code = if (!persisted) "storage" else failure(e)
                    if (persisted && code in comfyRejected) {
                        try { persistence.write(null) } catch (_: Exception) { /* Keep identity locked if clearing fails. */ }
                        val cleared = runCatching { persistence.read() == null }.getOrDefault(false)
                        if (owner != epoch) return@launch
                        val rejected = ComfyJob(request.requestId, request.workflowId, "failed", request.parameters, errorCode = code)
                        mutable.update { it.copy(pending = if (cleared) null else request.requestId,
                            selected = rejected, jobs = it.jobs.map { job -> if (job.requestId == request.requestId) rejected else job }, error = code) }
                    } else mutable.update {
                        val unknown = ComfyJob(request.requestId, request.workflowId, "unknown", request.parameters, errorCode = code)
                        it.copy(error = code, selected = if (persisted) unknown else it.selected,
                            jobs = it.jobs.map { job -> if (persisted && job.requestId == request.requestId) unknown else job })
                    }
                }
            } finally { if (owner == epoch) mutable.update { it.copy(submitting = false) } }
        }
    }
    private suspend fun accept(job: ComfyJob, owner: Long, persistence: ComfyPendingStore) {
        if (owner != epoch) return
        if (job.requestId == state.value.pending && job.status in comfyTerminal) {
            persistence.write(null)
            if (owner != epoch) return
            mutable.update { it.copy(pending = null) }
        }
        observe(job)
        mutable.update { current -> current.copy(selected = if (current.selected?.requestId == job.requestId) job else current.selected,
            jobs = if (current.jobs.any { it.requestId == job.requestId }) current.jobs.map { if (it.requestId == job.requestId) job else it }
                else (listOf(job) + current.jobs).take(50), error = job.errorCode) }
        if (foreground && !state.value.pollingPaused) schedulePoll()
    }
    fun open(job: ComfyJob) {
        stopPolling(user = false)
        mutable.update { it.copy(selected = job, pollingPaused = false, error = null) }
        check(job.requestId)
    }
    fun check(id: String? = state.value.pending ?: state.value.selected?.requestId) {
        if (!foreground || id == null || state.value.signInRequired || state.value.submitting || checkJob?.isActive == true) return
        val owner = epoch; val source = repository; val persistence = pendingStore
        pollJob?.cancel(); mutable.update { it.copy(polling = false) }
        lastPolled = id
        val generation = ++checkingGeneration
        mutable.update { it.copy(checking = true, error = null) }
        checkJob = viewModelScope.launch {
            try {
                val result = source.job(id)
                if (foreground && generation == checkingGeneration) accept(result, owner, persistence)
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (owner == epoch && generation == checkingGeneration) {
                    stopPolling(user = false)
                    mutable.update { it.copy(error = failure(e), selected = it.selected?.let { selected ->
                        if (selected.requestId == id) selected.copy(status = "query_failed") else selected }) }
                }
            } finally { if (owner == epoch && generation == checkingGeneration) mutable.update { it.copy(checking = false) } }
        }
    }
    private fun schedulePoll() {
        pollJob?.cancel()
        if (!foreground || state.value.pollingPaused || nextPollingId() == null) {
            mutable.update { it.copy(polling = false) }; return
        }
        mutable.update { it.copy(polling = true) }
        pollJob = viewModelScope.launch {
            delay(pollDelay.coerceAtLeast(1))
            mutable.update { it.copy(polling = false) }
            if (foreground && !state.value.pollingPaused) nextPollingId()?.let { check(it) }
        }
    }
    fun stopPolling(user: Boolean = true) {
        checkingGeneration++
        pollJob?.cancel(); checkJob?.cancel(); checkJob = null
        mutable.update { it.copy(polling = false, checking = false, pollingPaused = if (user) true else it.pollingPaused) }
    }
    fun resumePolling() { mutable.update { it.copy(pollingPaused = false) }; check(state.value.pending ?: nextPollingId() ?: state.value.selected?.requestId) }
    suspend fun output(job: ComfyJob, output: ComfyOutput): File = mediaLock.withLock {
        val source = repository; val owner = epoch
        withContext(io) {
            if (state.value.signInRequired) throw ComfyFailure("auth")
            mediaDirectory.mkdirs()
            val extension = mediaExtensions[output.mime] ?: throw ComfyFailure("media")
            val file = File(mediaDirectory, "$mediaSession-$owner-${job.requestId}-${output.index}.$extension")
            if (!file.isFile) {
                // Private, disposable cache: reserve at most one bounded media download.
                val existing = mediaDirectory.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }
                    ?.sortedBy { it.lastModified() }.orEmpty()
                var retained = existing.sumOf { it.length() }
                val selectedPrefix = "$mediaSession-$owner-${state.value.selected?.requestId}-"
                for (old in existing) {
                    if (retained <= 256L * 1024 * 1024 && System.currentTimeMillis() - old.lastModified() < 24 * 60 * 60 * 1000L) break
                    if (old.name.startsWith(selectedPrefix)) continue
                    val length = old.length()
                    if (old.delete()) retained -= length
                }
                val temporary = File.createTempFile("download-", ".part", mediaDirectory)
                try {
                    source.download(job.requestId, output, temporary)
                    if (owner != epoch) throw CancellationException("Connection changed")
                    if (!temporary.renameTo(file)) throw ComfyFailure("storage")
                } finally { temporary.delete() }
            }
            file
        }
    }
    private fun failure(error: Exception) = (error as? ComfyFailure)?.code ?: "network"
}
