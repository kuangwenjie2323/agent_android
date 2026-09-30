package com.karewinkcloud.agentweb.client.core

import kotlinx.serialization.json.*
import java.util.UUID

data class ConversationLineage(
    val parentConversationId: String, val forkKind: String? = null,
    val sourceUserMessageId: Long? = null, val sourceAssistantMessageId: Long? = null,
    val parentTitle: String? = null,
) {
    companion object {
        fun from(j: JsonObject?): ConversationLineage? {
            val parent = j?.string("parentConversationId")?.takeIf(String::isNotBlank) ?: return null
            return ConversationLineage(parent, j.string("forkKind"), j.long("sourceUserMessageId"),
                j.long("sourceAssistantMessageId"), j.string("parentTitle"))
        }
    }
}

enum class ForkMode(val wire: String) { RETRY("retry"), EDIT("edit") }
data class ForkRequest(
    val conversationId: String, val mode: ForkMode, val messageId: Long, val choice: ModelChoice,
    val message: String? = null, val requestId: String = UUID.randomUUID().toString(),
) {
    init {
        require(conversationId.isNotBlank() && messageId > 0)
        require(requestId.length == 36 && UUID.fromString(requestId).toString() == requestId.lowercase())
        require(if (mode == ForkMode.EDIT) !message.isNullOrBlank() else message == null)
    }
    fun json() = JsonObject(choice.fields() + buildJsonObject {
        put("mode", mode.wire); put("messageId", messageId); put("requestId", requestId)
        if (mode == ForkMode.EDIT) put("message", requireNotNull(message).trim())
    })
}

data class ForkVariant(
    val kind: String?, val parentConversationId: String, val parentTitle: String?,
    val sourceUserMessageId: Long?, val sourceAssistantMessageId: Long?,
)
data class ForkRun(val id: String, val status: String?, val choice: ModelChoice)
data class ForkResponse(
    val conversation: Conversation, val variant: ForkVariant, val run: ForkRun,
    val streamUrl: String?, val existing: Boolean,
) {
    companion object {
        fun from(j: JsonObject): ForkResponse {
            val variant = requireNotNull(j.obj("variant"))
            val parent = requireNotNull(variant.obj("parentConversation"))
            val parsedVariant = ForkVariant(variant.string("kind"), requireNotNull(parent.string("id")).also { require(it.isNotBlank()) },
                parent.string("title"), variant.long("sourceUserMessageId"), variant.long("sourceAssistantMessageId"))
            val run = requireNotNull(j.obj("run"))
            val conversation = Conversation.from(requireNotNull(j.obj("conversation")))
            require(conversation.id.isNotBlank())
            val lineage = conversation.lineage ?: ConversationLineage(parsedVariant.parentConversationId,
                parsedVariant.kind, parsedVariant.sourceUserMessageId, parsedVariant.sourceAssistantMessageId)
            return ForkResponse(conversation.copy(lineage = lineage.copy(parentTitle = parsedVariant.parentTitle ?: lineage.parentTitle)),
                parsedVariant, ForkRun(requireNotNull(run.string("id")).also { require(it.isNotBlank()) }, run.string("status"),
                    ModelChoice(run.string("agent").orEmpty(), run.string("model").orEmpty(), run.string("effort"),
                        run.string("permissionMode") ?: "auto", run.string("executionMode") ?: "standard")),
                j.string("streamUrl"), j.boolean("existing") == true)
        }
    }
}
