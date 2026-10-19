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
    /** The last model list saved on this device, so names show right away on a cold start. */
    suspend fun cachedAgents(): List<Agent> = emptyList()
    suspend fun saveConversations(rows: List<Conversation>) {}
    suspend fun cachedClaudeSessions(): ClaudeSessionPage? = null
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
    /** Likely next messages for the latest finished answer: (message id, chips). */
    suspend fun suggestions(conversationId: String): Pair<String?, List<String>> = null to emptyList()
    /** Answers a question the agent asked in the running turn; false when it is no longer open. */
    suspend fun answer(conversationId: String, controlId: String, questionId: String, answer: String): Boolean = false
    /** Sends [message] into the running turn; false when that turn cannot take it (queue it instead). */
    suspend fun steer(conversationId: String, controlId: String, message: String, attachments: List<Attachment> = emptyList()): Boolean = false
    /** The latest output of a background task; null once the server no longer has it. */
    suspend fun taskOutput(conversationId: String, taskId: String): TaskOutput? = null
    /** Stops one background task of the running turn; false when it no longer runs. */
    suspend fun stopTask(conversationId: String, controlId: String, taskId: String): Boolean = false
    /** A side question about the chat: answered from its transcript, never added to it or sent to its agent. */
    suspend fun sideQuestion(conversationId: String, question: String, history: List<SideQuestion> = emptyList()): String =
        throw ApiException(404, ClientError("Side questions are not available on this server.", "side_unavailable", false))
    /** Archive settings and how many chats are archived; [setArchiveDays] changes how long a chat may idle. */
    suspend fun archiveInfo(): ArchiveInfo? = null
    suspend fun setArchiveDays(days: Int): ArchiveInfo? = null
    /** The chats the server archived (by hand or for idling), newest first. */
    suspend fun archivedConversations(): List<Conversation> = emptyList()
    /** Pins or archives a chat (null leaves that side unchanged). */
    suspend fun updateConversation(id: String, pinned: Boolean? = null, archived: Boolean? = null) {}
    /** The agents' shared memory and its last tidy; one memory's text; a tidy started now. */
    suspend fun memory(): MemoryOverview = throw ApiException(404, ClientError("Memory is not available on this server.", "not_found", false))
    suspend fun memoryFile(file: String): String = throw ApiException(404, ClientError("Memory is not available on this server.", "not_found", false))
    suspend fun consolidateMemory() {}
    /** Deletes a chat on the server for good, stopping its running turn first. */
    suspend fun deleteConversation(id: String): Unit = error("Deleting chats is unavailable")
    /** Drops idle connections, so the next request dials afresh (the app was away; they may be dead). */
    fun freshConnections() {}
    /** A file the chat names, read from the computer for viewing: text cut at 1 MB, pictures and PDFs whole. */
    suspend fun readFile(conversationId: String, path: String): FileContent =
        throw ApiException(404, ClientError("Files cannot be opened on this server.", "not_found", false))
    suspend fun resume(conversationId: String, headId: String): JsonObject
    suspend fun claudeSessions(cursor: String? = null, project: String? = null): ClaudeSessionPage
    suspend fun claudeHistory(id: String, cursor: String? = null): ClaudeSessionHistory
    suspend fun adoptClaudeSession(id: String): String
}

/** The most the file viewer takes: the server sends at most 20 MB of a picture or PDF. */
private const val MAX_FILE_BYTES = 21L * 1024 * 1024

/** Whether a request may share a pooled connection: reads to an HTTPS origin only. */
internal fun reusesConnection(origin: String, read: Boolean) = read && origin.startsWith("https://", ignoreCase = true)

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
            .writeTimeout(30, TimeUnit.SECONDS).addInterceptor(Diagnostics.interceptor).build()
    }

    private fun request(path: String, body: JsonObject? = null, lastId: String? = null): Request {
        if (authRequired.value) throw ApiException(401, signInRequiredError)
        val builder = Request.Builder().url(origin + path)
            .header("AgentWeb-Protocol-Min", "1.0")
            .header("AgentWeb-Protocol-Max", "1.0")
            .header("AgentWeb-Client", "android/${BuildConfig.VERSION_NAME}")
        // The AgentWeb server speaks HTTP/1.0 and closes every connection, and a reused socket can look
        // alive through adb reverse or a proxy: a mutating request, and anything to a plain-HTTP origin,
        // goes out on its own connection. Reads over HTTPS (Cloudflare in front) share one HTTP/2
        // connection: a fresh TLS handshake across the Pacific was most of the ~1.1 s each read took.
        if (!reusesConnection(origin, body == null)) builder.header("Connection", "close")
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
    // server closes connections; adb reverse can leave the phone side open), and pings find a
    // dead HTTP/2 link. Mutating requests use the non-retrying client without a pool: they are
    // never replayed and never written onto a connection that was sitting idle.
    private val readClient: OkHttpClient by lazy {
        client.newBuilder().connectionPool(ConnectionPool(4, 4, TimeUnit.MINUTES)).pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true).build()
    }
    private val writeClient: OkHttpClient by lazy { client.newBuilder().connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS)).build() }

    /** A write that may be sent twice without harm (set pinned, archive, delete): it may ride the shared connection. */
    private object Idempotent

    private fun clientFor(request: Request) =
        if (request.method == "GET" || request.tag(Idempotent::class.java) != null) readClient else writeClient

    // After the app was away the shared connection can be dead without knowing it (the VPN or the radio dropped it):
    // a request on it waited for the 20 s ping to notice (10-20 s on a foreground in release check 420416).
    override fun freshConnections() { readClient.connectionPool.evictAll() }

    private fun idempotent(request: Request): Request = request.newBuilder().tag(Idempotent::class.java, Idempotent)
        .apply { if (!reusesConnection(origin, true)) header("Connection", "close") }.build()

    // A connection that fails before it is up (refused, unreachable, TLS handshake cut off by a mobile network or VPN)
    // carried no request, so even a message send is tried once more; a request that may have reached the server
    // never is.
    private suspend fun <T> execute(request: Request, timeoutSeconds: Long? = null, readTimeoutSeconds: Long? = null,
        read: (Response) -> T): T {
        var retries = 0
        while (true) {
            try { return executeOnce(request, timeoutSeconds, readTimeoutSeconds, read) }
            catch (e: IOException) {
                if (!neverSent(e) || retries == MAX_UNSENT_RETRIES) throw e
                retries++
                Diagnostics.warn("http", "${request.method} ${Diagnostics.target(request.url)} retried", "error" to Diagnostics.describe(e))
                delay(reconnectDelayMs * retries * retries)
            }
        }
    }

    private suspend fun <T> executeOnce(request: Request, timeoutSeconds: Long? = null, readTimeoutSeconds: Long? = null,
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
        execute(idempotent(request(path).newBuilder().delete().build()), timeoutSeconds) { response ->
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

    /** PUT to a presigned cloud-storage link: no Authorization, no cookies, no redirects, never replayed. */
    internal suspend fun uploadStorage(url: String, bytes: ByteArray, contentType: String): Unit = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Connection", "close").put(bytes.toRequestBody(contentType.toMediaType())).build()
        execute(request, 300, 60) { response -> if (!response.isSuccessful) throw IOException("Upload refused (${response.code})") }
    }

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
    override suspend fun agents() = json("/api/agents").let { data ->
        data.objects("agents").map(Agent::from).also { runCatching { saveSnapshot("agents", data) } }
    }
    override suspend fun cachedAgents(): List<Agent> = runCatching {
        snapshot("agents")?.objects("agents")?.map(Agent::from).orEmpty()
    }.getOrDefault(emptyList())
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
    // Last-known responses, one folder per server + credential so another account never sees
    // them; other accounts' folders are removed on save. Only lists are kept, never history.
    private fun snapshotDir(): java.io.File? = listCacheDir?.let { dir -> credentialKey()?.let { java.io.File(dir, it) } }
    /** A stable folder name for this server + credential; a different account never gets the same one. */
    internal fun credentialKey(): String? {
        val token = tokens.tokenFor(origin)?.takeIf { it.isNotBlank() } ?: return null
        return java.security.MessageDigest.getInstance("SHA-256").digest((origin + "\n" + token).toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)
    }
    internal suspend fun snapshot(name: String): JsonObject? = withContext(Dispatchers.IO) {
        runCatching {
            val file = snapshotDir()?.let { java.io.File(it, "$name.json") }?.takeIf { it.isFile } ?: return@runCatching null
            Json.parseToJsonElement(file.readText()).jsonObject
        }.getOrNull()
    }
    internal suspend fun saveSnapshot(name: String, value: JsonObject) = withContext(Dispatchers.IO) {
        val dir = snapshotDir() ?: return@withContext
        runCatching {
            dir.mkdirs()
            dir.parentFile?.listFiles()?.filter { it != dir }?.forEach { it.deleteRecursively() }
            val file = java.io.File(dir, "$name.json"); val temp = java.io.File(dir, "$name.json.tmp")
            temp.writeText(value.toString())
            if (!temp.renameTo(file)) temp.delete()
        }
        Unit
    }
    override suspend fun cachedConversations(): List<Conversation> =
        snapshot("conversations")?.objects("conversations")?.map(Conversation::from).orEmpty()
    override fun clearConversationCache() { listCacheDir?.listFiles()?.forEach { it.deleteRecursively() } }
    override suspend fun saveConversations(rows: List<Conversation>) =
        saveSnapshot("conversations", buildJsonObject { put("conversations", JsonArray(rows.take(200).map { it.cacheJson() })) })
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
        if (cursor == null && project == null) saveSnapshot("claude-sessions", result)
        return ClaudeSessionPage(result.objects("sessions").map(ClaudeSession::from), result.string("nextCursor"))
    }
    override suspend fun cachedClaudeSessions(): ClaudeSessionPage? = snapshot("claude-sessions")?.let { result ->
        runCatching { ClaudeSessionPage(result.objects("sessions").map(ClaudeSession::from), result.string("nextCursor")) }.getOrNull()
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
    override suspend fun suggestions(conversationId: String): Pair<String?, List<String>> {
        val data = json("/api/chat/conversations/$conversationId/suggestions")
        return (data["messageId"] as? JsonPrimitive)?.contentOrNull?.takeIf { it != "null" } to
            data.array("suggestions").mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }.take(3)
    }
    override suspend fun answer(conversationId: String, controlId: String, questionId: String, answer: String): Boolean = try {
        json("/api/chat/answer", buildJsonObject { put("conversationId", conversationId); put("controlId", controlId)
            put("questionId", questionId); put("answer", answer) }).string("answered") == questionId
    } catch (e: ApiException) {
        if (e.status == 409 || e.status == 404) false else throw e
    }
    override suspend fun steer(conversationId: String, controlId: String, message: String, attachments: List<Attachment>): Boolean = try {
        json("/api/chat/steer", JsonObject(mapOf("conversationId" to JsonPrimitive(conversationId), "controlId" to JsonPrimitive(controlId),
            "message" to JsonPrimitive(message)) + attachmentFields(attachments)), timeoutSeconds = if (attachments.isEmpty()) null else 120)
            .boolean("steered") == true
    } catch (e: ApiException) {
        if (e.status == 409 || e.status == 404) false else throw e
    }
    override suspend fun taskOutput(conversationId: String, taskId: String): TaskOutput? = try {
        val data = json("/api/chat/task-output?conversationId=${java.net.URLEncoder.encode(conversationId, "UTF-8")}" +
            "&taskId=${java.net.URLEncoder.encode(taskId, "UTF-8")}")
        TaskOutput(data.string("output").orEmpty(), data.boolean("truncated") == true, data.long("size") ?: 0)
    } catch (e: ApiException) {
        if (e.status == 410 || e.status == 404) null else throw e
    }
    override suspend fun stopTask(conversationId: String, controlId: String, taskId: String): Boolean = try {
        json("/api/chat/task-stop", buildJsonObject { put("conversationId", conversationId); put("controlId", controlId)
            put("taskId", taskId) }).string("stopping") == taskId
    } catch (e: ApiException) {
        if (e.status == 409 || e.status == 404) false else throw e
    }
    override suspend fun sideQuestion(conversationId: String, question: String, history: List<SideQuestion>): String =
        json("/api/chat/side", buildJsonObject {
            put("conversationId", conversationId); put("question", question)
            putJsonArray("history") { history.filter { it.answer != null }.forEach { item -> addJsonObject {
                put("question", item.question); put("answer", item.answer.orEmpty()) } } }
        }, timeoutSeconds = 120).string("answer").orEmpty()
    override suspend fun deleteConversation(id: String) { delete("/api/chat/conversations/${segment(id)}", 30) }
    override suspend fun archiveInfo() = ArchiveInfo.from(json("/api/chat/archive"))
    override suspend fun memory() = MemoryOverview.from(json("/api/memory"))
    override suspend fun memoryFile(file: String) = json("/api/memory/${segment(file)}").string("text").orEmpty()
    override suspend fun consolidateMemory() { json("/api/memory/consolidate", buildJsonObject { }) }
    override suspend fun setArchiveDays(days: Int) = ArchiveInfo.from(json("/api/chat/archive", buildJsonObject { put("days", days) }))
    override suspend fun archivedConversations() = json("/api/chat/conversations?archived=1").objects("conversations").map(Conversation::from)
    override suspend fun updateConversation(id: String, pinned: Boolean?, archived: Boolean?) = withContext(Dispatchers.IO) {
        val body = buildJsonObject { pinned?.let { put("pinned", it) }; archived?.let { put("archived", it) } }
        execute(idempotent(request("/api/chat/conversations/${segment(id)}").newBuilder()
            .patch(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()), 30) { response ->
            validate(response)
        }
    }
    override suspend fun readFile(conversationId: String, path: String): FileContent = withContext(Dispatchers.IO) {
        val owner = currentCoroutineContext()
        val request = request("/api/chat/conversations/${segment(conversationId)}/file?path=${java.net.URLEncoder.encode(path, "UTF-8")}")
        execute(request, 120) { response ->
            validate(response)
            val body = response.body ?: throw IOException("Empty file response")
            if (body.contentLength() > MAX_FILE_BYTES) throw IOException("File too large")
            val bytes = body.byteStream().use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    owner.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    out.write(buffer, 0, count)
                    if (out.size() > MAX_FILE_BYTES) throw IOException("File too large")
                }
                out.toByteArray()
            }
            fun header(name: String) = response.header("AgentWeb-File-$name")
            FileContent(header("Path")?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() } ?: path,
                header("Kind") ?: "text", body.contentType()?.let { "${it.type}/${it.subtype}" } ?: "application/octet-stream",
                bytes, header("Size")?.toLongOrNull() ?: bytes.size.toLong(), header("Modified")?.toLongOrNull() ?: 0L,
                header("Truncated") == "1")
        }
    }
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
                if (state?.done == true) {
                    Diagnostics.event("stream", "done", "conversation" to conversationId, "lastSequence" to state?.lastSequence)
                    return@flow
                }
                throw IOException("Stream ended before done.")
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                failures = (failures + 1).coerceAtMost(64)
                Diagnostics.warn("stream", "reconnecting", "conversation" to conversationId, "failures" to failures,
                    "lastSequence" to state?.lastSequence, "error" to Diagnostics.describe(e))
                state?.let { emit(TurnSnapshot(it, Connection.RECONNECTING)) }
                delay(ReconnectPolicy.delayMillis(failures, reconnectDelayMs))
            } catch (e: ApiException) {
                if (post == null && e.problem.retryable != false && ReconnectPolicy.retryStatus(e.status)) {
                    failures = (failures + 1).coerceAtMost(64)
                    Diagnostics.warn("stream", "reconnecting", "conversation" to conversationId, "failures" to failures,
                        "lastSequence" to state?.lastSequence, "status" to e.status)
                    state?.let { emit(TurnSnapshot(it, Connection.RECONNECTING)) }
                    delay(ReconnectPolicy.delayMillis(failures, reconnectDelayMs))
                    continue
                }
                Diagnostics.warn("stream", "failed", "conversation" to conversationId, "status" to e.status, "code" to e.problem.code)
                throw ApiException(e.status, e.problem, directSendRejected = post != null && e.status in 400..499)
            }
        }
    }.flowOn(Dispatchers.IO)
}

/** Tries after the first for a call that failed before it was sent (0.5 s, then 2 s apart). */
private const val MAX_UNSENT_RETRIES = 2

/** True when [e] ended a call before any request byte could reach the server. */
internal fun neverSent(e: IOException): Boolean = e is javax.net.ssl.SSLHandshakeException || e is java.net.ConnectException ||
    e is java.net.UnknownHostException || e is java.net.NoRouteToHostException ||
    (e is java.net.SocketTimeoutException && e.message.orEmpty().contains("connect", ignoreCase = true))
