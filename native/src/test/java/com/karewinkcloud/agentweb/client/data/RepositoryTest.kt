package com.karewinkcloud.agentweb.client.data

import com.karewinkcloud.agentweb.client.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.TimeUnit

class RepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repo: HttpAgentRepository
    private val control = "1234567890abcdef1234567890abcdef"
    @Before fun setup() {
        server = MockWebServer().apply { start() }
        repo = HttpAgentRepository(server.url("/").toString().trimEnd('/'), TokenProvider { "test-token" }, reconnectDelayMs = 0)
    }
    @After fun cleanup() { server.shutdown() }
    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status)
        .addHeader("Content-Type", "application/json").addHeader("X-AgentWeb-Protocol-Version", "1.0").setBody(body)
    private fun sse(body: String, run: String = control) = MockResponse().addHeader("Content-Type", "text/event-stream")
        .addHeader("X-Stream-Id", run).addHeader("X-Conversation-Id", "conversation").setBody(body)
    private fun text(seq: Int, content: String) = "id: $control:$seq\ndata: {\"type\":\"text\",\"seq\":$seq,\"content\":\"$content\"}\n\n"
    private fun done(seq: Int) = "id: $control:$seq\ndata: {\"type\":\"done\",\"seq\":$seq,\"conversationId\":\"conversation\"}\n\n"
    private fun next() = requireNotNull(server.takeRequest(3, TimeUnit.SECONDS))

    @Test fun forkPostsExactRequestToEncodedPathAndReturnsAdvertisedRun() = runBlocking {
        server.enqueue(json("""{"conversation":{"id":"child","active":true},"variant":{"kind":"retry","parentConversation":{"id":"parent","title":"Parent"}},"run":{"id":"$control","status":"running","agent":"a","model":"m"},"streamUrl":"https://untrusted.invalid/stream","existing":false}""", 202))
        val request = ForkRequest("parent/one", ForkMode.RETRY, 42, ModelChoice("a", "m"))
        val result = repo.fork(request)
        assertEquals(control, result.run.id)
        assertNull(result.conversation.controlId)
        val sent = next()
        assertEquals("POST", sent.method)
        assertEquals("/api/chat/conversations/parent%2Fone/forks", sent.path)
        assertEquals(request.json(), wireJson.parseToJsonElement(sent.body.readUtf8()).jsonObject)
        assertEquals(1, server.requestCount)
    }
    @Test fun forkConflictKeepsServerCodeAndIsNotReplayed() = runBlocking {
        server.enqueue(json("""{"error":"Project required","code":"fork_project_required","retryable":false}""", 409))
        try { repo.fork(ForkRequest("parent", ForkMode.EDIT, 41, ModelChoice(), "edited")); fail("Expected conflict") }
        catch (e: ApiException) {
            assertEquals(409, e.status)
            assertEquals("fork_project_required", e.problem.code)
            assertEquals(false, e.problem.retryable)
        }
        assertEquals("POST", next().method)
        assertEquals(1, server.requestCount)
    }
    @Test fun droppedForkPostIsNeverAutomaticallyReplayed() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        try { repo.fork(ForkRequest("parent", ForkMode.RETRY, 42, ModelChoice())); fail("Expected IOException") }
        catch (_: java.io.IOException) { }
        assertEquals(1, server.requestCount)
    }

    @Test fun claudeSessionPagingEncodesOpaqueCursorAndProjectPath() = runBlocking {
        server.enqueue(json("""{"sessions":[{"id":"session","project":{"cwd":"/synthetic/项目","name":"项目"},"title":"你好","state":"maybe_active","provider":"deepseek","model":"deepseek-v4-pro"}],"nextCursor":"next"}"""))
        val page = repo.claudeSessions("page+one=", "/synthetic/项目")
        assertEquals("项目", page.sessions.single().projectName)
        assertTrue(page.sessions.single().maybeActive)
        assertEquals("deepseek", page.sessions.single().provider)
        val req = next()
        assertEquals("page+one=", req.requestUrl!!.queryParameter("cursor"))
        assertEquals("/synthetic/项目", req.requestUrl!!.queryParameter("project"))
        assertEquals("Bearer test-token", req.getHeader("Authorization"))
    }
    @Test fun claudeHistoryUsesExistingToolAndThinkingProjection() = runBlocking {
        server.enqueue(json("""{"session":{"id":"s","state":"idle"},"messages":[{"id":"entry","role":"assistant","blocks":[{"type":"thinking","content":"Collapsed"},{"type":"tool","id":"tool","name":"Read","result":"Done"},{"type":"text","content":"你好"}]}],"messagePage":{"olderCount":3,"nextCursor":"older"}}"""))
        val result = repo.claudeHistory("s")
        assertEquals(3, result.olderCount)
        assertEquals("你好", result.messages.single().text)
        assertEquals(StepStatus.COMPLETE, result.messages.single().blocks.filterIsInstance<ChatBlock.Tool>().single().status)
        assertEquals("/api/claude-sessions/s", next().path)
    }
    @Test fun claudeAdoptionConflictIsStructuredAndNotReplayed() = runBlocking {
        server.enqueue(json("""{"error":"Active on this PC","code":"claude_session_active","retryable":false}""", 409))
        try { repo.adoptClaudeSession("s"); fail("Expected activity conflict") }
        catch (e: ApiException) { assertEquals("claude_session_active", e.problem.code); assertEquals(false, e.problem.retryable) }
        assertEquals("POST", next().method)
        assertEquals(1, server.requestCount)
    }
    @Test fun claudeAdoptionReturnsExistingBindingAndUnknownStateIsConservative() = runBlocking {
        server.enqueue(json("""{"conversationId":"existing","existing":true}"""))
        assertEquals("existing", repo.adoptClaudeSession("s"))
        assertEquals("/api/claude-sessions/s/adopt", next().path)
        assertTrue(ClaudeSession.from(buildJsonObject { put("id", "s"); put("state", "future") }).maybeActive)
    }

    // Real phone regression (adb reverse): the first GET after a POST hit a stale
    // pooled connection and surfaced connection_lost. GETs now recover; POSTs never replay.
    @Test fun staleConnectionAfterPostIsRecoveredForGetOnly() = runBlocking {
        // The server closes the socket after answering the POST while the client pools it.
        server.enqueue(json("{\"id\":\"c1\",\"title\":\"New chat\"}", 201).setSocketPolicy(SocketPolicy.DISCONNECT_AT_END))
        server.enqueue(json("{\"conversations\":[]}"))
        assertEquals("c1", repo.createConversation(ModelChoice("claude", "deepseek-flash")).id)
        assertEquals(emptyList<Conversation>(), repo.conversations())
        assertEquals("POST", next().method)
        assertEquals("GET", next().method)
    }
    @Test fun droppedPostIsNeverResent() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(json("{\"id\":\"c2\"}", 201))
        try { repo.createConversation(ModelChoice("claude", "deepseek-flash")); fail("Expected IOException") }
        catch (e: java.io.IOException) { }
        assertEquals(1, server.requestCount)
    }
    @Test fun everyRequestCarriesNegotiationClientAndToken() = runBlocking {
        server.enqueue(json("{\"agents\":[]}"))
        repo.agents()
        val req = next()
        assertEquals("1.0", req.getHeader("AgentWeb-Protocol-Min"))
        assertEquals("1.0", req.getHeader("AgentWeb-Protocol-Max"))
        assertEquals("android/0.7.0", req.getHeader("AgentWeb-Client"))
        assertEquals("Bearer test-token", req.getHeader("Authorization"))
        assertEquals("close", req.getHeader("Connection"))
    }
    @Test fun protocolRejectionsFromGoldenFixtureAreActionable() = runBlocking {
        val rows = fixture("f-30/http.jsonl").lineSequence().filter(String::isNotBlank).map { wireJson.parseToJsonElement(it).jsonObject }
        val rejected = rows.filter { it.obj("response")?.long("status") in listOf(400L, 409L) }.toList()
        assertEquals(3, rejected.size)
        for (row in rejected) {
            val response = row.obj("response")!!
            server.enqueue(json(response["body"].toString(), response.long("status")!!.toInt()))
            try { repo.agents(); fail("Expected negotiated rejection") } catch (e: ApiException) {
                assertTrue(e.problem.message.contains("protocol"))
                assertEquals(false, e.problem.retryable)
            }
        }
        assertEquals(3, server.requestCount)
    }
    @Test fun eofReattachesWithLastEventIdAndNeverRepeatsPost() = runBlocking {
        server.enqueue(sse(text(1, "你")))
        server.enqueue(sse(text(1, "你") + text(2, "好") + done(3)))
        val snapshots = repo.follow("conversation", control, SendRequest("conversation", "hello", ModelChoice("agent", "model"), control)).toList()
        assertEquals("你好", snapshots.last().turn.text)
        assertTrue(snapshots.last().turn.done)
        assertTrue(snapshots.any { it.connection == Connection.RECONNECTING })
        assertEquals("POST", next().method)
        val resumed = next()
        assertEquals("GET", resumed.method)
        assertEquals("/api/chat/stream/conversation", resumed.path)
        assertEquals("$control:1", resumed.getHeader("Last-Event-ID"))
        assertEquals(2, server.requestCount)
    }
    @Test fun textIsDeliveredBeforeTransportCompletion() = runBlocking {
        server.enqueue(sse(text(1, "early") + done(2)).throttleBody(text(1, "early").toByteArray().size.toLong(), 1, TimeUnit.SECONDS))
        val early = withTimeout(750) { repo.follow("conversation", control).first { it.turn.text == "early" } }
        assertFalse(early.turn.done)
    }
    @Test fun missingDoneKeepsReconnectingUntilDoneWithoutResending() = runBlocking {
        repeat(6) { server.enqueue(sse("")) }
        server.enqueue(sse(done(1)))
        assertTrue(withTimeout(5000) { repo.follow("conversation", control).toList() }.last().turn.done)
        assertEquals(7, server.requestCount)
        repeat(7) { assertEquals("GET", next().method) }
    }
    @Test fun reconnectRetriesServerOutageAndKeepsValidatedCursor() = runBlocking {
        server.enqueue(sse(text(1, "hello")))
        server.enqueue(json("{\"error\":\"Unavailable\"}", 503))
        server.enqueue(sse(done(2)))
        assertEquals("hello", repo.follow("conversation", control, SendRequest("conversation", "hi", ModelChoice(), control)).toList().last().turn.text)
        assertEquals("POST", next().method)
        repeat(2) { val req = next(); assertEquals("GET", req.method); assertEquals("$control:1", req.getHeader("Last-Event-ID")) }
    }
    @Test fun projectCreationUsesWebSentinelAndProjectIdOnlyOnCreate() = runBlocking {
        server.enqueue(json("""{"projects":[{"id":"p","name":"项目","rootLabel":"repo","defaultBranch":"main"}]}"""))
        assertEquals("项目", repo.projects().single().name); assertEquals("/api/chat/projects", next().path)
        server.enqueue(json("""{"project":{"id":"p2","name":"Other"}}""", 201))
        assertEquals("p2", repo.registerProject(" /repos/example ", " Other ").id)
        assertEquals("""{"root":"/repos/example","name":"Other"}""", next().body.readUtf8())
        server.enqueue(json("""{"id":"c","title":"New Chat","project":{"id":"p","name":"项目"}}""", 201))
        assertEquals("p", repo.createConversation(ModelChoice("a", "m"), "p").projectId)
        val body = wireJson.parseToJsonElement(next().body.readUtf8()).jsonObject
        assertEquals("New Chat", body.string("title")); assertEquals("p", body.string("projectId"))
        assertFalse(SendRequest("c", "first", ModelChoice()).json().containsKey("projectId"))
    }
    @Test fun authenticatedMediaDownloadIsBoundedAndDoesNotFollowRedirects() = runBlocking {
        val file = java.io.File.createTempFile("native-media", ".tmp")
        try {
            val origin = server.url("/").toString().trimEnd('/')
            val target = mediaTarget(origin, "/api/files/c/image")!!
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("image"))
            assertEquals("image/png", repo.downloadChatMedia(target, file, 10))
            assertEquals("image", file.readText()); assertEquals("Bearer test-token", next().getHeader("Authorization"))
            server.enqueue(MockResponse().setBody("too-large"))
            try { repo.downloadChatMedia(target, file, 3); fail("Expected limit") } catch (_: java.io.IOException) { }
            assertFalse(file.exists()); next()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://elsewhere.test/file"))
            try { repo.downloadChatMedia(target, file, 10); fail("Expected redirect refusal") } catch (_: ApiException) { }
            assertFalse(file.exists()); assertEquals(3, server.requestCount)
        } finally { file.delete() }
    }
    @Test fun mediaAuthIsStrictlyOriginAndFilePathBound() {
        val origin = "https://agent.example.test"
        assertTrue(mediaTarget(origin, "/api/files/c/file")!!.authenticated)
        assertTrue(mediaTarget(origin, "$origin/api/files/c/file")!!.authenticated)
        assertFalse(mediaTarget(origin, "https://external.test/api/files/file")!!.authenticated)
        assertFalse(mediaTarget(origin, "https://agent.example.test:8443/api/files/file")!!.authenticated)
        assertFalse(mediaTarget(origin, "$origin/api/files/../../secret")!!.authenticated)
        for (url in listOf("http://external.test/image", "file:///tmp/x", "javascript:alert(1)", "https://user:pass@agent.example.test/api/files/x", "//elsewhere.test/x", "/api/secret"))
            assertNull(url, mediaTarget(origin, url))
    }
    @Test fun publicMediaRequestNeverGetsTokenOrProtocolHeaders() = runBlocking {
        val file = java.io.File.createTempFile("native-public", ".tmp")
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("image"))
            // Exercise the unauthenticated transport with a synthetic local response.
            repo.downloadChatMedia(MediaTarget(server.url("/public.png"), false), file, 10)
            val request = next()
            assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("AgentWeb-Client"))
        } finally { file.delete() }
    }
    @Test fun missingJournalAfterPostEofDoesNotProvePostRejection() = runBlocking {
        server.enqueue(sse(""))
        server.enqueue(json("{\"error\":\"No journal\"}", 404))
        try {
            repo.follow("conversation", control, SendRequest("conversation", "work", ModelChoice(), control)).toList()
            fail("Expected missing journal")
        } catch (e: ApiException) { assertFalse(e.directSendRejected) }
        assertEquals("POST", next().method)
        assertEquals("GET", next().method)
    }
    @Test fun immediatePostRejectionIsDistinguishedFromRecoveryFailure() = runBlocking {
        server.enqueue(json("{\"error\":\"Incompatible protocol\",\"code\":\"protocol_major_mismatch\",\"retryable\":false}", 409))
        try {
            repo.follow("conversation", control, SendRequest("conversation", "work", ModelChoice(), control)).toList()
            fail("Expected rejection")
        } catch (e: ApiException) { assertTrue(e.directSendRejected) }
        assertEquals(1, server.requestCount)
    }
    @Test fun changedRunFailsClosedWithoutMixingText() = runBlocking {
        server.enqueue(sse(text(1, "first")))
        server.enqueue(sse("", "other-run"))
        try { repo.follow("conversation", control).toList(); fail("Expected owner mismatch") }
        catch (_: StreamProtocolException) { }
        assertEquals(2, server.requestCount)
    }
    @Test fun stopUsesExpectedControlIdExactly() = runBlocking {
        server.enqueue(json("{\"ok\":true,\"committed\":true,\"status\":\"running\"}"))
        repo.stop("conversation", control)
        val req = next()
        assertEquals("/api/chat/stop", req.path)
        assertEquals(setOf("conversationId", "expectedControlId"), wireJson.parseToJsonElement(req.body.readUtf8()).jsonObject.keys)
    }
    @Test fun queueIdentityAndNullableEffortAreStableAcrossExplicitChecks() = runBlocking {
        repeat(2) { server.enqueue(json("{\"pending\":true}", 202)) }
        val queued = QueueRequest("conversation", "follow up", ModelChoice("agent", "model", null, "plan"))
        repo.queue(queued); repo.queue(queued)
        val first = next().body.readUtf8()
        assertEquals(first, next().body.readUtf8())
        val body = wireJson.parseToJsonElement(first).jsonObject
        assertTrue(body.string("queueId")!!.matches(Regex("[0-9a-f]{32}")))
        assertEquals(JsonNull, body["effort"])
        assertEquals("plan", body.string("permMode"))
    }
    @Test fun resumeUsesExactHeadWithoutAutomaticRetry() = runBlocking {
        server.enqueue(json("{\"error\":\"Changed head\",\"code\":\"queue_head_changed\",\"retryable\":false}", 409))
        try { repo.resume("conversation", control); fail("Expected conflict") } catch (_: ApiException) { }
        val req = next()
        val body = wireJson.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals(control, body.string("expectedHeadItemId"))
        assertEquals(1, server.requestCount)
    }
    @Test fun historyPagingUsesFortyAndExclusiveCursor() = runBlocking {
        server.enqueue(json("""{"conversation":{"id":"conversation","title":"Chat","agent":"agent","model":"model","pinned":1,"active":false,"project":{"name":"Project"}},"messages":[],"queued":[],"queuePaused":true,"messagePage":{"olderCount":41,"nextCursor":"123","limit":40,"compactionCount":0}}"""))
        val detail = repo.detail("conversation", "456")
        assertEquals("/api/chat/conversations/conversation?messageLimit=40&messageCursor=456", next().path)
        assertTrue(detail.conversation.pinned)
        assertTrue(detail.queuePaused)
        assertEquals("Project", detail.conversation.project)
        assertEquals("123", detail.nextCursor)
    }
    @Test fun redirectsDoNotForwardCredentialsOrReplayMutations() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", server.url("/other")))
        try { repo.createConversation(ModelChoice()); fail("Redirect must be refused") } catch (e: ApiException) { assertEquals(307, e.status) }
        assertEquals(1, server.requestCount)
    }
    @Test fun optionalTokenProviderCanOmitAuthorization() = runBlocking {
        server.enqueue(json("{\"conversations\":[]}"))
        HttpAgentRepository(server.url("/").toString().trimEnd('/'), TokenProvider { null }).conversations()
        assertNull(next().getHeader("Authorization"))
    }
    @Test fun debugUrlPolicyIsStrictlyLoopback() {
        assertEquals("http://127.0.0.1:18892", normalizeBaseUrl("http://127.0.0.1:18892/", true))
        assertEquals("http://localhost:18892", normalizeBaseUrl("http://localhost:18892", true))
        listOf("http://10.0.2.2:18892", "http://example.com", "http://localhost.evil.test", "https://user:pass@example.com",
            "https://example.com/chat", "https://example.com?token=value").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { normalizeBaseUrl(value, true) }
        }
        assertThrows(IllegalArgumentException::class.java) { normalizeBaseUrl("http://localhost:18892", false) }
    }
}
