package com.karewinkcloud.agentweb.client.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ForksTest {
    private val choice = ModelChoice("claude", "deepseek-v4-pro", "high", "full", "standard")
    private val uuid = "12345678-1234-1234-1234-123456789abc"
    private fun json(text: String) = wireJson.parseToJsonElement(text).jsonObject

    @Test fun retryJsonUsesNumericAssistantIdUuidAndCurrentChoices() {
        val body = ForkRequest("parent", ForkMode.RETRY, 42, choice, requestId = uuid).json()
        assertEquals(json("""{"mode":"retry","messageId":42,"requestId":"$uuid","agent":"claude","model":"deepseek-v4-pro","effort":"high","permMode":"full","executionMode":"standard"}"""), body)
        assertFalse(body["messageId"]!!.jsonPrimitive.isString)
        assertFalse(body.containsKey("message"))
        val generated = ForkRequest("parent", ForkMode.RETRY, 42, choice).requestId
        assertEquals(36, generated.length)
        assertEquals(generated, UUID.fromString(generated).toString())
    }
    @Test fun editJsonUsesUserIdAndTrimmedText() {
        assertEquals(json("""{"mode":"edit","messageId":41,"requestId":"$uuid","message":"修改的问题","agent":"claude","model":"deepseek-v4-pro","effort":"high","permMode":"full","executionMode":"standard"}"""),
            ForkRequest("parent", ForkMode.EDIT, 41, choice, " 修改的问题 ", uuid).json())
    }
    @Test fun invalidAnchorsAndBlankEditsAreRejectedBeforeNetwork() {
        for (id in listOf(0L, -1L)) assertTrue(runCatching { ForkRequest("parent", ForkMode.RETRY, id, choice) }.isFailure)
        assertTrue(runCatching { ForkRequest("parent", ForkMode.EDIT, 1, choice, " ") }.isFailure)
        assertTrue(runCatching { ForkRequest("parent", ForkMode.RETRY, 1, choice, "not allowed") }.isFailure)
        assertTrue(runCatching { ForkRequest("parent", ForkMode.RETRY, 1, choice, requestId = newControlId()) }.isFailure)
    }
    @Test fun responseKeepsAdvertisedRunAndEnrichesLineageWithoutControlId() {
        val parsed = ForkResponse.from(json("""{
          "conversation":{"id":"child","title":"Variant","active":true,"lineage":{"parentConversationId":"parent","forkKind":"retry","sourceUserMessageId":41,"sourceAssistantMessageId":42}},
          "variant":{"kind":"retry","parentConversation":{"id":"parent","title":"父对话"},"sourceUserMessageId":41,"sourceAssistantMessageId":42},
          "run":{"id":"run123","status":"running","agent":"claude","model":"deepseek-v4-pro","effort":"high","permissionMode":"full","executionMode":"standard"},
          "streamUrl":"/api/chat/stream/child","existing":true
        }"""))
        assertEquals("child", parsed.conversation.id)
        assertNull(parsed.conversation.controlId)
        assertEquals("run123", parsed.run.id)
        assertEquals(choice, parsed.run.choice)
        assertEquals("/api/chat/stream/child", parsed.streamUrl)
        assertTrue(parsed.existing)
        assertEquals(ConversationLineage("parent", "retry", 41, 42, "父对话"), parsed.conversation.lineage)
    }
    @Test fun historyLineageKeepsIdsWithoutInventingParentTitle() {
        val parsed = Conversation.from(json("""{"id":"child","lineage":{"parentConversationId":"parent","forkKind":"edit","sourceUserMessageId":41,"sourceAssistantMessageId":null}}"""))
        assertEquals(ConversationLineage("parent", "edit", 41), parsed.lineage)
        assertNull(Conversation.from(json("""{"id":"regular","lineage":null}""")).lineage)
    }
}
