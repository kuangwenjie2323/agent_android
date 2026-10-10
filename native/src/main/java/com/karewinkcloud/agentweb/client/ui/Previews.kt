package com.karewinkcloud.agentweb.client.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import com.karewinkcloud.agentweb.client.R
import com.karewinkcloud.agentweb.client.R.string as S
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.karewinkcloud.agentweb.client.core.*
import kotlinx.serialization.json.*

// Faithful UI-only fixtures for Android Studio Compose Preview; no credentials or network.
@Preview(name = "Creation · 中文 · light", locale = "zh", widthDp = 360, heightDp = 760, showBackground = true)
@Preview(name = "Creation · 中文 · dark", locale = "zh", widthDp = 360, heightDp = 760, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Preview(name = "Creation · English · large type", locale = "en", widthDp = 360, heightDp = 760, fontScale = 1.3f, showBackground = true)
@Composable
private fun CreationPreview() {
    val workflow = ComfyWorkflow("landscape", "SDXL · 自由画幅", "", "image", "cloud_gpu", listOf(
        ComfyInput("prompt", buildJsonObject { put("type", "string"); put("required", true) })), null, emptyList())
    AgentWebTheme("system") {
        Surface(Modifier.fillMaxSize()) {
            Column {
                ScreenTitle(tr(S.studio))
                Column(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr(S.recent_works), style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(2) { Surface(Modifier.weight(1f).aspectRatio(1f), shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Box(contentAlignment = Alignment.Center) { Text(tr(if (it == 0) S.status_running else S.media_video)) }
                        } }
                    }
                }
                CreationComposer(ComfyState(workflows = listOf(workflow), workflowId = workflow.id, initialized = true,
                    prompt = "雨后的山间，晨光映照竹林", values = mapOf("width" to "1344", "height" to "768")),
                    TextFieldValue("雨后的山间，晨光映照竹林"), {}, { _, _ -> }, {}, {})
                AppNavigation(AppTab.CREATE) {}
            }
        }
    }
}

@Preview(name = "Conversations · 中文 · light", locale = "zh", widthDp = 360, showBackground = true)
@Preview(name = "Conversations · 中文 · dark", locale = "zh", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun ConversationPreview() {
    AgentWebTheme("system") {
        Surface {
            Column(Modifier.padding(16.dp)) {
                ScreenTitle(tr(S.conversations)) { ActionIcon(R.drawable.aw_more, tr(S.more)) {} }
                TextField("", {}, placeholder = { Text(tr(S.search_conversations)) }, modifier = Modifier.fillMaxWidth())
                Text(tr(S.today), Modifier.padding(top = 16.dp), style = MaterialTheme.typography.labelMedium)
                ConversationRow(Conversation("one", "一起整理这个周末的创作计划", false, false, false, null, null,
                    ModelChoice("deepseek", "DeepSeek V4 Pro"), updatedAt = 1790600000, messageCount = 4,
                    preview = "可以先从一个简短的故事开始，再安排分镜与配乐。"), emptyList()) {}
                AppNavigation(AppTab.CONVERSATIONS) {}
            }
        }
    }
}

@Preview(name = "Live chat · 中文 · dark", locale = "zh", widthDp = 360, heightDp = 760, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Live chat · English · large type", locale = "en", widthDp = 360, heightDp = 760, fontScale = 1.3f)
@Composable
private fun LiveChatPreview() {
    AgentWebTheme("system") {
        Surface(Modifier.fillMaxSize()) {
            Column {
                Toolbar("重构流式传输", onBack = {})
                Column(Modifier.weight(1f).padding(16.dp)) {
                    MessageView(ChatMessage("demo", "assistant", listOf(
                        ChatBlock.Tool("edit", "Edit", "", status = StepStatus.COMPLETE, durationMs = 1250, diff = "-before\n+after"),
                        ChatBlock.Text("## 实时 Markdown\n\n已完成 **第一步**，现在继续 *第二步"))), live = true, modelLabel = "DeepSeek V4 Pro")
                }
                ComposerSurface {
                    TextField("", {}, placeholder = { Text(tr(S.followup_hint)) }, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        ComposerChip("DeepSeek V4 Pro", {}, Modifier.weight(1f), selected = true)
                        FilledIconButton({}) { AppIcon(R.drawable.aw_stop, tr(S.stop)) }
                    }
                }
            }
        }
    }
}

@Preview(name = "v0.4 rich · 中文 · light", locale = "zh", widthDp = 360, heightDp = 800, showBackground = true)
@Preview(name = "v0.4 rich · 中文 · dark", locale = "zh", widthDp = 411, heightDp = 860, uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Preview(name = "v0.4 rich · large type", locale = "en", widthDp = 360, heightDp = 800, fontScale = 1.3f)
@Composable
private fun RichChatPreview() {
    AgentWebTheme("system") {
        Surface(Modifier.fillMaxSize()) {
            Column {
                Toolbar("渲染与项目选择", "AgentWeb · 工作区", onBack = {})
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
                    Markdown("""
                        ## 变更说明

                        - [x] **已完成** ~~旧方案~~
                          - 支持嵌套列表与 `inline code`
                        - [ ] 等待手机测试

                        > 保留稳定的流式段落和中文换行。

                        | 语言 | 状态 | 说明 |
                        | :--- | :---: | --- |
                        | Kotlin | 完成 | 长单元格自动换行；整张表可以横向滚动。 |
                        | Python | 测试中 | 点击复制 TSV |

                        ```kotlin
                        val message = "你好，世界！"
                        // A deliberately long line that should scroll horizontally, without widening the chat column.
                        ```

                        ```diff
                        --- a/client.kt
                        +++ b/client.kt
                        @@ -1 +1 @@
                        -val retry = false
                        +val retry = true
                        ```

                        ${'$'}${'$'}
                        \alpha + \frac{a}{b} \leq \infty
                        ${'$'}${'$'}
                    """.trimIndent())
                }
                ComposerSurface {
                    Text(tr(S.reconnecting), style = MaterialTheme.typography.bodySmall)
                    ComposerChip("AgentWeb", {})
                    TextField("", {}, Modifier.fillMaxWidth(), placeholder = { Text(tr(S.message_hint)) })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ActionIcon(R.drawable.aw_plus, tr(S.add_attachment)) {}
                        ComposerChip("DS V4.1 Flash", {}, Modifier.weight(1f), selected = true)
                        FilledIconButton({}) { AppIcon(R.drawable.aw_send, tr(S.send)) }
                    }
                }
            }
        }
    }
}

// Compact-width and large-type review fixtures. These are previews, not device screenshots.
@Preview(name = "States · compact Chinese", locale = "zh", widthDp = 320, heightDp = 900, showBackground = true)
@Preview(name = "States · dark English", locale = "en", widthDp = 360, heightDp = 900, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "States · large type", locale = "zh", widthDp = 360, heightDp = 1100, fontScale = 1.5f)
@Composable
private fun PageStatesPreview() {
    AgentWebTheme("system") {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ScreenTitle(tr(S.conversations), tr(S.conversations_subtitle))
                EmptyState(R.drawable.aw_search, tr(S.no_matches), tr(S.search_hint), tr(S.clear_search), {})
                LoadingState(tr(S.loading_chat))
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatusNotice(tr(S.reconnecting), busy = true)
                    ErrorBlock(ClientError("Cannot connect right now."), {}, tr(S.retry))
                }
                AppNavigation(AppTab.CONVERSATIONS) {}
            }
        }
    }
}

@Preview(name = "Composer · short viewport", locale = "zh", widthDp = 320, heightDp = 280, showBackground = true)
@Preview(name = "Composer · large type", locale = "en", widthDp = 360, heightDp = 300, fontScale = 1.5f)
@Composable
private fun CompactComposerPreview() {
    AgentWebTheme("system") {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column {
                CreationComposer(ComfyState(initialized = true), TextFieldValue(""), {}, { _, _ -> }, {}, {}, maxHeight = 160.dp)
            }
        }
    }
}
