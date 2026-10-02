@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.karewinkcloud.agentweb.client.ui

import androidx.lifecycle.ViewModelStore
import com.karewinkcloud.agentweb.client.core.*
import com.karewinkcloud.agentweb.client.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import java.io.IOException

class AgentViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var store: ViewModelStore
    private lateinit var repo: FakeRepository
    private lateinit var vm: AgentViewModel
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        repo = FakeRepository()
        vm = AgentViewModel(repo, ModelChoice("a", "model")) { }
        store = ViewModelStore().apply { put("vm", vm) }
    }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }

    private fun modelTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try { block() } finally { store.clear() }
    }

    @Test fun staleDetailCannotReplaceNewSelection() = modelTest {
        runCurrent()
        val delayed = CompletableDeferred<ConversationDetail>()
        repo.getDetail = { id, _ -> if (id == "old") withContext(NonCancellable) { delayed.await() } else detail(id) }
        vm.open(conversation("old")); runCurrent()
        vm.open(conversation("new")); runCurrent()
        delayed.complete(detail("old")); runCurrent()
        assertEquals("new", vm.state.value.chat!!.conversation.id)
        assertFalse(vm.state.value.chat!!.loading)
    }
    @Test fun draftsBelongToConversations() = modelTest {
        vm.open(conversation("one")); runCurrent(); vm.draft("first draft")
        vm.open(conversation("two")); runCurrent(); vm.draft("second draft")
        vm.open(conversation("one")); runCurrent()
        assertEquals("first draft", vm.state.value.chat!!.draft)
    }
    @Test fun browserFailureKeepsChatDraftAndRequestErrorAndCanBeDismissed() = modelTest {
        vm.open(conversation("one")); runCurrent(); vm.draft("unsent draft")
        vm.attachmentFailure(vm.attachmentOwner(), "Attachment could not be read")
        val before = vm.state.value.chat!!
        vm.browserUnavailable()
        val failed = vm.state.value.chat!!
        assertEquals("Could not open a browser. Install or enable a browser and try again.", failed.browserError!!.message)
        assertEquals(before.error, failed.error)
        assertEquals(before.draft, failed.draft)
        assertEquals(before.messages, failed.messages)
        assertEquals(0, repo.sendCalls)
        vm.dismissBrowserError()
        assertNull(vm.state.value.chat!!.browserError)
        assertEquals(before, vm.state.value.chat)
    }
    @Test fun browserFailureDoesNotFollowNavigationToAnotherChat() = modelTest {
        vm.open(conversation("one")); runCurrent(); vm.browserUnavailable()
        vm.open(conversation("two")); runCurrent()
        assertNull(vm.state.value.chat!!.browserError)
        vm.back(); runCurrent(); vm.browserUnavailable()
        assertNull(vm.state.value.chat)
        assertNull(vm.state.value.error)
    }
    @Test fun attachmentsKeepDraftOwnershipAndStalePickerCannotCrossNavigation() = modelTest {
        vm.open(conversation("one")); runCurrent()
        val file = encodeAttachment("a.txt", "text/plain", byteArrayOf(1), AttachmentKind.FILE)
        val owner = vm.attachmentOwner()
        vm.addAttachments(owner, listOf(file))
        vm.open(conversation("two")); runCurrent()
        vm.addAttachments(owner, listOf(file.copy(id = "late")))
        assertTrue(vm.state.value.chat!!.attachments.isEmpty())
        vm.open(conversation("one")); runCurrent()
        assertEquals(listOf(file), vm.state.value.chat!!.attachments)
        vm.removeAttachment(file.id); assertTrue(vm.state.value.chat!!.attachments.isEmpty())
    }
    @Test fun imageCapabilityBlocksImagesButAllowsFilesAndAttachmentOnlySends() = modelTest {
        vm.open(conversation("one")); runCurrent()
        val image = encodeAttachment("a.png", "image/png", byteArrayOf(1), AttachmentKind.IMAGE)
        vm.addAttachments(vm.attachmentOwner(), listOf(image)); assertTrue(vm.state.value.chat!!.attachments.isEmpty())
        assertTrue(vm.state.value.chat!!.error!!.message.contains("不支持图片"))
        val file = image.copy(kind = AttachmentKind.FILE)
        vm.addAttachments(vm.attachmentOwner(), listOf(file)); vm.send(); runCurrent()
        assertEquals(1, repo.sendCalls)
        assertTrue(vm.state.value.chat!!.attachments.isEmpty())
        assertEquals(file.name, vm.state.value.chat!!.messages.last().blocks.filterIsInstance<ChatBlock.Media>().single().items.single().name)
    }
    @Test fun pickerReturnSurvivesForegroundReloadAndReadingKeepsSendDisabled() = modelTest {
        vm.open(conversation("one")); runCurrent()
        val owner = vm.attachmentOwner()
        vm.onForeground(); runCurrent()
        val result = CompletableDeferred<List<Attachment>>()
        vm.readAttachments(owner) { result.await() }; runCurrent()
        assertTrue(vm.state.value.chat!!.attachmentLoading); assertFalse(vm.state.value.chat!!.canSend)
        val file = encodeAttachment("a.txt", "text/plain", byteArrayOf(1), AttachmentKind.FILE)
        result.complete(listOf(file)); runCurrent()
        assertEquals(listOf(file), vm.state.value.chat!!.attachments)
        assertFalse(vm.state.value.chat!!.attachmentLoading)
    }
    @Test fun pickerReceiptFromOldProcessCannotMatchAnotherViewModel() = modelTest {
        vm.open(conversation("one")); runCurrent()
        val oldOwner = vm.attachmentOwner()
        val fresh = AgentViewModel(FakeRepository(), ModelChoice("a", "model")) { }
        val freshStore = ViewModelStore().apply { put("fresh", fresh) }
        try {
            fresh.open(conversation("two")); runCurrent()
            assertNotEquals(oldOwner, fresh.attachmentOwner())
            val file = encodeAttachment("a.txt", "text/plain", byteArrayOf(1), AttachmentKind.FILE)
            assertFalse(fresh.addAttachments(oldOwner, listOf(file)))
            assertTrue(fresh.state.value.chat!!.attachments.isEmpty())
        } finally { freshStore.clear() }
    }
    @Test fun rejectedAttachmentSendRestoresExactDraftWhileUnknownQueueKeepsSameBody() = modelTest {
        vm.open(conversation("one")); runCurrent()
        val file = encodeAttachment("a.txt", "text/plain", byteArrayOf(1), AttachmentKind.FILE)
        repo.followFlow = { _, _, _, _ -> flow { throw ApiException(400, ClientError("Rejected"), directSendRejected = true) } }
        vm.addAttachments(vm.attachmentOwner(), listOf(file)); vm.send(); runCurrent()
        assertEquals(listOf(file), vm.state.value.chat!!.attachments)
        repo.getDetail = { id, _ -> detail(id, true) }
        repo.followFlow = { _, _, _, _ -> flow { awaitCancellation() } }
        vm.reload(); runCurrent()
        repo.admit = { throw IOException("Lost ack") }
        vm.queue(); runCurrent(); val first = repo.queueCalls.single()
        vm.draft("later"); vm.queue(); runCurrent()
        assertEquals(first.json(), repo.queueCalls.last().json())
        assertEquals("later", vm.state.value.chat!!.draft)
    }
    @Test fun projectSelectionCreatesNewBlankWithDraftAndNeverRetargetsExistingHistory() = modelTest {
        repo.projectList = listOf(Project("p", "项目")); runCurrent()
        vm.open(conversation("one")); runCurrent(); vm.draft("carry me")
        repo.create = { conversation("project-chat").copy(projectId = "p", project = "项目") }
        repo.getDetail = { id, _ -> detail(id).copy(conversation = conversation(id).copy(projectId = if (id == "project-chat") "p" else null)) }
        vm.selectProject("p"); runCurrent()
        assertEquals("p", repo.createdProject)
        assertEquals("project-chat", vm.state.value.chat!!.conversation.id)
        assertEquals("carry me", vm.state.value.chat!!.draft)
        repo.getDetail = { id, _ -> detail(id).copy(messages = listOf(message("1"))) }
        vm.reload(); runCurrent(); vm.selectProject(null); runCurrent()
        assertEquals(1, repo.createCalls)
    }
    @Test fun newChatKeepsSelectedEffortAndPermissionsForFirstSend() = modelTest {
        repo.models = listOf("model", "next")
        val choice = ModelChoice("a", "next", "high", "plan")
        val blank = conversation("new").copy(choice = ModelChoice("a", "next"))
        repo.create = { blank }
        repo.getDetail = { _, _ -> detail("new").copy(conversation = blank) }
        runCurrent()
        vm.choose(choice)
        vm.newChat(); runCurrent()
        assertEquals(choice, vm.state.value.choice)
        vm.draft("hello"); vm.send(); runCurrent()
        assertEquals(choice, repo.followCalls.last { it.third != null }.third!!.choice)
    }
    @Test fun newChatIsSingleFlightAndCannotHijackLaterNavigation() = modelTest {
        runCurrent()
        val pending = CompletableDeferred<Conversation>()
        repo.create = { pending.await() }
        vm.newChat(); vm.newChat(); runCurrent()
        assertEquals(1, repo.createCalls)
        vm.open(conversation("selected")); runCurrent()
        pending.complete(conversation("created")); runCurrent()
        assertEquals("selected", vm.state.value.chat!!.conversation.id)
        assertEquals("created", vm.state.value.conversations.first().id)
    }
    @Test fun ambiguousDirectSendStaysLockedEvenAfterIdleHistoryAndMissingJournal() = modelTest {
        vm.open(conversation("one")); runCurrent()
        repo.followFlow = { _, _, send, _ -> flow {
            if (send != null) throw IOException("network loss")
            throw ApiException(404, ClientError("No journal"))
        } }
        vm.draft("do work"); vm.send(); runCurrent()
        assertFalse(vm.state.value.chat!!.canSend)
        vm.reload(); runCurrent()
        assertFalse(vm.state.value.chat!!.canSend)
        assertEquals(1, repo.sendCalls)
        assertEquals("", vm.state.value.chat!!.draft)
    }
    @Test fun preStreamNegotiatedRejectionRestoresDraft() = modelTest {
        vm.open(conversation("one")); runCurrent()
        repo.followFlow = { _, _, _, _ -> flow { throw ApiException(409, ClientError("Incompatible protocol"), directSendRejected = true) } }
        vm.draft("keep this"); vm.send(); runCurrent()
        assertEquals("keep this", vm.state.value.chat!!.draft)
        assertTrue(vm.state.value.chat!!.canSend)
        assertTrue(vm.state.value.chat!!.messages.isEmpty())
    }
    @Test fun queueCheckReusesEntireRequestAndNeverClearsNewDraft() = modelTest {
        repo.getDetail = { id, _ -> detail(id, running = true) }
        vm.open(conversation("one", true)); runCurrent()
        repo.admit = { if (repo.queueCalls.size == 1) throw IOException("unknown") else buildJsonObject { put("pending", false) } }
        vm.draft("queued text"); vm.queue(); runCurrent()
        assertTrue(vm.state.value.chat!!.queueUncertain)
        vm.draft("new draft"); vm.queue(); runCurrent()
        assertEquals(2, repo.queueCalls.size)
        assertEquals(repo.queueCalls[0], repo.queueCalls[1])
        assertEquals("new draft", vm.state.value.chat!!.draft)
        assertNull(vm.state.value.chat!!.pendingQueue)
        assertTrue(vm.state.value.chat!!.queued.isEmpty()) // pending:false is not a phantom chip
    }
    @Test fun pausedQueueDoesNotResumeOnLoadOrForegroundAndUsesExactHeadOnTap() = modelTest {
        repo.getDetail = { id, _ -> detail(id).copy(queuePaused = true, queued = listOf(QueueItem(CONTROL, "later"))) }
        repo.resumeCall = { _, _ -> throw ApiException(409, ClientError("Changed head", "queue_head_changed", false)) }
        vm.open(conversation("one")); runCurrent(); vm.onForeground(); runCurrent()
        assertTrue(repo.resumeCalls.isEmpty())
        vm.resumeQueue(); runCurrent()
        assertEquals(listOf("one" to CONTROL), repo.resumeCalls)
        assertTrue(vm.state.value.chat!!.queuePaused)
        assertEquals("queue_head_changed", vm.state.value.chat!!.error!!.code)
    }
    @Test fun stopUsesObservedOwnerAndNeverStopsOnNavigation() = modelTest {
        repo.getDetail = { id, _ -> detail(id, running = true) }
        vm.open(conversation("one", true)); runCurrent()
        vm.back(); runCurrent()
        assertTrue(repo.stopCalls.isEmpty())
        vm.open(conversation("one", true)); runCurrent(); vm.stop(); runCurrent()
        assertEquals(listOf("one" to CONTROL), repo.stopCalls)
    }
    @Test fun olderPagesArePrependedAndDeduplicated() = modelTest {
        repo.getDetail = { id, cursor ->
            if (cursor == null) detail(id).copy(messages = listOf(message("3"), message("4")), olderCount = 2, nextCursor = "3")
            else detail(id).copy(messages = listOf(message("1"), message("2"), message("3")))
        }
        vm.open(conversation("one")); runCurrent(); vm.loadOlder(); runCurrent()
        assertEquals(listOf("1", "2", "3", "4"), vm.state.value.chat!!.messages.map { it.id })
        assertNull(vm.state.value.chat!!.nextCursor)
    }
    @Test fun desktopControlledConversationsCannotUseOrdinaryMutations() = modelTest {
        repo.getDetail = { id, _ -> detail(id, running = true).let { it.copy(conversation = it.conversation.copy(nativeControl = true)) } }
        vm.open(conversation("one", true)); runCurrent()
        vm.draft("hello"); vm.send(); vm.queue(); vm.stop(); vm.resumeQueue(); runCurrent()
        assertEquals(0, repo.sendCalls)
        assertTrue(repo.stopCalls.isEmpty())
        assertTrue(repo.queueCalls.isEmpty())
    }

    @Test fun unauthorizedRepositoryClearsScreenAndStopsForegroundRetryAndMutations() = modelTest {
        runCurrent()
        repo.getDetail = { _, _ ->
            repo.authenticationRequired.value = true
            throw ApiException(401, signInRequiredError)
        }
        vm.open(conversation("one")); runCurrent()
        assertTrue(vm.state.value.signInRequired)
        assertNull(vm.state.value.chat)
        assertEquals("sign_in_required", vm.state.value.error!!.code)
        val requests = repo.readCalls
        repeat(3) { vm.onForeground(); vm.refresh(); vm.open(conversation("one")); vm.showClaudeSessions(); vm.newChat() }
        runCurrent()
        assertEquals(requests, repo.readCalls)
        assertEquals(0, repo.createCalls); assertEquals(0, repo.sendCalls)
        assertTrue(repo.stopCalls.isEmpty())
    }

    @Test fun freshSignInReplaces401LatchWithoutReplayingOldWork() = modelTest {
        vm.open(conversation("one")); runCurrent(); vm.draft("not sent")
        repo.authenticationRequired.value = true; runCurrent()
        val fresh = FakeRepository()
        vm.changeServer(fresh, ModelChoice("a", "model")); runCurrent()
        assertFalse(vm.state.value.signInRequired)
        assertNull(vm.state.value.chat)
        assertEquals(0, fresh.sendCalls); assertTrue(fresh.queueCalls.isEmpty())
        assertTrue(repo.stopCalls.isEmpty())
    }

    @Test fun signedOutServerDoesNotMakeInitialOrForegroundRequests() = modelTest {
        runCurrent()
        val fresh = FakeRepository()
        vm.changeServer(fresh, ModelChoice(), signInRequired = true)
        vm.onForeground(); vm.newChat(); runCurrent()
        assertEquals(0, fresh.readCalls); assertEquals(0, fresh.createCalls)
    }

    @Test fun listSearchIncludesPreviewProjectAndModelWithoutDeletingSourceRows() = modelTest {
        runCurrent()
        repo.list = listOf(conversation("one").copy(title = "中文任务", preview = "Mountain scene", project = "设计", messageCount = 2),
            conversation("two").copy(title = "Different", choice = ModelChoice("claude", "Sonnet"), messageCount = 2))
        vm.refresh(); runCurrent(); vm.search("MOUNTAIN")
        assertEquals(listOf("one"), vm.state.value.sections.flatMap { it.conversations }.map { it.id })
        vm.search("设计"); assertEquals("one", vm.state.value.sections.single().conversations.single().id)
        vm.search("sonnet"); assertEquals("two", vm.state.value.sections.single().conversations.single().id)
        assertEquals(2, vm.state.value.conversations.size)
    }
    @Test fun listHidesOnlyConfirmedUnusedChatsAndFilterRestoresThem() = modelTest {
        runCurrent()
        repo.list = listOf(conversation("empty").copy(title = "New chat", messageCount = 0), conversation("running", true).copy(title = "New chat", messageCount = 0),
            conversation("pinned").copy(title = "New chat", pinned = true, messageCount = 0), conversation("unknown").copy(title = "New chat"),
            conversation("named").copy(title = "Keep my project plan", messageCount = 0))
        repo.getDetail = { _, _ -> throw IOException("offline") }
        vm.refresh(); runCurrent()
        assertEquals(setOf("running", "pinned", "unknown", "named"), vm.state.value.sections.flatMap { it.conversations }.map { it.id }.toSet())
        vm.showEmpty(true); assertEquals(5, vm.state.value.sections.sumOf { it.conversations.size })
        assertEquals(5, vm.state.value.conversations.size)
    }
    @Test fun listGroupsByApprovedThreeDatesAndPreservesPins() = modelTest {
        runCurrent()
        val today = java.time.LocalDate.now()
        fun date(days: Long) = today.minusDays(days).atStartOfDay(java.time.ZoneId.systemDefault()).toEpochSecond()
        repo.list = listOf(conversation("old").copy(updatedAt = date(20), messageCount = 1),
            conversation("week").copy(updatedAt = date(4), messageCount = 1), conversation("today").copy(updatedAt = date(0), messageCount = 1),
            conversation("pin").copy(pinned = true, updatedAt = date(30), messageCount = 1), conversation("yesterday").copy(updatedAt = date(1), messageCount = 1))
        vm.refresh(); runCurrent()
        assertEquals(listOf(ConversationGroup.TODAY, ConversationGroup.YESTERDAY, ConversationGroup.OLDER), vm.state.value.sections.map { it.group })
        assertEquals(listOf("today", "yesterday", "week", "old", "pin"), vm.state.value.sections.flatMap { it.conversations }.map { it.id })
    }
    @Test fun previewDerivesBlankTitleAndRetainsUnknownOnFailedRead() = modelTest {
        runCurrent()
        repo.list = listOf(conversation("one").copy(title = "New chat"), conversation("two").copy(title = "New chat"))
        repo.getDetail = { id, _ -> if (id == "two") throw IOException("offline") else detail(id).copy(
            messages = listOf(ChatMessage("1", "user", listOf(ChatBlock.Text("第一个问题"))))) }
        vm.refresh(); runCurrent()
        assertEquals("第一个问题", vm.state.value.conversations.first().title)
        assertEquals("第一个问题", vm.state.value.conversations.first().preview)
        assertNull(vm.state.value.conversations.last().messageCount)
        assertEquals(2, vm.state.value.sections.sumOf { it.conversations.size })
    }
    @Test fun replyKeepsCapturedModelWhenPickerChangesMidTurn() = modelTest {
        runCurrent()
        repo.models = listOf("model", "next")
        vm.refresh(); runCurrent(); vm.open(conversation("one")); runCurrent()
        vm.draft("say hello"); vm.send(); runCurrent()
        vm.choose(ModelChoice("a", "next"))
        assertEquals("next", vm.state.value.choice.model)
        assertEquals("model", vm.state.value.chat?.liveModel)
    }
    @Test fun accountChangeClearsListFiltersAndPreviews() = modelTest {
        runCurrent(); vm.search("secret draft"); vm.showEmpty(true)
        vm.changeServer(FakeRepository(), ModelChoice(), signInRequired = true); runCurrent()
        assertEquals("", vm.state.value.search); assertFalse(vm.state.value.showEmpty); assertTrue(vm.state.value.sections.isEmpty())
    }

    @Test fun tabsPersistAcrossConnectionsAndReturnFromClaudeChat() = modelTest {
        runCurrent()
        for (tab in AppTab.entries) { vm.selectTab(tab); runCurrent(); assertEquals(tab, vm.state.value.tab) }
        vm.selectTab(AppTab.CLAUDE); runCurrent()
        vm.open(conversation("one")); runCurrent(); vm.back(); runCurrent()
        assertEquals(AppTab.CLAUDE, vm.state.value.tab)
        vm.selectTab(AppTab.SETTINGS)
        vm.changeServer(FakeRepository(), ModelChoice(), signInRequired = true); runCurrent()
        assertEquals(AppTab.SETTINGS, vm.state.value.tab)
        vm.selectTab(AppTab.CREATE); assertEquals(AppTab.CREATE, vm.state.value.tab)
    }
    @Test fun tabSelectionCancelsLocalFollowerAndRetainsDraftWithoutStoppingWork() = modelTest {
        vm.open(conversation("one")); runCurrent(); vm.draft("saved draft")
        vm.selectTab(AppTab.CREATE); runCurrent()
        assertNull(vm.state.value.chat); assertTrue(repo.stopCalls.isEmpty())
        vm.open(conversation("one")); runCurrent(); assertEquals("saved draft", vm.state.value.chat?.draft)
    }
    @Test fun screenProjectionDoesNotChangeForFiveThousandTextEvents() = modelTest {
        val base = ClientState(chat = ChatState(conversation("one"), live = TurnState("one", CONTROL, startedAt = 1)))
        val before = screenSnapshot(base)
        var text = ""
        repeat(5000) { i ->
            text += "字 "
            assertEquals(before, screenSnapshot(base.copy(chat = base.chat!!.copy(live = base.chat.live!!.copy(
                blocks = listOf(ChatBlock.Text(text)), lastSequence = i.toLong() + 1)))))
        }
    }
    @Test fun burstPresentationKeepsFullTruthForReconnectAndDrainsBeforeHistory() = modelTest {
        vm.open(conversation("one")); runCurrent()
        val text = "A".repeat(5000)
        repo.followFlow = { _, _, _, _ -> flow {
            emit(TurnSnapshot(TurnState("one", CONTROL, 1, listOf(ChatBlock.Text(text))), Connection.LIVE))
            awaitCancellation()
        } }
        vm.draft("test"); vm.send(); runCurrent(); advanceTimeBy(32); runCurrent()
        assertEquals(text, vm.state.value.chat!!.live!!.text)
        assertTrue(vm.liveTurn.value!!.text.length in 1 until text.length)
        advanceTimeBy(900); runCurrent(); assertEquals(text, vm.liveTurn.value!!.text)
    }

    @Test fun screenSubscriptionSurvivesAuthenticationAndServerChanges() = modelTest {
        val observed = mutableListOf<ClientState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.screenState.toList(observed) }
        runCurrent(); vm.selectTab(AppTab.CLAUDE); runCurrent()
        repo.authenticationRequired.value = true; runCurrent()
        assertTrue(observed.last().signInRequired)
        vm.changeServer(FakeRepository(), ModelChoice("a", "model")); runCurrent()
        assertFalse(observed.last().signInRequired)
        assertEquals(AppTab.CLAUDE, observed.last().tab); assertTrue(observed.last().claudeVisible)
        vm.selectTab(AppTab.SETTINGS); runCurrent()
        assertEquals(AppTab.SETTINGS, observed.last().tab)
    }

    @Test fun disposedActivityFrameClockDoesNotCancelTheFollower() = modelTest {
        vm.frameClock = object : androidx.compose.runtime.MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = throw CancellationException("disposed clock")
        }
        vm.open(conversation("one")); runCurrent()
        repo.followFlow = { _, _, _, _ -> flow {
            emit(TurnSnapshot(TurnState("one", CONTROL, 1, listOf(ChatBlock.Text("A".repeat(1000)))), Connection.LIVE))
            awaitCancellation()
        } }
        vm.draft("test"); vm.send(); runCurrent(); advanceTimeBy(1000); runCurrent()
        assertEquals(1000, vm.liveTurn.value!!.text.length); assertNull(vm.state.value.chat!!.error)
    }

    @Test fun terminalBurstLocksComposerUntilRevealCompletes() = modelTest {
        vm.open(conversation("one")); runCurrent()
        repo.followFlow = { _, _, _, _ -> flow {
            emit(TurnSnapshot(TurnState("one", CONTROL, 1, listOf(ChatBlock.Text("A".repeat(5000))), done = true), Connection.COMPLETE))
            awaitCancellation()
        } }
        vm.draft("test"); vm.send(); runCurrent(); advanceTimeBy(32); runCurrent()
        assertTrue(vm.state.value.chat!!.live!!.done)
        assertTrue(vm.state.value.chat!!.revealing); assertFalse(vm.state.value.chat!!.canSend)
        advanceTimeBy(900); runCurrent()
        assertFalse(vm.state.value.chat!!.revealing); assertTrue(vm.state.value.chat!!.canSend)
    }

    private fun forkHistory(id: String) = detail(id).copy(messages = if (id == "parent") listOf(
        ChatMessage("1", "user", listOf(ChatBlock.Text("original"))), message("2")) else listOf(
        ChatMessage("11", "user", listOf(ChatBlock.Text("original"))), ChatMessage("12", "assistant", listOf(ChatBlock.Text("new answer")))))

    @Test fun forkUsesLatestAnchorsFollowsChildGetAndPreservesParentDraft() = modelTest {
        repo.getDetail = { id, _ -> forkHistory(id) }
        vm.open(conversation("parent")); runCurrent(); vm.draft("keep parent draft")
        vm.retry("1"); vm.retry("pending-2"); vm.retry("999"); runCurrent()
        assertTrue(repo.forkCalls.isEmpty())
        vm.retry("2"); vm.retry("2"); runCurrent()
        assertEquals(1, repo.forkCalls.size)
        assertEquals(2L, repo.forkCalls.single().messageId)
        assertEquals(ForkMode.RETRY, repo.forkCalls.single().mode)
        assertEquals("child", vm.state.value.chat!!.conversation.id)
        assertEquals(listOf("11"), vm.state.value.chat!!.messages.map { it.id })
        assertEquals(Triple("child", CONTROL, null), repo.followCalls.last())
        assertEquals(0, repo.sendCalls)
        assertNotNull(vm.state.value.chat!!.live!!.startedAt)
        vm.open(conversation("parent")); runCurrent()
        assertEquals("keep parent draft", vm.state.value.chat!!.draft)
    }
    @Test fun editUsesUserIdTrimmedTextAndRejectsWhitespace() = modelTest {
        repo.getDetail = { id, _ -> forkHistory(id) }
        vm.open(conversation("parent")); runCurrent()
        vm.edit("1", "  "); vm.edit("2", "new text"); runCurrent()
        assertTrue(repo.forkCalls.isEmpty())
        vm.edit("1", "  revised question  "); runCurrent()
        assertEquals(ForkMode.EDIT, repo.forkCalls.single().mode)
        assertEquals("revised question", repo.forkCalls.single().message)
        assertEquals(1L, repo.forkCalls.single().messageId)
    }
    @Test fun forkAdmissionFencesReloadAndStaleResultCannotNavigate() = modelTest {
        repo.getDetail = { id, _ -> forkHistory(id) }
        val pending = CompletableDeferred<ForkResponse>()
        repo.forkAction = { withContext(NonCancellable) { pending.await() } }
        vm.open(conversation("parent")); runCurrent()
        vm.retry("2"); runCurrent(); vm.reload(); vm.onForeground(); runCurrent()
        assertTrue(vm.state.value.chat!!.controlBusy)
        assertEquals(1, repo.forkCalls.size)
        vm.open(conversation("selected")); runCurrent()
        pending.complete(forkResponse()); runCurrent()
        assertEquals("selected", vm.state.value.chat!!.conversation.id)
        assertFalse(vm.state.value.chat!!.controlBusy)
        assertTrue(repo.followCalls.isEmpty())
    }
    @Test fun forkResultFromOldServerCannotOpenChildOnNewServer() = modelTest {
        repo.getDetail = { id, _ -> forkHistory(id) }
        val pending = CompletableDeferred<ForkResponse>()
        repo.forkAction = { withContext(NonCancellable) { pending.await() } }
        vm.open(conversation("parent")); runCurrent(); vm.retry("2"); runCurrent()
        vm.changeServer(FakeRepository(), ModelChoice("a", "model")); runCurrent()
        pending.complete(forkResponse()); runCurrent()
        assertNull(vm.state.value.chat)
        assertFalse(vm.state.value.conversations.any { it.id == "child" })
    }
    @Test fun ambiguousForkRequiresExplicitRetryAndReusesRequestUuid() = modelTest {
        repo.getDetail = { id, _ -> forkHistory(id) }
        repo.forkAction = { throw IOException("Lost acknowledgement") }
        vm.open(conversation("parent")); runCurrent(); vm.retry("2"); runCurrent()
        assertFalse(vm.state.value.chat!!.controlBusy)
        assertEquals("connection_lost", vm.state.value.chat!!.error!!.code)
        vm.onForeground(); runCurrent()
        assertEquals(1, repo.forkCalls.size)
        vm.retry("2"); runCurrent()
        assertEquals(2, repo.forkCalls.size)
        assertEquals(repo.forkCalls.first(), repo.forkCalls.last())
    }
    @Test fun fork409RemainsVisibleAndClearsBusyWithoutChangingConversation() = modelTest {
        repo.getDetail = { id, _ -> forkHistory(id) }
        repo.forkAction = { throw ApiException(409, ClientError("Project required", "fork_project_required", false)) }
        vm.open(conversation("parent")); runCurrent(); vm.retry("2"); runCurrent()
        assertEquals("parent", vm.state.value.chat!!.conversation.id)
        assertEquals("fork_project_required", vm.state.value.chat!!.error!!.code)
        assertFalse(vm.state.value.chat!!.controlBusy)
        assertTrue(vm.state.value.chat!!.canFork)
    }
    @Test fun forkGuardsRejectQueueNativeLiveAndLoadingStates() = modelTest {
        val base = ChatState(conversation("parent"), loading = false)
        assertTrue(base.canFork)
        assertFalse(base.copy(loading = true).canFork)
        assertFalse(base.copy(loadingOlder = true).canFork)
        assertFalse(base.copy(revealing = true).canFork)
        assertFalse(base.copy(controlBusy = true).canFork)
        assertFalse(base.copy(queued = listOf(QueueItem("q", "queued"))).canFork)
        assertFalse(base.copy(pendingQueue = QueueRequest("parent", "queued", ModelChoice())).canFork)
        assertFalse(base.copy(conversation = base.conversation.copy(nativeControl = true)).canFork)
        assertFalse(base.copy(live = TurnState("parent", CONTROL)).canFork)
        repo.getDetail = { id, _ -> forkHistory(id).copy(queued = listOf(QueueItem("q", "queued")), queuePaused = true) }
        vm.open(conversation("parent")); runCurrent(); vm.retry("2"); vm.edit("1", "edited"); runCurrent()
        assertTrue(repo.forkCalls.isEmpty())
    }
    @Test fun completedForkKeepsUsageAndLineageAcrossHistoryReloadAndParentNavigation() = modelTest {
        repo.getDetail = { id, _ -> forkHistory(id) }
        repo.followFlow = { id, run, _, previous -> flow {
            emit(TurnSnapshot(previous!!.copy(blocks = listOf(ChatBlock.Text("new answer")), done = true,
                usage = TurnUsage(19524, 752, durationMs = 4744)), Connection.COMPLETE))
        } }
        vm.open(conversation("parent")); runCurrent(); vm.retry("2"); advanceUntilIdle()
        assertEquals(TurnUsage(19524, 752, durationMs = 4744), vm.state.value.chat!!.messages.last().usage)
        assertEquals("Parent title", vm.state.value.chat!!.conversation.lineage!!.parentTitle)
        vm.reload(); advanceUntilIdle()
        assertEquals(19524L, vm.state.value.chat!!.messages.last().usage!!.inputTokens)
        assertEquals("Parent title", vm.state.value.chat!!.conversation.lineage!!.parentTitle)
        vm.openParent(); runCurrent()
        assertEquals("parent", vm.state.value.chat!!.conversation.id)
    }
    @Test fun coldChildResolvesMissingParentTitleAndTimerStartsBeforeAnyStreamEvent() = modelTest {
        repo.getDetail = { id, _ -> detail(id).copy(conversation = if (id == "child") conversation(id, true).copy(
            lineage = ConversationLineage("parent", "retry")) else conversation(id).copy(title = "Saved parent")) }
        vm.open(conversation("child", true)); runCurrent()
        assertEquals("Saved parent", vm.state.value.chat!!.conversation.lineage!!.parentTitle)
        val started = vm.state.value.chat!!.live!!.startedAt
        assertNotNull(started)
        assertEquals(started, repo.previousTurns.last()!!.startedAt)
        vm.reload(); runCurrent()
        assertEquals(started, vm.state.value.chat!!.live!!.startedAt)
    }

    private class FakeRepository : AgentRepository {
        override val authenticationRequired = MutableStateFlow(false)
        var readCalls = 0
        var list = emptyList<Conversation>()
        var models = listOf("model")
        var projectList = emptyList<Project>()
        var createdProject: String? = null
        override suspend fun projects() = projectList
        override suspend fun createConversation(choice: ModelChoice, projectId: String?): Conversation {
            createdProject = projectId; return createConversation(choice)
        }
        override suspend fun claudeSessions(cursor: String?, project: String?) = ClaudeSessionPage(emptyList(), null)
        override suspend fun claudeHistory(id: String, cursor: String?): ClaudeSessionHistory = error("Unused")
        override suspend fun adoptClaudeSession(id: String): String = error("Unused")
        var getDetail: suspend (String, String?) -> ConversationDetail = { id, _ -> AgentViewModelTest.detail(id) }
        var create: suspend () -> Conversation = { conversation("new") }
        var followFlow: (String, String?, SendRequest?, TurnState?) -> Flow<TurnSnapshot> = { _, _, _, _ -> flow { awaitCancellation() } }
        var admit: suspend (QueueRequest) -> JsonObject = { buildJsonObject { put("pending", true) } }
        var resumeCall: suspend (String, String) -> JsonObject = { _, _ -> buildJsonObject { put("started", true) } }
        var createCalls = 0
        var sendCalls = 0
        var forkAction: suspend (ForkRequest) -> ForkResponse = { forkResponse() }
        val forkCalls = mutableListOf<ForkRequest>()
        val followCalls = mutableListOf<Triple<String, String?, SendRequest?>>()
        val previousTurns = mutableListOf<TurnState?>()
        override suspend fun fork(request: ForkRequest): ForkResponse { forkCalls += request; return forkAction(request) }
        val stopCalls = mutableListOf<Pair<String, String>>()
        val queueCalls = mutableListOf<QueueRequest>()
        val resumeCalls = mutableListOf<Pair<String, String>>()
        override suspend fun agents(): List<Agent> { readCalls++; return listOf(Agent("a", "Agent", models, emptyMap(), "model", true, true)) }
        override suspend fun conversations(): List<Conversation> { readCalls++; return list }
        override suspend fun createConversation(choice: ModelChoice): Conversation { createCalls++; return create() }
        override suspend fun detail(id: String, cursor: String?) = getDetail(id, cursor)
        override fun follow(conversationId: String, runId: String?, send: SendRequest?, previous: TurnState?): Flow<TurnSnapshot> {
            if (send != null) sendCalls++
            followCalls += Triple(conversationId, runId, send)
            previousTurns += previous
            return followFlow(conversationId, runId, send, previous)
        }
        override suspend fun stop(conversationId: String, controlId: String): JsonObject {
            stopCalls += conversationId to controlId
            return buildJsonObject { put("committed", true); put("status", "running") }
        }
        override suspend fun queue(request: QueueRequest): JsonObject { queueCalls += request; return admit(request) }
        override suspend fun resume(conversationId: String, headId: String): JsonObject {
            resumeCalls += conversationId to headId
            return resumeCall(conversationId, headId)
        }
    }
    companion object {
        private const val CONTROL = "1234567890abcdef1234567890abcdef"
        private fun conversation(id: String, running: Boolean = false) = Conversation(id, id, false, running, false,
            if (running) CONTROL else null, null, ModelChoice("a", "model"))
        private fun detail(id: String, running: Boolean = false) = ConversationDetail(conversation(id, running), emptyList(), emptyList(), false, 0, null)
        private fun forkResponse() = ForkResponse(conversation("child").copy(active = true,
            lineage = ConversationLineage("parent", "retry", 1, 2, "Parent title")),
            ForkVariant("retry", "parent", "Parent title", 1, 2), ForkRun(CONTROL, "running", ModelChoice("a", "model")),
            "https://untrusted.invalid/not-followed", false)
        private fun message(id: String) = ChatMessage(id, "assistant", listOf(ChatBlock.Text(id)))
    }
}
