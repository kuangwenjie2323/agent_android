package com.karewinkcloud.agentweb.client.data

import com.karewinkcloud.agentweb.client.core.*
import com.karewinkcloud.agentweb.client.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun interface TokenProvider {
    fun tokenFor(origin: String): String?
    fun unauthorized(origin: String, rejectedToken: String?) {}
}

fun normalizeBaseUrl(value: String, debug: Boolean): String {
    val url = value.trim().toHttpUrlOrNull() ?: throw IllegalArgumentException("Enter a valid server URL.")
    require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
        "The URL cannot contain credentials, a query, or a fragment."
    }
    require(url.encodedPath == "/") { "Enter the server origin without a path, for example https://agent.karewinkcloud.com." }
    require(url.isHttps || (debug && url.host in setOf("localhost", "127.0.0.1"))) {
        "Use HTTPS. Debug builds also allow HTTP on localhost or 127.0.0.1."
    }
    return url.toString().trimEnd('/')
}

enum class Connection { CONNECTING, LIVE, RECONNECTING, COMPLETE }
data class TurnSnapshot(val turn: TurnState, val connection: Connection)

interface AgentRepository {
    val authenticationRequired: Flow<Boolean> get() = emptyFlow()
    suspend fun agents(): List<Agent>
    suspend fun conversations(): List<Conversation>
    /** The last list saved on this device for the current server and account; empty when none. */
    suspend fun cachedConversations(): List<Conversation> = emptyList()
    suspend fun saveConversations(rows: List<Conversation>) {}
    fun clearConversationCache() {}
    suspend fun createConversation(choice: ModelChoice): Conversation
    suspend fun createConversation(choice: ModelChoice, projectId: String?): Conversation = createConversation(choice)
    suspend fun projects(): List<Project> = emptyList()
    suspend fun registerProject(root: String, name: String): Project = error("Project registration unavailable")
    suspend fun fork(request: ForkRequest): ForkResponse = error("Conversation variants unavailable")
    suspend fun detail(id: String, cursor: String? = null): ConversationDetail
    suspend fun preview(id: String): ConversationDetail = detail(id)
    fun follow(conversationId: String, runId: String?, send: SendRequest? = null, previous: TurnState? = null): Flow<TurnSnapshot>
    suspend fun stop(conversationId: String, controlId: String): JsonObject
    suspend fun queue(request: QueueRequest): JsonObject
    suspend fun resume(conversationId: String, headId: String): JsonObject
    suspend fun claudeSessions(cursor: String? = null, project: String? = null): ClaudeSessionPage
    suspend fun claudeHistory(id: String, cursor: String? = null): ClaudeSessionHistory
    suspend fun adoptClaudeSession(id: String): String
}

class HttpAgentRepository(
    val origin: String,
    private val tokens: TokenProvider,
    private val client: OkHttpClient = defaultClient(),
    private val reconnectDelayMs: Long = 500,
    private val listCacheDir: java.io.File? = null,
) : AgentRepository {
    private val authRequired = MutableStateFlow(false)
    override val authenticationRequired = authRequired.asStateFlow()
    companion object {
        fun defaultClient() = OkHttpClient.Builder()
            // POST /message and New Chat are deliberately never replayed by the HTTP stack.
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS).build()
    }

    private fun request(path: String, body: JsonObject? = null, lastId: String? = null): Request {
        if (authRequired.value) throw ApiException(401, signInRequiredError)
        val builder = Request.Builder().url(origin + path)
            .header("AgentWeb-Protocol-Min", "1.0")
            .header("AgentWeb-Protocol-Max", "1.0")
            .header("AgentWeb-Client", "android/${BuildConfig.VERSION_NAME}")
            // The AgentWeb API speaks HTTP/1.0 and closes every connection after its
            // response. Never pool: a reused socket can look alive through adb reverse or
            // a proxy, and a mutating POST must never be written onto a dead connection.
            .header("Connection", "close")
        tokens.tokenFor(origin)?.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }
        if (body != null) builder.post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        if (lastId != null) builder.header("Last-Event-ID", lastId)
        return builder.build()
    }

    private fun validate(response: Response) {
        if (response.code == 401) {
            authRequired.value = true
            tokens.unauthorized(origin, response.request.header("Authorization")?.removePrefix("Bearer "))
            throw ApiException(401, signInRequiredError)
        }
        if (!response.isSuccessful) throw ApiException(response.code, parseApiError(response.code, response.body?.string().orEmpty()))
        val selected = response.header("X-AgentWeb-Protocol-Version")
        if (selected != null && selected != "1.0") throw ApiException(409,
            ClientError("The server selected an unsupported protocol. Update this app.", "protocol_version_mismatch", false))
    }

    // Idempotent GETs may transparently retry once on a stale pooled connection (the
    // server closes connections; adb reverse can leave the phone side open). Mutating
    // POSTs keep using the non-retrying client and are never replayed.
    private val readClient: OkHttpClient by lazy { client.newBuilder().retryOnConnectionFailure(true).build() }

    private fun clientFor(request: Request) = if (request.method == "GET") readClient else client

    private suspend fun <T> execute(request: Request, timeoutSeconds: Long? = null, readTimeoutSeconds: Long? = null,
        read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
        val transport = clientFor(request).let { if (timeoutSeconds == null) it else it.newBuilder()
            .readTimeout(readTimeoutSeconds ?: timeoutSeconds, TimeUnit.SECONDS).callTimeout(timeoutSeconds, TimeUnit.SECONDS).build() }
        val call = transport.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                // Keep cancellation attached until the response body has been consumed.
                // Parsing/streaming runs on OkHttp's IO worker, never on the UI thread.
                val result = runCatching { response.use(read) }
                if (continuation.isActive) result.fold(continuation::resume, continuation::resumeWithException)
            }
        })
    }

    internal suspend fun json(path: String, body: JsonObject? = null, timeoutSeconds: Long? = null): JsonObject = withContext(Dispatchers.IO) {
        execute(request(path, body), timeoutSeconds) { response ->
            validate(response)
            runCatching { wireJson.parseToJsonElement(response.body?.string().orEmpty()) as JsonObject }
                .getOrElse { throw ApiException(response.code, ClientError("The server returned an unreadable response.")) }
        }
    }
    internal suspend fun delete(path: String, timeoutSeconds: Long? = null): JsonObject = withContext(Dispatchers.IO) {
        execute(request(path).newBuilder().delete().build(), timeoutSeconds) { response ->
            validate(response)
            runCatching { wireJson.parseToJsonElement(response.body?.string().orEmpty()) as JsonObject }
                .getOrElse { throw ApiException(response.code, ClientError("The server returned an unreadable response.")) }
        }
    }
    /** Stream authenticated output to private disk; never load an unbounded byte array. */
    internal suspend fun download(path: String, destination: java.io.File, mime: String, maxBytes: Long,
        onProgress: (Long, Long) -> Unit = { _, _ -> }): Unit = fetch(request(path), destination, mime, maxBytes, onProgress)

    /** Fetch a presigned cloud-storage link: no Authorization, no cookies, no redirects. */
    internal suspend fun downloadStorage(url: String, destination: java.io.File, mime: String, maxBytes: Long,
        onProgress: (Long, Long) -> Unit = { _, _ -> }): Unit =
        fetch(Request.Builder().url(url).header("Connection", "close").build(), destination, mime, maxBytes, onProgress)

    private suspend fun fetch(request: Request, destination: java.io.File, mime: String, maxBytes: Long,
        onProgress: (Long, Long) -> Unit): Unit = withContext(Dispatchers.IO) {
        val owner = currentCoroutineContext()
        try {
            // Slow links (a 4K original at tens of KB/s) need minutes: bound the whole call
            // generously and fail fast only when bytes stop arriving.
            execute(request, 1800, 60) { response ->
                validate(response)
                val body = response.body ?: throw IOException("Empty media response")
                if (body.contentType()?.toString()?.substringBefore(';') != mime || body.contentLength() > maxBytes)
                    throw IOException("Invalid media response")
                body.byteStream().use { input -> destination.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        owner.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > maxBytes) throw IOException("Media exceeds download limit")
                        output.write(buffer, 0, count)
                        onProgress(total, body.contentLength())
                    }
                    if (total == 0L || (body.contentLength() >= 0 && total != body.contentLength())) throw IOException("Incomplete media response")
                } }
            }
        } catch (e: Exception) { destination.delete(); throw e }
    }
    override suspend fun agents() = json("/api/agents").objects("agents").map(Agent::from)
    internal suspend fun downloadChatMedia(target: MediaTarget, destination: java.io.File, maxBytes: Long): String = withContext(Dispatchers.IO) {
        val req = if (target.authenticated) request(target.url.encodedPath + (target.url.encodedQuery?.let { "?$it" } ?: ""))
            else Request.Builder().url(target.url).build()
        val owner = currentCoroutineContext()
        try {
            execute(req, 120) { response ->
                if (target.authenticated) validate(response) else if (!response.isSuccessful) throw IOException("Media unavailable")
                val body = response.body ?: throw IOException("Empty media response")
                if (body.contentLength() > maxBytes) throw IOException("附件下载上限为 32 MB")
                body.byteStream().use { input -> destination.outputStream().use { out ->
                    val buffer = ByteArray(32 * 1024); var total = 0L
                    while (true) {
                        owner.ensureActive()
                        val count = input.read(buffer); if (count < 0) break
                        total += count
                        if (total > maxBytes) throw IOException("附件下载上限为 32 MB")
                        out.write(buffer, 0, count)
                    }
                    if (total == 0L || (body.contentLength() >= 0 && total != body.contentLength())) throw IOException("Incomplete media response")
                } }
                body.contentType()?.let { "${it.type}/${it.subtype}" } ?: "application/octet-stream"
            }
        } catch (e: Exception) { destination.delete(); throw e }
    }
    override suspend fun conversations() = json("/api/chat/conversations").objects("conversations").map(Conversation::from)
    // One file per server + credential, so another account never sees this list; older files are removed.
    private fun listCacheFile(): java.io.File? = listCacheDir?.let { dir ->
        val token = tokens.tokenFor(origin)?.takeIf { it.isNotBlank() } ?: return null
        val key = java.security.MessageDigest.getInstance("SHA-256").digest((origin + "\n" + token).toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)
        java.io.File(dir, "$key.json")
    }
    override suspend fun cachedConversations(): List<Conversation> = withContext(Dispatchers.IO) {
        runCatching {
            val file = listCacheFile()?.takeIf { it.isFile } ?: return@runCatching emptyList()
            Json.parseToJsonElement(file.readText()).jsonObject.objects("conversations").map(Conversation::from)
        }.getOrDefault(emptyList())
    }
    override fun clearConversationCache() { listCacheDir?.listFiles()?.forEach { it.delete() } }
    override suspend fun saveConversations(rows: List<Conversation>) = withContext(Dispatchers.IO) {
        val file = listCacheFile() ?: return@withContext
        runCatching {
            file.parentFile?.mkdirs()
            val temp = java.io.File(file.parentFile, file.name + ".tmp")
            temp.writeText(buildJsonObject { put("conversations", JsonArray(rows.take(200).map { it.cacheJson() })) }.toString())
            if (!temp.renameTo(file)) temp.delete()
            file.parentFile?.listFiles()?.filter { it.name != file.name }?.forEach { it.delete() }
        }
        Unit
    }
    override suspend fun createConversation(choice: ModelChoice) = createConversation(choice, null)
    override suspend fun createConversation(choice: ModelChoice, projectId: String?) = Conversation.from(json("/api/chat/conversations", buildJsonObject {
        put("title", "New Chat")
        if (choice.agent.isNotBlank()) put("agent", choice.agent)
        if (choice.model.isNotBlank()) put("model", choice.model)
        if (projectId != null) put("projectId", projectId)
    }))
    override suspend fun projects() = json("/api/chat/projects").objects("projects").mapNotNull(Project::from).distinctBy { it.id }
    override suspend fun registerProject(root: String, name: String): Project {
        val response = json("/api/chat/projects", buildJsonObject {
            put("root", root.trim()); if (name.isNotBlank()) put("name", name.trim())
        })
        return Project.from(response.obj("project") ?: response) ?: throw IOException("Invalid project response")
    }
    private fun segment(value: String) = HttpUrl.Builder().scheme("https").host("unused.invalid")
        .addPathSegment(value).build().encodedPath.removePrefix("/")
    private fun query(path: String, values: Map<String, String?>): String {
        val url = requireNotNull((origin + path).toHttpUrlOrNull()).newBuilder()
        values.forEach { (key, value) -> if (value != null) url.addQueryParameter(key, value) }
        return url.build().let { it.encodedPath + (it.encodedQuery?.let { q -> "?$q" } ?: "") }
    }
    override suspend fun claudeSessions(cursor: String?, project: String?): ClaudeSessionPage {
        val result = json(query("/api/claude-sessions", mapOf("cursor" to cursor, "project" to project)))
        return ClaudeSessionPage(result.objects("sessions").map(ClaudeSession::from), result.string("nextCursor"))
    }
    override suspend fun claudeHistory(id: String, cursor: String?): ClaudeSessionHistory {
        val result = json(query("/api/claude-sessions/${segment(id)}", mapOf("cursor" to cursor)))
        return ClaudeSessionHistory(ClaudeSession.from(requireNotNull(result.obj("session"))),
            result.objects("messages").map(ChatMessage::from), result.obj("messagePage")?.string("nextCursor"),
            result.obj("messagePage")?.long("olderCount")?.toInt() ?: 0)
    }
    override suspend fun adoptClaudeSession(id: String): String {
        val result = json("/api/claude-sessions/${segment(id)}/adopt", buildJsonObject { })
        return result.string("conversationId")?.takeIf { it.isNotBlank() }
            ?: throw ApiException(502, ClientError("Unable to confirm session adoption. Refresh and try Continue here again."))
    }
    override suspend fun detail(id: String, cursor: String?): ConversationDetail {
        require(cursor == null || (cursor.isNotEmpty() && cursor.all { it in '0'..'9' }))
        return ConversationDetail.from(json("/api/chat/conversations/${segment(id)}?compact=1&messageLimit=40" +
            (cursor?.let { "&messageCursor=$it" } ?: "")))
    }
    override suspend fun fork(request: ForkRequest) = ForkResponse.from(
        json("/api/chat/conversations/${segment(request.conversationId)}/forks", request.json()))
    override suspend fun preview(id: String) = ConversationDetail.from(json("/api/chat/conversations/${segment(id)}?compact=1&messageLimit=1"))
    override suspend fun stop(conversationId: String, controlId: String) = json("/api/chat/stop", buildJsonObject {
        put("conversationId", conversationId); put("expectedControlId", controlId)
    })
    override suspend fun queue(request: QueueRequest) = json("/api/chat/queue", request.json())
    override suspend fun resume(conversationId: String, headId: String) = json("/api/chat/queue/resume", buildJsonObject {
        put("conversationId", conversationId); put("expectedHeadItemId", headId)
    })

    private sealed interface Packet {
        data class Header(val run: String, val conversation: String?, val fullReplay: Boolean) : Packet
        data class Event(val event: StreamEvent) : Packet
    }

    /** A cancelled collector closes only its own socket, never the server turn. */
    private fun packets(request: Request): Flow<Packet> = callbackFlow {
        val call = clientFor(request).newCall(request.newBuilder().header("Accept", "text/event-stream").build())
        val reader = launch(Dispatchers.IO) {
            try {
                call.execute().use { response ->
                    validate(response)
                    if (response.body?.contentType()?.let { it.type == "text" && it.subtype == "event-stream" } != true) {
                        throw StreamProtocolException("Expected an event stream from the server.")
                    }
                    val run = response.header("X-Stream-Id")?.takeIf(String::isNotBlank)
                        ?: throw StreamProtocolException("The stream is missing its run identity.")
                    send(Packet.Header(run, response.header("X-Conversation-Id"), response.header("X-Resume-Mode") == "full"))
                    val parser = SseParser()
                    response.body!!.charStream().use { source ->
                        val buffer = CharArray(4096)
                        var done = false
                        while (isActive && !done) {
                            val size = source.read(buffer)
                            if (size < 0) break
                            for (frame in parser.feed(String(buffer, 0, size))) {
                                val event = StreamEvent.from(frame)
                                send(Packet.Event(event))
                                if (event.type == "done") { done = true; break }
                            }
                        }
                    }
                }
                close()
            } catch (e: Exception) { close(e) }
        }
        awaitClose { call.cancel(); reader.cancel() }
    }.buffer(64)

    override fun follow(conversationId: String, runId: String?, send: SendRequest?, previous: TurnState?): Flow<TurnSnapshot> = flow {
        val startedAt = previous?.startedAt ?: System.nanoTime() / 1_000_000
        var state = previous?.copy(startedAt = startedAt) ?: runId?.let { TurnState(conversationId, it, startedAt = startedAt) }
        var first = true
        var failures = 0
        while (currentCoroutineContext().isActive) {
            val post = if (first) send else null
            first = false // Even a failure before headers must never replay the POST.
            val request = if (post != null) request("/api/chat/message", post.json()) else
                request("/api/chat/stream/${segment(conversationId)}", lastId = state?.lastEventId)
            try {
                packets(request).collect { packet ->
                    when (packet) {
                        is Packet.Header -> {
                            if (packet.conversation != null && packet.conversation != conversationId) {
                                throw StreamProtocolException("This stream belongs to another conversation.")
                            }
                            if (state != null && state!!.runId != packet.run) {
                                throw StreamProtocolException("The run changed. Reloading saved history is required.")
                            }
                            if (post == null && packet.fullReplay && (state?.lastSequence ?: 0) > 0) {
                                throw StreamProtocolException("The server reset the replay cursor. Reloading saved history is required.")
                            }
                            if (state == null) state = TurnState(conversationId, packet.run, startedAt = startedAt)
                            emit(TurnSnapshot(state!!, Connection.LIVE))
                        }
                        is Packet.Event -> {
                            val before = state ?: throw StreamProtocolException("An event arrived before its run identity.")
                            val reduced = TurnReducer.reduce(before, packet.event, System.nanoTime() / 1_000_000)
                            state = reduced
                            if (reduced.lastSequence > before.lastSequence) failures = 0
                            emit(TurnSnapshot(reduced, if (reduced.done) Connection.COMPLETE else Connection.LIVE))
                        }
                    }
                }
                if (state?.done == true) return@flow
                throw IOException("Stream ended before done.")
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                failures = (failures + 1).coerceAtMost(64)
                state?.let { emit(TurnSnapshot(it, Connection.RECONNECTING)) }
                delay(ReconnectPolicy.delayMillis(failures, reconnectDelayMs))
            } catch (e: ApiException) {
                if (post == null && e.problem.retryable != false && ReconnectPolicy.retryStatus(e.status)) {
                    failures = (failures + 1).coerceAtMost(64)
                    state?.let { emit(TurnSnapshot(it, Connection.RECONNECTING)) }
                    delay(ReconnectPolicy.delayMillis(failures, reconnectDelayMs))
                    continue
                }
                throw ApiException(e.status, e.problem, directSendRejected = post != null && e.status in 400..499)
            }
        }
    }.flowOn(Dispatchers.IO)
}
