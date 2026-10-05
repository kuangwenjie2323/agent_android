@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)
package com.karewinkcloud.agentweb.client.ui

import androidx.lifecycle.ViewModelStore
import com.karewinkcloud.agentweb.client.core.*
import com.karewinkcloud.agentweb.client.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real HTTP repository + navigation ownership, with synthetic data only. */
class ClaudeSessionsViewModelTest {
    private val main = newSingleThreadContext("claude-session-ui-test")
    private lateinit var server: MockWebServer
    private lateinit var vm: AgentViewModel
    private lateinit var store: ViewModelStore
    private val requests = ConcurrentLinkedQueue<String>()
    @Volatile private var active = false
    @Volatile private var linked = false
    @Volatile private var adoptStatus = 201
    @Volatile private var listStatus = 200
    @Volatile private var adoptionGate: CountDownLatch? = null
    private val id = "11111111-1111-4111-8111-111111111111"
    private fun session() = """{"id":"$id","cwd":"/synthetic/project","project":{"cwd":"/synthetic/project","name":"项目"},"title":"Synthetic question","model":"claude-sonnet-synthetic","provider":"claude","updatedAt":100,"messageCount":4,"state":"${if (active) "maybe_active" else "idle"}","linkedConversationId":${if (linked) "\"chat1\"" else "null"}}"""
    private fun response(body: String, code: Int = 200) = MockResponse().setResponseCode(code)
        .addHeader("Content-Type", "application/json").setBody(body)

    @Before fun setup() = runBlocking {
        Dispatchers.setMain(main)
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add("${request.method} ${request.path}")
                return when {
                    request.path == "/api/agents" -> response("""{"agents":[{"id":"claude","name":"Claude","models":["sonnet"],"model":"sonnet","available":true}]}""")
                    request.path == "/api/chat/conversations" -> response("""{"conversations":[]}""")
                    request.path == "/api/claude-sessions" -> if (listStatus == 200) response("""{"sessions":[${session()}],"nextCursor":"next+page="}""")
                        else response("""{"error":"Owner only","code":"claude_session_forbidden","retryable":false}""", listStatus)
                    request.path!!.startsWith("/api/claude-sessions?") -> response("""{"sessions":[${session()}],"nextCursor":null}""")
                    request.path == "/api/claude-sessions/$id/adopt" -> {
                        adoptionGate?.await(3, TimeUnit.SECONDS)
                        if (adoptStatus == 201) response("""{"conversationId":"chat1","existing":false}""", 201)
                        else response("""{"error":"Active on this PC","code":"claude_session_active","retryable":false}""", adoptStatus)
                    }
                    request.path == "/api/claude-sessions/$id" -> response("""{"session":${session()},"messages":[{"id":"3","role":"user","content":"synthetic third"},{"id":"4","role":"assistant","content":"synthetic fourth"}],"messagePage":{"olderCount":2,"nextCursor":"older+page="}}""")
                    request.path!!.startsWith("/api/claude-sessions/$id?") -> response("""{"session":${session()},"messages":[{"id":"1","role":"user","content":"synthetic first"},{"id":"2","role":"assistant","content":"synthetic second"}],"messagePage":{"olderCount":0,"nextCursor":null}}""")
                    request.path!!.startsWith("/api/chat/conversations/") -> response("""{"conversation":{"id":"chat1","title":"Synthetic","agent":"claude","model":"claude-sonnet-synthetic","active":false},"messages":[],"queued":[],"queuePaused":false}""")
                    else -> response("{}", 404)
                }
            }
        }
        server.start()
        withContext(main) {
            vm = AgentViewModel(HttpAgentRepository(server.url("/").toString().trimEnd('/'), TokenProvider { "synthetic-token" }), ModelChoice("claude", "sonnet")) { }
            store = ViewModelStore().apply { put("vm", vm) }
        }
        await { !it.refreshing }
        Unit
    }
    @After fun cleanup() = runBlocking {
        adoptionGate?.countDown()
        withContext(main) { store.clear() }
        server.shutdown()
        Dispatchers.resetMain()
        main.close()
    }
    private suspend fun act(block: () -> Unit) = withContext(main) { block() }
    private suspend fun await(predicate: (ClientState) -> Boolean) = withTimeout(5000) { vm.state.first(predicate) }
    private suspend fun openPreview(): ClientState {
        act { vm.showClaudeSessions() }
        val list = await { it.claude.sessions.isNotEmpty() && !it.claude.loading }
        act { vm.openClaudeSession(list.claude.sessions.first()) }
        return await { it.claudePreview?.loading == false }
    }

    @Test fun savedSessionListShowsWhenTheServerIsSlowOrDown() = runBlocking {
        val folder = java.nio.file.Files.createTempDirectory("snapshots").toFile()
        try {
            val origin = server.url("/").toString().trimEnd('/')
            val first = withContext(main) { AgentViewModel(HttpAgentRepository(origin, TokenProvider { "synthetic-token" }, listCacheDir = folder), ModelChoice("claude", "sonnet")) { } }
            withContext(main) { first.showClaudeSessions() }
            withTimeout(5000) { first.state.first { it.claude.sessions.isNotEmpty() && !it.claude.loading } }
            listStatus = 503
            val cold = withContext(main) { AgentViewModel(HttpAgentRepository(origin, TokenProvider { "synthetic-token" }, listCacheDir = folder), ModelChoice("claude", "sonnet")) { } }
            withContext(main) { cold.showClaudeSessions() }
            val shown = withTimeout(5000) { cold.state.first { !it.claude.loading && it.claude.error != null } }
            assertEquals(listOf(id), shown.claude.sessions.map { it.id })  // the saved page stays visible beside the error
            val stranger = withContext(main) { AgentViewModel(HttpAgentRepository(origin, TokenProvider { "other-token" }, listCacheDir = folder), ModelChoice("claude", "sonnet")) { } }
            withContext(main) { stranger.showClaudeSessions() }
            assertTrue(withTimeout(5000) { stranger.state.first { !it.claude.loading && it.claude.error != null } }.claude.sessions.isEmpty())
            withContext(main) { ViewModelStore().apply { put("a", first); put("b", cold); put("c", stranger) }.clear() }
        } finally { folder.deleteRecursively() }
    }
    @Test fun reopenedSessionShowsItsHistoryWhileRefreshing() = runBlocking {
        val loaded = openPreview()
        act { vm.back() }
        val reopened = withContext(main) { vm.openClaudeSession(loaded.claudePreview!!.session); vm.state.value }
        assertTrue(reopened.claudePreview!!.loading)
        assertEquals(listOf("3", "4"), reopened.claudePreview!!.messages.map { it.id })
        await { it.claudePreview?.loading == false }
        Unit
    }
    @Test fun activeSessionShowsReadOnlyHistoryAndNeverAdopts() = runBlocking {
        active = true
        val preview = openPreview().claudePreview!!
        assertTrue(preview.session.maybeActive)
        assertFalse(preview.canAdopt)
        assertEquals(2, preview.messages.size)
        act { vm.continueClaudeSession() }
        assertFalse(requests.any { it.startsWith("POST") })
    }
    @Test fun idleAdoptionOpensNormalChatWithoutSendingAMessage() = runBlocking {
        openPreview()
        act { vm.continueClaudeSession(); vm.continueClaudeSession() }
        val state = await { it.chat?.loading == false }
        assertEquals("chat1", state.chat!!.conversation.id)
        assertEquals("sonnet", state.choice.model)
        assertTrue(state.chat.canSend)
        assertEquals(1, requests.count { it.startsWith("POST") })
        assertFalse(requests.any { it.contains("/api/chat/message") })
    }
    @Test fun linkedSessionOpensExistingConversationWithoutHistoryOrAdoption() = runBlocking {
        linked = true
        act { vm.showClaudeSessions() }
        val list = await { it.claude.sessions.isNotEmpty() }
        act { vm.openClaudeSession(list.claude.sessions.first()) }
        await { it.chat?.loading == false }
        assertFalse(requests.any { it.contains("/claude-sessions/$id") })
    }
    @Test fun activeConflictStaysOnPreviewWithExplanation() = runBlocking {
        openPreview()
        adoptStatus = 409
        act { vm.continueClaudeSession() }
        val preview = await { it.claudePreview?.error != null }.claudePreview!!
        assertTrue(preview.session.maybeActive)
        assertFalse(preview.canAdopt)
        assertEquals("claude_session_active", preview.error!!.code)
        assertNull(vm.state.value.chat)
        assertEquals(1, requests.count { it.startsWith("POST") })
    }
    @Test fun historyAndListPaginationPrependAndDeduplicate() = runBlocking {
        openPreview()
        act { vm.olderClaudeHistory() }
        val preview = await { it.claudePreview?.messages?.size == 4 }.claudePreview!!
        assertEquals(listOf("1", "2", "3", "4"), preview.messages.map { it.id })
        assertNull(preview.nextCursor)
        assertTrue(requests.any { it.contains("cursor=older%2Bpage%3D") })
        act { vm.back() }
        await { !it.claude.loading }
        act { vm.refreshClaudeSessions(older = true) }
        val list = await { !it.claude.loading && it.claude.nextCursor == null }.claude
        assertEquals(1, list.sessions.size)
    }
    @Test fun ownerOnlyFailureRendersInSection() = runBlocking {
        listStatus = 403
        act { vm.showClaudeSessions() }
        val state = await { it.claude.error != null }
        assertTrue(state.claudeVisible)
        assertEquals("claude_session_forbidden", state.claude.error!!.code)
        assertTrue(state.claude.sessions.isEmpty())
    }
    @Test fun lateAdoptionCannotHijackBackNavigation() = runBlocking {
        openPreview()
        val gate = CountDownLatch(1)
        adoptionGate = gate
        act { vm.continueClaudeSession() }
        withTimeout(5000) { while (requests.none { it.startsWith("POST") }) delay(10) }
        act { vm.back() }
        gate.countDown()
        await { it.claudePreview == null && !it.claude.loading }
        assertNull(vm.state.value.chat)
        assertTrue(vm.state.value.claudeVisible)
    }
    @Test fun changingServerClearsSessionHistoryAndIgnoresPendingAdoption() = runBlocking {
        openPreview()
        val gate = CountDownLatch(1)
        adoptionGate = gate
        act { vm.continueClaudeSession() }
        withTimeout(5000) { while (requests.none { it.startsWith("POST") }) delay(10) }
        act { vm.changeServer(HttpAgentRepository(server.url("/").toString().trimEnd('/'), TokenProvider { "new-synthetic-token" }), ModelChoice()) }
        gate.countDown()
        val state = await { !it.refreshing }
        assertNull(state.claudePreview)
        assertNull(state.chat)
        assertFalse(state.claudeVisible)
        assertTrue(state.claude.sessions.isEmpty())
    }
}
