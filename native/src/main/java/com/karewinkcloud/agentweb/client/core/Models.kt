package com.karewinkcloud.agentweb.client.core

import kotlinx.serialization.json.*
import java.util.UUID

fun newControlId(): String = UUID.randomUUID().toString().replace("-", "")

data class ModelChoice(
    val agent: String = "", val model: String = "", val effort: String? = null,
    val permission: String = "auto", val execution: String = "standard",
) {
    fun fields(): JsonObject = buildJsonObject {
        if (agent.isNotBlank()) put("agent", agent)
        if (model.isNotBlank()) put("model", model)
        put("effort", effort?.let(::JsonPrimitive) ?: JsonNull)
        put("permMode", permission)
        put("executionMode", execution)
    }
}

data class Agent(
    val id: String, val name: String, val models: List<String>, val labels: Map<String, String>,
    val defaultModel: String, val supportsEffort: Boolean, val available: Boolean,
    /** False when the operator turned the provider off; pickers hide it entirely. */
    val enabled: Boolean = true,
    val contextWindows: Map<String, Long> = emptyMap(),
    val contextKinds: Map<String, String> = emptyMap(),
    val effortLevels: Map<String, List<String>> = emptyMap(),
    val supportsImages: Boolean = false,
    /** Per-model image support; older servers send only [supportsImages]. */
    val modelImages: Map<String, Boolean> = emptyMap(),
) {
    fun label(model: String) = model.removeSuffix("[1m]").let { id -> labels[id] ?: id.substringAfterLast('/') }
    /** Whether [model] reads attached photos (DeepSeek V4.1 Flash does, V4 Pro does not). */
    fun readsImages(model: String?) = model?.let { modelImages[it] } ?: supportsImages
    fun resumeModel(model: String): String = if (model in models || id != "claude") model else
        models.firstOrNull { it in setOf("opus", "sonnet", "haiku") && model.startsWith("claude-$it-") } ?: model
    companion object {
        fun from(j: JsonObject) = Agent(
            j.string("id").orEmpty(), j.string("name").orEmpty(),
            j.array("models").mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            j.obj("modelLabels")?.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull.orEmpty() }.orEmpty(),
            j.string("model").orEmpty(), j.boolean("supportsEffort") == true, j.boolean("available") == true,
            j.boolean("enabled") != false,
            j.obj("contextWindows")?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.longOrNull?.let { k to it } }?.toMap().orEmpty(),
            j.obj("contextKinds")?.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull.orEmpty() }.orEmpty(),
            j.obj("modelEffortLevels")?.mapValues { (_, v) -> (v as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty() }.orEmpty(),
            j.boolean("supportsImages") == true,
            j.obj("modelSupportsImages")?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.booleanOrNull?.let { k to it } }?.toMap().orEmpty(),
        )
    }
}

data class Conversation(
    val id: String, val title: String, val pinned: Boolean, val active: Boolean,
    val starting: Boolean, val controlId: String?, val project: String?, val choice: ModelChoice,
    val nativeControl: Boolean = false,
    val updatedAt: Long = 0,
    val messageCount: Int? = null,
    val preview: String = "",
    val projectId: String? = null,
    val lineage: ConversationLineage? = null,
) {
    val running get() = active || starting
    /** The list-API shape of this row (without live run state), for the on-device list cache. */
    fun cacheJson(): JsonObject = buildJsonObject {
        put("id", id); put("title", title); put("pinned", pinned); put("updated_at", updatedAt)
        if (choice.agent.isNotBlank()) put("agent", choice.agent)
        if (choice.model.isNotBlank()) put("model", choice.model)
        choice.effort?.let { put("effort", it) }
        put("perm_mode", choice.permission); put("execution_mode", choice.execution)
        if (nativeControl) put("controlKind", "codexNative")
        messageCount?.let { put("message_count", it) }
        if (preview.isNotEmpty()) put("last_message_preview", preview)
        if (projectId != null || project != null) put("project", buildJsonObject {
            projectId?.let { put("id", it) }; project?.let { put("name", it) }
        })
    }
    companion object {
        fun from(j: JsonObject) = Conversation(
            j.string("id").orEmpty(), j.string("title").orEmpty().ifBlank { "New chat" },
            j.boolean("pinned") == true, j.boolean("active") == true, j.boolean("starting") == true,
            j.string("controlId"), j.obj("project")?.string("name"),
            ModelChoice(j.string("agent").orEmpty(), j.string("model").orEmpty(), j.string("effort"),
                j.string("perm_mode") ?: "auto", j.string("execution_mode") ?: "standard"),
            j.string("controlKind") == "codexNative",
            j.long("updated_at") ?: j.long("created_at") ?: 0,
            j.long("message_count")?.toInt(), j.string("last_message_preview").orEmpty(),
            j.obj("project")?.string("id"), ConversationLineage.from(j.obj("lineage")),
        )
    }
}

enum class StepStatus { RUNNING, COMPLETE, ERROR, INTERRUPTED }
sealed interface ChatBlock {
    data class Text(val content: String) : ChatBlock
    data class Thought(val content: String = "", val startedAt: Long = 0, val durationMs: Long? = null,
        val status: StepStatus = StepStatus.RUNNING) : ChatBlock
    data class Tool(val id: String, val name: String, val input: String = "", val result: String? = null,
        val status: StepStatus = StepStatus.RUNNING, val startedAt: Long = 0, val durationMs: Long? = null, val diff: String? = null) : ChatBlock
    data class Error(val problem: ClientError) : ChatBlock
    data class Notice(val content: String) : ChatBlock
    /** A message the user sent into the running turn; the model read it at its next step. */
    data class Steer(val content: String) : ChatBlock
    data class Media(val items: List<MessageMedia>) : ChatBlock
}

/** Shown when the user stopped a turn; not an error. */
const val STOPPED_NOTICE = "stopped by user"
/** A command the stop itself killed (SIGKILL/SIGTERM) rather than one that failed on its own. */
internal fun killedByStop(tool: ChatBlock.Tool) = tool.status == StepStatus.ERROR &&
    tool.result?.trim() in setOf("Exit code 137", "Exit code 143")

fun historyBlocks(j: JsonObject): List<ChatBlock> {
    val blocks = j.objects("blocks").mapIndexedNotNull { index, b ->
        when (b.string("type")) {
            "text" -> ChatBlock.Text(b.string("content").orEmpty())
            "thinking" -> ChatBlock.Thought(b.string("content").orEmpty(), durationMs =
                (b["duration"] as? JsonPrimitive)?.doubleOrNull?.times(1000)?.toLong(), status = StepStatus.COMPLETE)
            "tool" -> ChatBlock.Tool(
                b.string("id") ?: "history-$index", b.string("name").orEmpty().ifBlank { "Tool" },
                b["input"]?.toString().orEmpty(), b.string("result"),
                when { b.boolean("is_error") == true -> StepStatus.ERROR
                    b.containsKey("result") -> StepStatus.COMPLETE
                    else -> StepStatus.INTERRUPTED },
                durationMs = b.long("duration_ms")?.coerceAtLeast(0), diff = b.string("diff"),
            )
            "error" -> if (b.string("code") == "interrupted") ChatBlock.Notice(STOPPED_NOTICE)
                else ChatBlock.Error(ClientError(b.string("content").orEmpty(), b.string("code"), b.boolean("retryable")))
            "steer" -> ChatBlock.Steer(b.string("content").orEmpty())
            else -> null
        }
    }.toMutableList()
    if (blocks.none { it is ChatBlock.Text } && !j.string("content").isNullOrEmpty()) {
        blocks.add(0, ChatBlock.Text(j.string("content").orEmpty()))
    }
    if (j.string("status") == "interrupted") {
        blocks.replaceAll { if (it is ChatBlock.Tool && killedByStop(it)) it.copy(status = StepStatus.INTERRUPTED) else it }
        if (blocks.none { it is ChatBlock.Error || (it is ChatBlock.Notice && it.content == STOPPED_NOTICE) }) blocks.add(ChatBlock.Notice(STOPPED_NOTICE))
    } else if (j.string("status") == "error" && blocks.none { it is ChatBlock.Error }) {
        blocks.add(ChatBlock.Error(ClientError("This turn was error.", retryable = false)))
    }
    if (j.objects("images").isNotEmpty()) blocks.add(ChatBlock.Media(j.objects("images").map(MessageMedia::from)))
    return blocks
}

data class ChatMessage(val id: String, val role: String, val blocks: List<ChatBlock>, val model: String? = null,
    val usage: TurnUsage? = null) {
    val text get() = blocks.filterIsInstance<ChatBlock.Text>().joinToString("") { it.content }
    companion object {
        fun from(j: JsonObject) = ChatMessage(j.string("id").orEmpty(), j.string("role") ?: "assistant", historyBlocks(j),
            j.string("model") ?: j.obj("usage")?.string("model"), TurnUsage.from(j.obj("usage")))
    }
}
data class QueueItem(val id: String, val text: String) {
    companion object { fun from(j: JsonObject) = QueueItem(j.string("id").orEmpty(), j.string("message").orEmpty()) }
}
data class ConversationDetail(
    val conversation: Conversation, val messages: List<ChatMessage>, val queued: List<QueueItem>,
    val queuePaused: Boolean, val olderCount: Int, val nextCursor: String?,
) {
    companion object {
        fun from(j: JsonObject) = ConversationDetail(
            Conversation.from(requireNotNull(j.obj("conversation"))), j.objects("messages").map(ChatMessage::from),
            j.objects("queued").map(QueueItem::from), j.boolean("queuePaused") == true,
            j.obj("messagePage")?.long("olderCount")?.toInt() ?: 0, j.obj("messagePage")?.string("nextCursor"),
        )
    }
}

data class SendRequest(val conversationId: String, val text: String, val choice: ModelChoice, val controlId: String = newControlId(),
    val attachments: List<Attachment> = emptyList()) {
    fun json() = JsonObject(choice.fields() + mapOf("conversationId" to JsonPrimitive(conversationId),
        "message" to JsonPrimitive(attachmentMessage(text, attachments)), "controlId" to JsonPrimitive(controlId)) + attachmentFields(attachments))
}

data class ClaudeSession(
    val id: String, val cwd: String, val projectName: String, val title: String,
    val updatedAt: Long, val messageCount: Int, val model: String, val provider: String,
    val entrypoint: String?, val gitBranch: String?, val maybeActive: Boolean,
    val linkedConversationId: String?, val historyLimited: Boolean = false,
) {
    fun conversation(id: String = requireNotNull(linkedConversationId)) = Conversation(
        id, title, false, false, false, null, projectName, ModelChoice(provider, model),
    )
    companion object {
        fun from(j: JsonObject): ClaudeSession {
            val project = j.obj("project")
            return ClaudeSession(requireNotNull(j.string("id")), project?.string("cwd") ?: j.string("cwd").orEmpty(),
                project?.string("name").orEmpty(), j.string("title").orEmpty().ifBlank { "Claude Code session" },
                j.long("updatedAt") ?: 0, j.long("messageCount")?.toInt() ?: 0,
                j.string("model").orEmpty(), j.string("provider") ?: "claude", j.string("entrypoint"), j.string("gitBranch"),
                j.string("state") != "idle", j.string("linkedConversationId"), j.boolean("historyLimited") == true)
        }
    }
}
data class ClaudeSessionPage(val sessions: List<ClaudeSession>, val nextCursor: String?)
data class ClaudeSessionHistory(val session: ClaudeSession, val messages: List<ChatMessage>, val nextCursor: String?, val olderCount: Int)
data class QueueRequest(val conversationId: String, val text: String, val choice: ModelChoice, val queueId: String = newControlId(),
    val attachments: List<Attachment> = emptyList()) {
    fun json() = JsonObject(choice.fields() + mapOf("conversationId" to JsonPrimitive(conversationId),
        "message" to JsonPrimitive(attachmentMessage(text, attachments)), "queueId" to JsonPrimitive(queueId)) + attachmentFields(attachments))
}
