package io.github.zby1888.codexzh

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal data class CodexChatEntry(
    val id: String,
    val title: String,
    val text: String,
    val isUser: Boolean = false,
    val isActivity: Boolean = false
)

internal data class CodexModelOption(
    val id: String,
    val displayName: String,
    val description: String,
    val defaultReasoningEffort: String,
    val reasoningEfforts: List<String>,
    val isDefault: Boolean
)

internal data class CodexMcpServerSummary(
    val name: String,
    val runtimeStatus: String,
    val authStatus: String,
    val httpOrigin: String,
    val toolNames: List<String>,
    val error: String?
)

internal data class CodexThreadSummary(
    val id: String,
    val title: String,
    val preview: String,
    val workingDirectory: String
)

internal data class CodexUserInputOption(
    val label: String,
    val description: String
)

internal data class CodexUserInputQuestion(
    val id: String,
    val header: String,
    val question: String,
    val isSecret: Boolean,
    val options: List<CodexUserInputOption>
)

internal data class CodexPendingUserInput(
    val requestId: Any,
    val isBlocking: Boolean,
    val questions: List<CodexUserInputQuestion>
)

internal data class CodexPendingApproval(
    val requestId: Any,
    val method: String,
    val params: JSONObject
) {
    val supported: Boolean
        get() = method == "item/commandExecution/requestApproval" ||
            method == "item/fileChange/requestApproval"
}

internal class CodexChatUiState {
    var controller by mutableStateOf<CodexAgentController?>(null)
    var threadId by mutableStateOf<String?>(null)
    var workingDirectory by mutableStateOf<String?>(null)
    var activeTurnId by mutableStateOf<String?>(null)
    var showRecentThreads by mutableStateOf(false)
    var showArchivedThreads by mutableStateOf(false)
    val recentThreads = mutableStateListOf<CodexThreadSummary>()
    val mcpServers = mutableStateListOf<CodexMcpServerSummary>()
    val mcpOAuthMessages = mutableStateMapOf<String, String>()
    val models = mutableStateListOf<CodexModelOption>()
    var selectedModelId by mutableStateOf<String?>(null)
    var selectedReasoningEffort by mutableStateOf<String?>(null)
    var isWorking by mutableStateOf(false)
    var isSubmitting by mutableStateOf(false)
    var status by mutableStateOf("尚未连接 Codex")
    var error by mutableStateOf<String?>(null)
    var pendingApproval by mutableStateOf<CodexPendingApproval?>(null)
    var pendingUserInput by mutableStateOf<CodexPendingUserInput?>(null)
    val userInputAnswers = mutableStateMapOf<String, String>()
    var needsAgentRestart by mutableStateOf(false)
    val entries = mutableStateListOf<CodexChatEntry>()
    private val mainHandler = Handler(Looper.getMainLooper())
    val appServerListener = object : CodexAppServerClient.Listener {
        override fun onNotification(message: JSONObject) {
            mainHandler.post {
                applyNotification(message)
                if (message.optString("method") == "turn/completed") {
                    val params = message.optJSONObject("params") ?: JSONObject()
                    val turn = params.optJSONObject("turn") ?: JSONObject()
                    val turnId = turn.optString("id")
                    val items = turn.optJSONArray("items") ?: JSONArray()
                    val hasFinalAssistantText = (0 until items.length()).any { index ->
                        val item = items.optJSONObject(index) ?: return@any false
                        item.optString("type") == "agentMessage" &&
                            item.optString("text").isNotBlank()
                    }
                    val thread = params.optString("threadId")
                    if (!hasFinalAssistantText && thread.isNotBlank() && turnId.isNotBlank()) {
                        requestFinalItems(thread, turnId)
                    }
                }
            }
        }

        override fun onServerRequest(message: JSONObject) {
            val requestId = message.opt("id") ?: return
            val method = message.optString("method")
            val params = message.optJSONObject("params") ?: JSONObject()
            mainHandler.post {
                if (method == "item/tool/requestUserInput") {
                    val questionsJson = params.optJSONArray("questions")
                    val questions = buildList {
                        if (questionsJson != null) {
                            for (index in 0 until questionsJson.length()) {
                                val question = questionsJson.optJSONObject(index) ?: continue
                                val optionsJson = question.optJSONArray("options")
                                val options = buildList {
                                    if (optionsJson != null) {
                                        for (optionIndex in 0 until optionsJson.length()) {
                                            val option = optionsJson.optJSONObject(optionIndex) ?: continue
                                            add(
                                                CodexUserInputOption(
                                                    label = option.optString("label"),
                                                    description = option.optString("description")
                                                )
                                            )
                                        }
                                    }
                                }
                                val id = question.optString("id")
                                if (id.isNotBlank()) {
                                    add(
                                        CodexUserInputQuestion(
                                            id = id,
                                            header = question.optString("header"),
                                            question = question.optString("question"),
                                            isSecret = question.optBoolean("isSecret"),
                                            options = options
                                        )
                                    )
                                }
                            }
                        }
                    }
                    pendingUserInput = CodexPendingUserInput(
                        requestId = requestId,
                        isBlocking = params.optBoolean("isBlocking"),
                        questions = questions
                    )
                    userInputAnswers.clear()
                    pendingApproval = null
                    status = "Codex 正在等待你回答问题"
                } else {
                    pendingApproval = CodexPendingApproval(requestId, method, params)
                    pendingUserInput = null
                    status = "Codex 正在等待你的操作确认"
                }
            }
        }

        override fun onDiagnostic(message: String) {
            mainHandler.post { status = message }
        }
    }

    fun replaceMcpServers(response: JSONObject) {
        mcpServers.clear()
        val data = response.optJSONArray("data") ?: return
        for (index in 0 until data.length()) {
            val server = data.optJSONObject(index) ?: continue
            val name = server.optString("name")
            if (name.isBlank()) continue
            val toolObject = server.optJSONObject("tools")
            val toolNames = mutableListOf<String>()
            if (toolObject != null) {
                val keys = toolObject.keys()
                while (keys.hasNext()) toolNames.add(keys.next())
            }
            mcpServers.add(
                CodexMcpServerSummary(
                    name = name,
                    runtimeStatus = server.optString("runtimeStatus").ifBlank { "unknown" },
                    authStatus = server.optString("authStatus").ifBlank { "unknown" },
                    httpOrigin = server.optString("httpOrigin"),
                    toolNames = toolNames.sorted(),
                    error = server.optString("toolsError").takeIf { it.isNotBlank() }
                )
            )
        }
    }

    fun closeInBackground() {
        val active = controller
        controller = null
        if (active != null) {
            Thread({
                runCatching { active.close() }
            }, "codex-app-server-close").apply { isDaemon = true }.start()
        }
    }

    fun append(id: String, title: String, delta: String, isActivity: Boolean = false) {
        if (delta.isEmpty()) return
        val index = entries.indexOfFirst { it.id == id }
        if (index < 0) {
            entries.add(CodexChatEntry(id, title, delta, isActivity = isActivity))
        } else {
            val old = entries[index]
            entries[index] = old.copy(text = old.text + delta)
        }
    }

    fun upsert(id: String, title: String, text: String, isActivity: Boolean = false) {
        val index = entries.indexOfFirst { it.id == id }
        val next = if (index < 0) CodexChatEntry(id, title, text, isActivity = isActivity)
        else entries[index].copy(title = title, text = text, isActivity = isActivity)
        if (index < 0) entries.add(next) else entries[index] = next
    }

    fun replaceModels(response: JSONObject) {
        val next = mutableListOf<CodexModelOption>()
        val data = response.optJSONArray("data") ?: JSONArray()
        for (index in 0 until data.length()) {
            val model = data.optJSONObject(index) ?: continue
            val id = model.optString("id").ifBlank { model.optString("model") }
            if (id.isBlank()) continue
            val efforts = model.optJSONArray("supportedReasoningEfforts") ?: JSONArray()
            val effortValues = buildList {
                for (effortIndex in 0 until efforts.length()) {
                    val effort = efforts.optJSONObject(effortIndex) ?: continue
                    val value = effort.optString("reasoningEffort")
                    if (value.isNotBlank()) add(value)
                }
            }
            next.add(
                CodexModelOption(
                    id = id,
                    displayName = model.optString("displayName").ifBlank { id },
                    description = model.optString("description"),
                    defaultReasoningEffort = model.optString("defaultReasoningEffort").ifBlank {
                        effortValues.firstOrNull().orEmpty()
                    },
                    reasoningEfforts = effortValues,
                    isDefault = model.optBoolean("isDefault")
                )
            )
        }
        models.clear()
        models.addAll(next)
        val selected = models.firstOrNull { it.id == selectedModelId }
            ?: models.firstOrNull { it.isDefault }
            ?: models.firstOrNull()
        selectedModelId = selected?.id
        if (selected != null && selectedReasoningEffort?.let { it in selected.reasoningEfforts } != true) {
            selectedReasoningEffort = selected.defaultReasoningEffort.ifBlank {
                selected.reasoningEfforts.firstOrNull()
            }
        }
    }

    fun selectModel(model: CodexModelOption) {
        selectedModelId = model.id
        selectedReasoningEffort = model.defaultReasoningEffort.ifBlank {
            model.reasoningEfforts.firstOrNull()
        }
    }

    fun replaceRecentThreads(response: JSONObject) {
        recentThreads.clear()
        val data = response.optJSONArray("data") ?: return
        for (index in 0 until data.length()) {
            val thread = data.optJSONObject(index) ?: continue
            val id = thread.optString("id")
            if (id.isBlank()) continue
            val preview = thread.optString("preview")
            val title = thread.optString("name").ifBlank { preview }.ifBlank { "未命名会话" }
            recentThreads.add(
                CodexThreadSummary(
                    id = id,
                    title = title,
                    preview = preview,
                    workingDirectory = thread.optString("cwd")
                )
            )
        }
    }

    fun restoreHistory(response: JSONObject) {
        entries.clear()
        val data = response.optJSONArray("data") ?: return
        for (index in 0 until data.length()) {
            val entry = data.optJSONObject(index) ?: continue
            val item = entry.optJSONObject("item") ?: continue
            val id = item.optString("id")
            if (id.isBlank()) continue
            when (item.optString("type")) {
                "userMessage" -> {
                    val content = item.optJSONArray("content") ?: continue
                    val text = buildList {
                        for (contentIndex in 0 until content.length()) {
                            val part = content.optJSONObject(contentIndex) ?: continue
                            when (part.optString("type")) {
                                "text" -> add(part.optString("text"))
                                "skill" -> add("[技能：${part.optString("name")}]")
                                "mention" -> add("@${part.optString("name")}")
                                "localImage", "image" -> add("[图片附件]")
                            }
                        }
                    }.filter { it.isNotBlank() }.joinToString("\n")
                    if (text.isNotBlank()) upsert("history:user:$id", "你", text)
                }
                "agentMessage" -> {
                    val text = item.optString("text")
                    if (text.isNotBlank()) upsert("history:assistant:$id", "Codex", text)
                }
                "commandExecution" -> {
                    val text = buildString {
                        append(item.optString("command"))
                        append("\n目录：").append(item.optString("cwd"))
                        append("\n状态：").append(item.optString("status"))
                        val output = item.optString("aggregatedOutput")
                        if (output.isNotBlank()) append("\n\n").append(output)
                    }
                    upsert("history:command:$id", "命令执行", text, true)
                }
                "fileChange" -> {
                    val changes = item.optJSONArray("changes")
                    val paths = buildList {
                        if (changes != null) {
                            for (changeIndex in 0 until changes.length()) {
                                val change = changes.optJSONObject(changeIndex) ?: continue
                                add(change.optString("path"))
                            }
                        }
                    }.filter { it.isNotBlank() }
                    upsert("history:file:$id", "文件修改", paths.joinToString("\n"), true)
                }
                "mcpToolCall" -> upsert(
                    "history:mcp:$id",
                    "MCP 工具",
                    "${item.optString("server")} / ${item.optString("tool")}\n状态：${item.optString("status")}",
                    true
                )
                "plan" -> upsert("history:plan:$id", "执行计划", item.optString("text"), true)
            }
        }
    }

    fun appendCompletedTurnItems(turn: JSONObject) {
        val items = turn.optJSONArray("items") ?: return
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            handleItem(item, started = false)
        }
    }

    fun containsAssistantMessage(items: JSONArray): Boolean =
        (0 until items.length()).any { index ->
            val item = items.optJSONObject(index) ?: return@any false
            item.optString("type") == "agentMessage" && item.optString("text").isNotBlank()
        }

    fun responseContainsAssistantMessage(response: JSONObject): Boolean {
        val data = response.optJSONArray("data") ?: return false
        return (0 until data.length()).any { index ->
            val entry = data.optJSONObject(index) ?: return@any false
            val item = entry.optJSONObject("item") ?: return@any false
            item.optString("type") == "agentMessage" && item.optString("text").isNotBlank()
        }
    }

    fun describeTurnError(turn: JSONObject): String? {
        val turnError = turn.optJSONObject("error") ?: return null
        return listOf(
            turnError.optString("message").takeIf { it.isNotBlank() },
            turnError.optString("additionalDetails").takeIf { it.isNotBlank() },
            turnError.opt("codexErrorInfo")?.toString()?.takeIf { it.isNotBlank() }
        ).filterNotNull().joinToString("\n").takeIf { it.isNotBlank() }
    }

    fun appendListedTurnItems(response: JSONObject) {
        val data = response.optJSONArray("data") ?: return
        for (index in 0 until data.length()) {
            val entry = data.optJSONObject(index) ?: continue
            val item = entry.optJSONObject("item") ?: continue
            handleItem(item, started = false)
        }
    }

    private fun requestFinalItems(threadId: String, turnId: String) {
        val agent = controller ?: return
        Thread({
            runCatching {
                agent.listItems(threadId, turnId = turnId, limit = 200, sortDirection = "asc")
            }.onSuccess { response ->
                mainHandler.post {
                    if (this.threadId == threadId) {
                        val hasFinalText = responseContainsAssistantMessage(response)
                        appendListedTurnItems(response)
                        if (!hasFinalText) {
                            error = "服务端已结束本轮，但当前轮次的会话记录没有助手正文。请检查服务商 Responses API 实际返回内容。"
                            status = "本轮已结束，但没有可显示的回复"
                        } else {
                            error = null
                            status = "已从当前轮次记录同步最终回复"
                        }
                    }
                }
            }.onFailure { failure ->
                mainHandler.post {
                    if (this.threadId == threadId &&
                        entries.none { !it.isUser && !it.isActivity && it.text.isNotBlank() }) {
                        error = "本轮已结束，但读取最终消息失败：${failure.message ?: "未知错误"}"
                    }
                }
            }
        }, "codex-final-items-sync").apply { isDaemon = true }.start()
    }

    fun applyNotification(message: JSONObject) {
        val method = message.optString("method")
        val params = message.optJSONObject("params") ?: JSONObject()
        val eventThreadId = params.optString("threadId")
        if (eventThreadId.isNotBlank() && threadId != null && eventThreadId != threadId) return
        when (method) {
            "mcpServer/oauthLogin/completed" -> {
                val name = params.optString("name")
                val success = params.optBoolean("success")
                val detail = params.optString("error")
                if (name.isNotBlank()) {
                    mcpOAuthMessages[name] = if (success) {
                        "OAuth 授权完成"
                    } else {
                        "OAuth 授权失败：${detail.ifBlank { "请检查授权页面或服务端日志" }}"
                    }
                }
            }
            "turn/started" -> {
                val turn = params.optJSONObject("turn") ?: JSONObject()
                activeTurnId = turn.optString("id").takeIf { it.isNotBlank() }
                isWorking = true
                error = null
                status = "Codex 已开始执行任务"
            }
            "turn/completed" -> {
                val turn = params.optJSONObject("turn") ?: JSONObject()
                // The pinned 0.169.0 Turn payload includes canonical final items.
                appendCompletedTurnItems(turn)
                val result = turn.optString("status").ifBlank { "completed" }
                val failure = describeTurnError(turn)
                isWorking = false
                activeTurnId = null
                if (result == "failed") {
                    error = failure ?: "Codex 本轮执行失败；请检查服务商和 API 兼容性"
                } else if (result == "completed") {
                    error = null
                }
                status = when (result) {
                    "completed" -> if (entries.none {
                        !it.isUser && !it.isActivity && it.text.isNotBlank()
                    }) "服务端已完成本轮，但没有返回可显示的助手正文" else "本轮已完成"
                    "interrupted" -> "任务已中断"
                    "failed" -> "任务执行失败"
                    else -> "本轮结束：$result"
                }
            }
            "item/agentMessage/delta" -> {
                val itemId = params.optString("itemId")
                val delta = params.optString("delta")
                if (delta.isNotEmpty()) {
                    error = null
                    status = "Codex 正在生成回复"
                    append("assistant:$itemId", "Codex", delta)
                }
            }
            "item/commandExecution/outputDelta" -> {
                val itemId = params.optString("itemId")
                append("command:$itemId", "命令输出", params.optString("delta"), true)
            }
            "item/started" -> handleItem(params.optJSONObject("item"), started = true)
            "item/completed" -> handleItem(params.optJSONObject("item"), started = false)
            "item/plan/delta" -> {
                val itemId = params.optString("itemId")
                append("plan:$itemId", "执行计划", params.optString("delta"), true)
            }
            "item/reasoning/summaryTextDelta" -> {
                val itemId = params.optString("itemId")
                append("reasoning:$itemId", "思考摘要", params.optString("delta"), true)
            }
            "item/mcpToolCall/progress" -> {
                val itemId = params.optString("itemId")
                append("mcp:$itemId", "MCP 工具进度", params.optString("message"), true)
            }
            "turn/diff/updated" -> {
                val turnId = params.optString("turnId")
                val diff = params.optString("diff")
                if (diff.isNotBlank()) {
                    upsert(
                        "diff:${turnId.ifBlank { params.optString("threadId") }}",
                        "代码变更 Diff",
                        diff,
                        true
                    )
                }
            }
            "item/fileChange/patchUpdated" -> {
                val changes = params.optJSONArray("changes")
                val names = buildList {
                    if (changes != null) {
                        for (index in 0 until changes.length()) {
                            val change = changes.optJSONObject(index) ?: continue
                            add(change.optString("path"))
                        }
                    }
                }.filter { it.isNotBlank() }
                if (names.isNotEmpty()) {
                    upsert(
                        "patch:${params.optString("itemId")}",
                        "文件变更",
                        "已更新变更内容：\n" + names.joinToString("\n"),
                        true
                    )
                }
            }
        }
    }

    private fun handleItem(item: JSONObject?, started: Boolean) {
        if (item == null) return
        val id = item.optString("id")
        if (id.isBlank()) return
        when (item.optString("type")) {
            "agentMessage" -> {
                val text = item.optString("text")
                if (text.isNotBlank()) upsert("assistant:$id", "Codex", text)
            }
            "commandExecution" -> {
                val command = item.optString("command")
                val cwd = item.optString("cwd")
                val state = item.optString("status").ifBlank {
                    if (started) "inProgress" else "completed"
                }
                val output = item.optString("aggregatedOutput")
                val details = buildString {
                    append(command.ifBlank { "正在执行命令" })
                    if (cwd.isNotBlank()) append("\n目录：").append(cwd)
                    append("\n状态：").append(state)
                    item.optInt("exitCode", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }?.let {
                        append("\n退出码：").append(it)
                    }
                    if (output.isNotBlank()) append("\n\n").append(output)
                }
                upsert("command:$id", "命令执行", details, true)
            }
            "fileChange" -> {
                val changes = item.optJSONArray("changes")
                val paths = buildList {
                    if (changes != null) {
                        for (index in 0 until changes.length()) {
                            val change = changes.optJSONObject(index) ?: continue
                            add(change.optString("path"))
                        }
                    }
                }.filter { it.isNotBlank() }
                upsert(
                    "file:$id",
                    "文件修改",
                    if (paths.isEmpty()) "文件修改状态：${item.optString("status")}"
                    else paths.joinToString("\n"),
                    true
                )
            }
            "mcpToolCall" -> upsert(
                "mcp:$id",
                "MCP 工具",
                "${item.optString("server")} / ${item.optString("tool")}\n状态：${item.optString("status")}",
                true
            )
            "plan" -> {
                val text = item.optString("text")
                if (text.isNotBlank()) upsert("plan:$id", "执行计划", text, true)
            }
            else -> {
                val type = item.optString("type").ifBlank { "未知操作" }
                if (started) upsert("item:$id", "执行步骤", type, true)
            }
        }
    }
}

@Composable
internal fun CodexChatScreen(
    modifier: Modifier = Modifier,
    state: CodexChatUiState,
    onOpenProviders: () -> Unit,
    onOpenMcp: () -> Unit,
    onOpenTerminal: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runtimeManager = remember(context) { RuntimeManager(context) }
    val providerRepository = remember(context) { ProviderRepository(context) }
    var prompt by remember { mutableStateOf("") }
    var enterSendsMessage by remember(context) {
        mutableStateOf(
            context.getSharedPreferences("codex_chat_ui", Context.MODE_PRIVATE)
                .getBoolean("enter_sends_message", true)
        )
    }
    val messageListState = rememberLazyListState()
    var followLatestMessage by remember { mutableStateOf(true) }
    LaunchedEffect(messageListState) {
        snapshotFlow {
            val info = messageListState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount == 0 || lastVisible >= info.totalItemsCount - 2
        }.collect { atBottom ->
            followLatestMessage = atBottom
        }
    }
    LaunchedEffect(state.entries.size, state.entries.lastOrNull()?.text, followLatestMessage) {
        if (followLatestMessage && state.entries.isNotEmpty()) {
            messageListState.scrollToItem(state.entries.lastIndex)
        }
    }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var effortMenuExpanded by remember { mutableStateOf(false) }
    var actionMenuThreadId by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<CodexThreadSummary?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var archiveTarget by remember { mutableStateOf<CodexThreadSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<CodexThreadSummary?>(null) }
    val workspaceRepository = remember(context) {
        CodexWorkspaceRepository(runtimeManager.paths.home)
    }
    var showWorkspacePicker by remember { mutableStateOf(false) }
    val workspaceOptions = remember { mutableStateListOf<String>() }
    var newWorkspaceName by remember { mutableStateOf("") }
    var actionsMenuExpanded by remember { mutableStateOf(false) }
    var goalDialogOpen by remember { mutableStateOf(false) }
    var goalText by remember { mutableStateOf("") }
    var goalBudget by remember { mutableStateOf("") }
    var goalStatus by remember { mutableStateOf<String?>(null) }
    var toolsMenuExpanded by remember { mutableStateOf(false) }
    var sessionSearchQuery by remember { mutableStateOf("") }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val handler = remember { Handler(Looper.getMainLooper()) }
    suspend fun ensureAgent(): CodexAgentController {
        state.controller?.takeIf { it.isConnected }?.let { return it }
        val runtime = withContext(Dispatchers.IO) { runtimeManager.inspect() }
        check(runtime.phase == RuntimePhase.RUNTIME_READY) {
            "Codex 私有运行环境尚未就绪：${runtime.message}"
        }
        check(providerRepository.getDefaultCodexAgent() != null) {
            "还没有设置 Codex Agent 服务商。请先添加兼容 Responses API 的服务商。"
        }
        return withContext(Dispatchers.IO) {
            CodexAgentRuntimeFactory(context).start(listener = state.appServerListener)
        }.also {
            state.controller = it
            state.status = "已连接 Codex App Server"
        }
    }

    fun openWorkspacePicker() {
        scope.launch {
            state.isSubmitting = true
            state.error = null
            try {
                val projects = withContext(Dispatchers.IO) {
                    workspaceRepository.list().map { it.canonicalPath }
                }
                workspaceOptions.clear()
                workspaceOptions.addAll(projects)
                showWorkspacePicker = true
            } catch (error: Exception) {
                state.error = error.message?.take(600) ?: "读取项目目录失败"
            } finally {
                state.isSubmitting = false
            }
        }
    }

    suspend fun applyWorkspaceSelection(path: String) {
        val selected = withContext(Dispatchers.IO) {
            workspaceRepository.validate(File(path)).canonicalPath
        }
        state.threadId = null
        state.workingDirectory = selected
        state.activeTurnId = null
        state.isWorking = false
        state.entries.clear()
        state.pendingApproval = null
        state.pendingUserInput = null
        state.userInputAnswers.clear()
        state.showRecentThreads = false
        state.status = "已切换项目；旧会话仍保留在历史记录中"
        showWorkspacePicker = false
    }

    fun selectWorkspace(path: String) {
        if (state.isWorking || state.isSubmitting) return
        scope.launch {
            state.isSubmitting = true
            state.error = null
            try {
                applyWorkspaceSelection(path)
            } catch (error: Exception) {
                state.error = error.message?.take(600) ?: "切换项目失败"
            } finally {
                state.isSubmitting = false
            }
        }
    }

    fun createWorkspace() {
        val name = newWorkspaceName.trim()
        if (name.isBlank() || state.isWorking || state.isSubmitting) return
        scope.launch {
            state.isSubmitting = true
            state.error = null
            try {
                val created = withContext(Dispatchers.IO) {
                    workspaceRepository.create(name).canonicalPath
                }
                if (created !in workspaceOptions) workspaceOptions.add(created)
                newWorkspaceName = ""
                applyWorkspaceSelection(created)
            } catch (error: Exception) {
                state.error = error.message?.take(600) ?: "创建项目失败"
            } finally {
                state.isSubmitting = false
            }
        }
    }

    suspend fun loadThreads(agent: CodexAgentController) {
        val response = withContext(Dispatchers.IO) {
            agent.listThreads(archived = state.showArchivedThreads, limit = 30)
        }
        state.replaceRecentThreads(response)
        state.showRecentThreads = true
    }

    fun refreshThreads() {
        scope.launch {
            state.isSubmitting = true
            state.error = null
            try {
                loadThreads(ensureAgent())
            } catch (error: Exception) {
                state.error = error.message?.take(600) ?: "读取会话列表失败"
            } finally {
                state.isSubmitting = false
            }
        }
    }

    fun compactCurrentThread() {
        val threadId = state.threadId ?: return
        if (state.isWorking || state.isSubmitting) return
        scope.launch {
            state.isSubmitting = true
            state.error = null
            try {
                val agent = ensureAgent()
                withContext(Dispatchers.IO) { agent.compactThread(threadId) }
                state.status = "已请求压缩当前会话上下文"
            } catch (error: Exception) {
                state.error = error.message?.take(600) ?: "压缩会话失败"
            } finally {
                state.isSubmitting = false
            }
        }
    }

    fun reviewUncommittedChanges() {
        val threadId = state.threadId ?: return
        if (state.isWorking || state.isSubmitting) return
        scope.launch {
            state.isSubmitting = true
            state.error = null
            try {
                val agent = ensureAgent()
                withContext(Dispatchers.IO) {
                    agent.startReview(
                        threadId,
                        JSONObject().put("type", "uncommittedChanges")
                    )
                }
                state.status = "已启动代码审查：暂存、未暂存和未跟踪文件"
                state.isWorking = true
            } catch (error: Exception) {
                state.error = error.message?.take(600) ?: "启动代码审查失败"
            } finally {
                state.isSubmitting = false
            }
        }
    }

    fun openGoalEditor() {
        val threadId = state.threadId ?: return
        if (state.isSubmitting || state.isWorking) return
        scope.launch {
            state.isSubmitting = true
            state.error = null
            try {
                val agent = ensureAgent()
                val response = withContext(Dispatchers.IO) { agent.getGoal(threadId) }
                val goal = response.optJSONObject("goal")
                goalText = goal?.optString("objective").orEmpty()
                goalBudget = goal?.opt("tokenBudget")
                    ?.takeIf { it != JSONObject.NULL }
                    ?.toString()
                    .orEmpty()
                goalStatus = goal?.optString("status")
                goalDialogOpen = true
            } catch (error: Exception) {
                state.error = error.message?.take(600) ?: "读取会话目标失败"
            } finally {
                state.isSubmitting = false
            }
        }
    }

    fun saveGoal() {
        val threadId = state.threadId ?: return
        val objective = goalText.trim()
        if (objective.isBlank() || objective.length > 4000) {
            state.error = "目标内容不能为空，且不能超过 4000 个字符"
            return
        }
        val budget = goalBudget.trim().takeIf { it.isNotEmpty() }?.toLongOrNull()
        if (goalBudget.isNotBlank() && (budget == null || budget <= 0L)) {
            state.error = "Token 预算必须是大于 0 的整数，或者留空表示不设预算"
            return
        }
        scope.launch {
            state.isSubmitting = true
            state.error = null
            try {
                val agent = ensureAgent()
                withContext(Dispatchers.IO) {
                    agent.setGoal(
                        threadId = threadId,
                        objective = objective,
                        tokenBudget = budget,
                        status = "active"
                    )
                }
                goalDialogOpen = false
                goalStatus = "active"
                state.status = "已保存本会话目标"
            } catch (error: Exception) {
                state.error = error.message?.take(600) ?: "保存会话目标失败"
            } finally {
                state.isSubmitting = false
            }
        }
    }

    fun clearGoal() {
        val threadId = state.threadId ?: return
        scope.launch {
            state.isSubmitting = true
            state.error = null
            try {
                val agent = ensureAgent()
                withContext(Dispatchers.IO) { agent.clearGoal(threadId) }
                goalDialogOpen = false
                goalText = ""
                goalBudget = ""
                goalStatus = null
                state.status = "已清除本会话目标"
            } catch (error: Exception) {
                state.error = error.message?.take(600) ?: "清除会话目标失败"
            } finally {
                state.isSubmitting = false
            }
        }
    }

    fun refreshModels() {
        scope.launch {
            state.isSubmitting = true
            state.error = null
            try {
                val agent = ensureAgent()
                val response = withContext(Dispatchers.IO) { agent.listModels(includeHidden = false) }
                state.replaceModels(response)
                state.status = if (state.models.isEmpty()) "服务商没有返回可用模型" else "已更新模型列表"
            } catch (error: Exception) {
                state.error = error.message?.take(600) ?: "读取模型列表失败"
            } finally {
                state.isSubmitting = false
            }
        }
    }

    fun resumeThread(summary: CodexThreadSummary) {
        scope.launch {
            state.isSubmitting = true
            state.error = null
            try {
                val agent = ensureAgent()
                val resumed = withContext(Dispatchers.IO) {
                    agent.resumeThread(summary.id, summary.workingDirectory)
                }
                val history = withContext(Dispatchers.IO) {
                    agent.listItems(resumed.id, limit = 100, sortDirection = "asc")
                }
                state.threadId = resumed.id
                state.workingDirectory = summary.workingDirectory
                state.activeTurnId = null
                state.isWorking = false
                state.restoreHistory(history)
                state.showRecentThreads = false
                state.status = "已恢复会话"
            } catch (error: Exception) {
                state.error = error.message?.take(600) ?: "恢复会话失败"
            } finally {
                state.isSubmitting = false
            }
        }
    }

    fun forkThread(summary: CodexThreadSummary) {
        scope.launch {
            state.isSubmitting = true
            state.error = null
            try {
                val agent = ensureAgent()
                val forked = withContext(Dispatchers.IO) {
                    agent.forkThread(summary.id)
                }
                val history = withContext(Dispatchers.IO) {
                    agent.listItems(forked.id, limit = 100, sortDirection = "asc")
                }
                state.threadId = forked.id
                state.workingDirectory = summary.workingDirectory
                state.activeTurnId = null
                state.isWorking = false
                state.restoreHistory(history)
                state.showRecentThreads = false
                state.status = "已从选定会话创建分支"
            } catch (error: Exception) {
                state.error = error.message?.take(600) ?: "创建会话分支失败"
            } finally {
                state.isSubmitting = false
            }
        }
    }

    fun renameThread(summary: CodexThreadSummary, newName: String) {
        scope.launch {
            state.isSubmitting = true
            state.error = null
            try {
                val agent = ensureAgent()
                withContext(Dispatchers.IO) { agent.renameThread(summary.id, newName) }
                state.status = "会话名称已更新"
                loadThreads(agent)
            } catch (error: Exception) {
                state.error = error.message?.take(600) ?: "重命名会话失败"
            } finally {
                state.isSubmitting = false
            }
        }
    }

    fun archiveThread(summary: CodexThreadSummary) {
        scope.launch {
            state.isSubmitting = true
            state.error = null
            try {
                val agent = ensureAgent()
                withContext(Dispatchers.IO) { agent.archiveThread(summary.id) }
                if (state.threadId == summary.id) {
                    state.threadId = null
                    state.workingDirectory = null
                    state.activeTurnId = null
                    state.entries.clear()
                    state.isWorking = false
                }
                state.showArchivedThreads = false
                state.status = "会话已归档"
                loadThreads(agent)
            } catch (error: Exception) {
                state.error = error.message?.take(600) ?: "归档会话失败"
            } finally {
                state.isSubmitting = false
            }
        }
    }

    fun unarchiveThread(summary: CodexThreadSummary) {
        scope.launch {
            state.isSubmitting = true
            state.error = null
            try {
                val agent = ensureAgent()
                withContext(Dispatchers.IO) { agent.unarchiveThread(summary.id) }
                state.showArchivedThreads = false
                state.status = "会话已恢复到最近列表"
                loadThreads(agent)
            } catch (error: Exception) {
                state.error = error.message?.take(600) ?: "恢复归档会话失败"
            } finally {
                state.isSubmitting = false
            }
        }
    }

    fun deleteThread(summary: CodexThreadSummary) {
        scope.launch {
            state.isSubmitting = true
            state.error = null
            try {
                val agent = ensureAgent()
                withContext(Dispatchers.IO) { agent.deleteThread(summary.id) }
                if (state.threadId == summary.id) {
                    state.threadId = null
                    state.workingDirectory = null
                    state.activeTurnId = null
                    state.entries.clear()
                    state.isWorking = false
                }
                state.status = "会话已删除"
                loadThreads(agent)
            } catch (error: Exception) {
                state.error = error.message?.take(600) ?: "删除会话失败"
            } finally {
                state.isSubmitting = false
            }
        }
    }

    fun startNewConversation() {
        state.threadId = null
        state.activeTurnId = null
        state.isWorking = false
        state.entries.clear()
        state.pendingApproval = null
        state.showRecentThreads = false
        state.status = "准备开始新会话"
    }

    fun monitorTurn(agent: CodexAgentController, threadId: String, turnId: String) {
        scope.launch {
            var lastStatus = "inProgress"
            var queryFailure: String? = null
            repeat(15) {
                delay(4_000L)
                if (state.threadId != threadId || state.activeTurnId != turnId || !state.isWorking) {
                    return@launch
                }
                try {
                    val response = withContext(Dispatchers.IO) {
                        agent.listTurns(threadId, limit = 10, sortDirection = "desc")
                    }
                    if (state.threadId != threadId || state.activeTurnId != turnId || !state.isWorking) {
                        return@launch
                    }
                    val data = response.optJSONArray("data") ?: JSONArray()
                    val turn = (0 until data.length())
                        .mapNotNull { data.optJSONObject(it) }
                        .firstOrNull { it.optString("id") == turnId }
                    if (turn == null) {
                        state.status = "消息已提交，正在核对服务端执行记录…"
                        return@repeat
                    }
                    lastStatus = turn.optString("status").ifBlank { "inProgress" }
                    queryFailure = null
                    when (lastStatus) {
                        "completed", "failed", "interrupted" -> {
                            val turnItems = turn.optJSONArray("items") ?: JSONArray()
                            var hasAssistantText = state.containsAssistantMessage(turnItems)
                            state.appendCompletedTurnItems(turn)
                            var itemSyncFailure: String? = null
                            if (!hasAssistantText && lastStatus == "completed") {
                                try {
                                    val listed = withContext(Dispatchers.IO) {
                                        agent.listItems(
                                            threadId,
                                            turnId = turnId,
                                            limit = 200,
                                            sortDirection = "asc"
                                        )
                                    }
                                    if (state.threadId != threadId || state.activeTurnId != turnId) {
                                        return@launch
                                    }
                                    hasAssistantText = state.responseContainsAssistantMessage(listed)
                                    state.appendListedTurnItems(listed)
                                } catch (failure: Exception) {
                                    itemSyncFailure = failure.message ?: "未知错误"
                                }
                            }
                            state.isWorking = false
                            state.activeTurnId = null
                            val failure = state.describeTurnError(turn)
                            when (lastStatus) {
                                "failed" -> {
                                    state.error = failure ?: "本轮已失败，但服务端没有提供具体错误信息"
                                    state.status = "任务执行失败"
                                }
                                "interrupted" -> {
                                    state.error = null
                                    state.status = "任务已中断"
                                }
                                else -> {
                                    state.error = when {
                                        hasAssistantText -> null
                                        itemSyncFailure != null ->
                                            "本轮已结束，但读取最终消息失败：$itemSyncFailure"
                                        else ->
                                            "服务端已完成本轮，但当前轮次没有返回助手正文。请检查中转站是否正确处理 Responses API。"
                                    }
                                    state.status = if (hasAssistantText) {
                                        "已从服务端同步当前轮次最终回复"
                                    } else {
                                        "本轮已结束，但没有可显示的回复"
                                    }
                                }
                            }
                            return@launch
                        }
                        else -> {
                            state.error = null
                            state.status = "Codex 正在执行（服务端状态：$lastStatus）"
                        }
                    }
                } catch (error: Exception) {
                    queryFailure = error.message ?: "未知错误"
                    state.error = "已提交消息，但读取服务端执行状态失败：$queryFailure"
                    state.status = "无法确认 Codex 是否正在执行"
                }
            }
            if (state.threadId == threadId && state.activeTurnId == turnId && state.isWorking) {
                state.error = queryFailure?.let {
                    "已提交消息，但连续查询服务端状态失败：$it"
                } ?: "服务端仍未结束本轮（状态：$lastStatus），客户端没有收到最终回复。请检查中转站的 Responses API 流式兼容性。"
                state.status = "等待服务端回复超时"
            }
        }
    }

    fun connectAndSend(text: String) {
        if (state.isSubmitting || text.isBlank()) return
        scope.launch {
            state.isSubmitting = true
            state.error = null
            state.status = "正在连接 Codex 并提交消息…"
            state.entries.add(
                CodexChatEntry(
                    id = "user:${System.nanoTime()}",
                    title = "你",
                    text = text,
                    isUser = true
                )
            )
            try {
                val agent = ensureAgent()
                val threadId = state.threadId ?: run {
                    val cwd = withContext(Dispatchers.IO) {
                        state.workingDirectory ?: defaultWorkspace(runtimeManager.paths.home)
                    }
                    withContext(Dispatchers.IO) {
                        agent.startThread(cwd, model = state.selectedModelId).id
                    }.also {
                        state.threadId = it
                        state.workingDirectory = cwd
                        state.status = "已创建新会话"
                    }
                }

                val activeTurn = state.activeTurnId
                if (state.isWorking && !activeTurn.isNullOrBlank()) {
                    withContext(Dispatchers.IO) {
                        agent.steerTurn(threadId, activeTurn, text)
                    }
                    state.status = "补充指令已发送，正在等待 Codex 处理"
                } else {
                    val cwd = state.workingDirectory ?: defaultWorkspace(runtimeManager.paths.home)
                    state.isWorking = true
                    val turn = withContext(Dispatchers.IO) {
                        agent.startTurn(
                            threadId,
                            text,
                            cwd,
                            model = state.selectedModelId,
                            effort = state.selectedReasoningEffort
                        )
                    }
                    if (state.isWorking) state.activeTurnId = turn.id
                    state.status = "消息已提交，正在等待 Codex 回复"
                    monitorTurn(agent, threadId, turn.id)

                }
            } catch (error: Exception) {
                state.isWorking = false
                state.activeTurnId = null
                state.error = error.message?.take(600) ?: "连接 Codex 时发生未知错误"
                state.status = "消息未能完成提交"
            } finally {
                state.isSubmitting = false
            }
        }
    }

    fun submitPrompt() {
        val text = prompt.trim()
        if (text.isBlank() || state.isSubmitting) return
        prompt = ""
        followLatestMessage = true
        connectAndSend(text)
    }

    fun submitUserInput() {
        val pending = state.pendingUserInput ?: return
        val agent = state.controller ?: return
        scope.launch {
            try {
                val answers = JSONObject()
                pending.questions.forEach { question ->
                    val values = JSONArray()
                    state.userInputAnswers[question.id]
                        ?.takeIf { it.isNotBlank() }
                        ?.let { values.put(it) }
                    answers.put(
                        question.id,
                        JSONObject().put("answers", values)
                    )
                }
                withContext(Dispatchers.IO) {
                    agent.respondToServerRequest(
                        pending.requestId,
                        JSONObject().put("answers", answers)
                    )
                }
                state.pendingUserInput = null
                state.userInputAnswers.clear()
                state.status = "已提交回答，Codex 将继续执行"
            } catch (error: Exception) {
                state.error = error.message ?: "提交回答失败"
            }
        }
    }

    fun answerApproval(decision: String) {
        val pending = state.pendingApproval ?: return
        val agent = state.controller ?: return
        if (!pending.supported) return
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    agent.respondToServerRequest(
                        pending.requestId,
                        JSONObject().put("decision", decision)
                    )
                }
                state.pendingApproval = null
                state.status = if (decision.startsWith("accept")) "已提交审批决定" else "已拒绝该操作"
            } catch (error: Exception) {
                state.error = error.message ?: "提交审批决定失败"
            }
        }
    }

    fun rejectUnsupportedRequest() {
        val pending = state.pendingApproval ?: return
        val agent = state.controller ?: return
        if (pending.supported) return
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    agent.rejectServerRequest(
                        pending.requestId,
                        -32601,
                        "当前 Android 客户端暂不支持此请求，已安全拒绝"
                    )
                }
                state.pendingApproval = null
                state.status = "已拒绝当前不支持的请求"
            } catch (error: Exception) {
                state.error = error.message ?: "拒绝请求失败"
            }
        }
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名会话") },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    singleLine = true,
                    label = { Text("会话名称") }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = renameValue.trim()
                        if (name.isNotBlank()) {
                            renameTarget = null
                            renameThread(target, name)
                        }
                    },
                    enabled = renameValue.isNotBlank() && !state.isSubmitting
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } }
        )
    }

    archiveTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { archiveTarget = null },
            title = { Text("归档会话？") },
            text = { Text("“${target.title}”会从最近会话列表移到已归档列表。以后仍可在归档列表中恢复。") },
            confirmButton = {
                Button(
                    onClick = {
                        archiveTarget = null
                        archiveThread(target)
                    },
                    enabled = !state.isSubmitting
                ) { Text("归档") }
            },
            dismissButton = { TextButton(onClick = { archiveTarget = null }) { Text("取消") } }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("永久删除会话？") },
            text = { Text("将从 Codex 本地会话记录中删除“${target.title}”。此操作无法撤销。") },
            confirmButton = {
                Button(
                    onClick = {
                        deleteTarget = null
                        deleteThread(target)
                    },
                    enabled = !state.isSubmitting
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }

    if (goalDialogOpen) {
        AlertDialog(
            onDismissRequest = { if (!state.isSubmitting) goalDialogOpen = false },
            title = { Text("本会话目标") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = goalText,
                        onValueChange = { goalText = it.take(4000) },
                        label = { Text("希望 Codex 完成什么") },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = goalBudget,
                        onValueChange = { goalBudget = it.filter { char -> char.isDigit() }.take(12) },
                        label = { Text("Token 预算（可留空）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    goalStatus?.let {
                        Text("当前目标状态：$it", style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { saveGoal() },
                    enabled = !state.isSubmitting && goalText.isNotBlank()
                ) { Text("保存目标") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { clearGoal() }, enabled = !state.isSubmitting) {
                        Text("清除目标")
                    }
                    TextButton(onClick = { goalDialogOpen = false }, enabled = !state.isSubmitting) {
                        Text("关闭")
                    }
                }
            }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            // RikkaHub uses a 300dp chat-history drawer; keep this panel compact on phones.
            ModalDrawerSheet(modifier = Modifier.width(300.dp)) {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Text(
                            "Codex",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        TextButton(
                            onClick = {
                                startNewConversation()
                                scope.launch { drawerState.close() }
                            },
                            enabled = !state.isSubmitting && !state.isWorking
                        ) { Text("＋ 新建") }
                    }
                    TextButton(
                        onClick = {
                            scope.launch { drawerState.close() }
                            openWorkspacePicker()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.isSubmitting && !state.isWorking
                    ) {
                        Text(
                            "项目 · ${state.workingDirectory?.substringAfterLast('/') ?: "选择工作区"}",
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 1
                        )
                    }
                    Divider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = {
                                state.showArchivedThreads = false
                                refreshThreads()
                            },
                            enabled = !state.isSubmitting && state.showArchivedThreads,
                            modifier = Modifier.weight(1f)
                        ) { Text("最近") }
                        TextButton(
                            onClick = {
                                state.showArchivedThreads = true
                                refreshThreads()
                            },
                            enabled = !state.isSubmitting && !state.showArchivedThreads,
                            modifier = Modifier.weight(1f)
                        ) { Text("已归档") }
                        TextButton(
                            onClick = { refreshThreads() },
                            enabled = !state.isSubmitting
                        ) { Text("刷新") }
                    }
                    OutlinedTextField(
                        value = sessionSearchQuery,
                        onValueChange = { sessionSearchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("筛选会话") }
                    )
                    val visibleThreads = state.recentThreads.filter { summary ->
                        sessionSearchQuery.isBlank() ||
                            summary.title.contains(sessionSearchQuery, ignoreCase = true) ||
                            summary.preview.contains(sessionSearchQuery, ignoreCase = true)
                    }
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        if (visibleThreads.isEmpty()) {
                            item {
                                Text(
                                    when {
                                        state.isSubmitting -> "正在读取会话…"
                                        state.recentThreads.isEmpty() -> "这里还没有会话记录"
                                        else -> "没有找到匹配的会话"
                                    },
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        items(visibleThreads, key = { it.id }) { summary ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                            ) {
                                TextButton(
                                    onClick = {
                                        if (summary.id != state.threadId) resumeThread(summary)
                                        scope.launch { drawerState.close() }
                                    },
                                    modifier = Modifier.weight(1f),
                                    enabled = !state.isSubmitting &&
                                        (!state.isWorking || summary.id == state.threadId)
                                ) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(
                                            summary.title,
                                            maxLines = 1,
                                            fontWeight = if (summary.id == state.threadId) {
                                                FontWeight.SemiBold
                                            } else {
                                                FontWeight.Normal
                                            }
                                        )
                                        if (summary.preview.isNotBlank() && summary.preview != summary.title) {
                                            Text(
                                                summary.preview,
                                                style = MaterialTheme.typography.labelSmall,
                                                maxLines = 1,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                                Box {
                                    TextButton(
                                        onClick = { actionMenuThreadId = summary.id },
                                        enabled = !state.isSubmitting
                                    ) { Text("⋯") }
                                    DropdownMenu(
                                        expanded = actionMenuThreadId == summary.id,
                                        onDismissRequest = { actionMenuThreadId = null }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("从这里分支") },
                                            onClick = {
                                                actionMenuThreadId = null
                                                forkThread(summary)
                                                scope.launch { drawerState.close() }
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("重命名") },
                                            onClick = {
                                                actionMenuThreadId = null
                                                renameTarget = summary
                                                renameValue = summary.title
                                                scope.launch { drawerState.close() }
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(if (state.showArchivedThreads) "取消归档" else "归档") },
                                            onClick = {
                                                actionMenuThreadId = null
                                                if (state.showArchivedThreads) unarchiveThread(summary)
                                                else archiveTarget = summary
                                                scope.launch { drawerState.close() }
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("删除会话") },
                                            onClick = {
                                                actionMenuThreadId = null
                                                deleteTarget = summary
                                                scope.launch { drawerState.close() }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Divider()
                    Text(
                        "工具与设置",
                        modifier = Modifier.padding(start = 8.dp, top = 2.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        TextButton(
                            onClick = {
                                scope.launch { drawerState.close() }
                                onOpenTerminal()
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("终端") }
                        TextButton(
                            onClick = {
                                scope.launch { drawerState.close() }
                                onOpenMcp()
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("MCP 工具") }
                    }
                    TextButton(
                        onClick = {
                            scope.launch { drawerState.close() }
                            onOpenProviders()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("服务商与模型设置") }
                    if (providerRepository.getDefaultCodexAgent() == null) {
                        Text(
                            "尚未配置兼容 Responses API 的服务商",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    ) {
        Column(
            modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        scope.launch {
                            drawerState.open()
                            if (state.recentThreads.isEmpty()) refreshThreads()
                        }
                    }
                ) { Text("☰", style = MaterialTheme.typography.titleLarge) }
                Column(Modifier.weight(1f)) {
                    Text(
                        state.recentThreads.firstOrNull { it.id == state.threadId }?.title ?: "Codex",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Text(
                        state.workingDirectory?.substringAfterLast('/') ?: "选择项目工作区",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                TextButton(
                    onClick = { startNewConversation() },
                    enabled = !state.isSubmitting && !state.isWorking
                ) { Text("新建") }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Box {
                    TextButton(
                        onClick = {
                            if (state.models.isEmpty()) refreshModels()
                            else modelMenuExpanded = true
                        },
                        enabled = !state.isSubmitting
                    ) {
                        Text(
                            state.models.firstOrNull { it.id == state.selectedModelId }?.displayName
                                ?: "选择模型"
                        )
                    }
                    DropdownMenu(
                        expanded = modelMenuExpanded,
                        onDismissRequest = { modelMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("刷新模型列表") },
                            onClick = {
                                modelMenuExpanded = false
                                refreshModels()
                            }
                        )
                        state.models.forEach { model ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(model.displayName)
                                        if (model.description.isNotBlank()) {
                                            Text(
                                                model.description,
                                                style = MaterialTheme.typography.labelSmall,
                                                maxLines = 2
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    state.selectModel(model)
                                    modelMenuExpanded = false
                                }
                            )
                        }
                    }
                }
                Box {
                    TextButton(
                        onClick = { effortMenuExpanded = true },
                        enabled = state.models.firstOrNull { it.id == state.selectedModelId }
                            ?.reasoningEfforts?.isNotEmpty() == true
                    ) {
                        Text(state.selectedReasoningEffort ?: "默认推理")
                    }
                    DropdownMenu(
                        expanded = effortMenuExpanded,
                        onDismissRequest = { effortMenuExpanded = false }
                    ) {
                        state.models.firstOrNull { it.id == state.selectedModelId }
                            ?.reasoningEfforts?.forEach { effort ->
                                DropdownMenuItem(
                                    text = { Text(effort) },
                                    onClick = {
                                        state.selectedReasoningEffort = effort
                                        effortMenuExpanded = false
                                    }
                                )
                            }
                    }
                }
                Spacer(Modifier.weight(1f))
                Box {
                    TextButton(
                        onClick = { actionsMenuExpanded = true },
                        enabled = state.threadId != null && !state.isSubmitting
                    ) { Text("更多") }
                    DropdownMenu(
                        expanded = actionsMenuExpanded,
                        onDismissRequest = { actionsMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("设置会话目标") },
                            onClick = {
                                actionsMenuExpanded = false
                                openGoalEditor()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("压缩上下文") },
                            onClick = {
                                actionsMenuExpanded = false
                                compactCurrentThread()
                            },
                            enabled = !state.isWorking
                        )
                        DropdownMenuItem(
                            text = { Text("审查未提交改动") },
                            onClick = {
                                actionsMenuExpanded = false
                                reviewUncommittedChanges()
                            },
                            enabled = !state.isWorking
                        )
                    }
                }
            }
            Text(
                state.error?.let { "需要留意：$it" } ?: when {
                    state.pendingApproval != null || state.pendingUserInput != null -> "等待你处理一项请求"
                    state.isWorking -> state.status
                    state.controller?.isConnected == true -> "Codex 已连接 · ${state.status}"
                    else -> state.status
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (state.error != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
        if (showWorkspacePicker) {
            AlertDialog(
                onDismissRequest = { if (!state.isSubmitting) showWorkspacePicker = false },
                title = { Text("选择项目工作区") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "项目目录限定在应用自己的 HOME/projects 中。切换项目会新建会话，旧会话不会删除。",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (workspaceOptions.isEmpty()) {
                            Text("还没有项目，可以在下面创建一个。")
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().height(220.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(workspaceOptions, key = { it }) { path ->
                                    TextButton(
                                        onClick = { selectWorkspace(path) },
                                        enabled = !state.isSubmitting && !state.isWorking,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(Modifier.fillMaxWidth()) {
                                            Text(path.substringAfterLast('/'))
                                            Text(
                                                path,
                                                style = MaterialTheme.typography.labelSmall,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        OutlinedTextField(
                            value = newWorkspaceName,
                            onValueChange = { newWorkspaceName = it },
                            label = { Text("新项目名称") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedButton(
                            onClick = { createWorkspace() },
                            enabled = newWorkspaceName.isNotBlank() && !state.isSubmitting && !state.isWorking
                        ) { Text("创建并切换") }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { showWorkspacePicker = false },
                        enabled = !state.isSubmitting
                    ) { Text("关闭") }
                }
            )
        }

        state.error?.let { error ->
            Card {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("遇到问题", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                    Text(error, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { state.error = null }) { Text("知道了") }
                }
            }
        }

        state.pendingUserInput?.let { pending ->
            Card {
                Column(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Codex 需要你回答", fontWeight = FontWeight.SemiBold)
                    if (pending.questions.isEmpty()) {
                        Text("收到的问题没有可显示的内容。", style = MaterialTheme.typography.bodySmall)
                    }
                    pending.questions.forEach { question ->
                        Text(question.header, style = MaterialTheme.typography.labelMedium)
                        Text(question.question, style = MaterialTheme.typography.bodyMedium)
                        if (question.options.isNotEmpty()) {
                            question.options.forEach { option ->
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    androidx.compose.material3.RadioButton(
                                        selected = state.userInputAnswers[question.id] == option.label,
                                        onClick = {
                                            state.userInputAnswers[question.id] = option.label
                                        }
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(option.label, style = MaterialTheme.typography.bodyMedium)
                                        if (option.description.isNotBlank()) {
                                            Text(option.description, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                            if (question.options.any { it.label.equals("其他", true) || it.label.equals("other", true) }) {
                                OutlinedTextField(
                                    value = state.userInputAnswers[question.id].orEmpty(),
                                    onValueChange = { state.userInputAnswers[question.id] = it },
                                    label = { Text("补充回答") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        } else {
                            OutlinedTextField(
                                value = state.userInputAnswers[question.id].orEmpty(),
                                onValueChange = { state.userInputAnswers[question.id] = it },
                                label = { Text("你的回答") },
                                singleLine = true,
                                visualTransformation = if (question.isSecret) PasswordVisualTransformation()
                                    else androidx.compose.ui.text.input.VisualTransformation.None,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { submitUserInput() },
                            enabled = !state.isSubmitting && pending.questions.isNotEmpty()
                        ) { Text("提交回答") }
                        if (pending.isBlocking) {
                            Text("当前任务正在等待回答", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        state.pendingApproval?.let { pending ->
            Card {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("等待你确认", fontWeight = FontWeight.SemiBold)
                    Text(
                        when (pending.method) {
                            "item/commandExecution/requestApproval" -> "Codex 请求执行一条命令。请确认后再继续。"
                            "item/fileChange/requestApproval" -> "Codex 请求应用文件修改。请确认后再继续。"
                            else -> "收到尚未支持的请求：${pending.method}。为安全起见，不会自动批准。"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    val detail = buildString {
                        val reason = pending.params.optString("reason")
                        val command = pending.params.optString("command")
                        val cwd = pending.params.optString("cwd")
                        if (reason.isNotBlank()) append(reason)
                        if (command.isNotBlank()) {
                            if (isNotEmpty()) append("\n")
                            append("命令：").append(command)
                        }
                        if (cwd.isNotBlank()) {
                            if (isNotEmpty()) append("\n")
                            append("工作目录：").append(cwd)
                        }
                    }
                    if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall)
                    if (pending.supported) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { answerApproval("accept") }) { Text("允许一次") }
                            OutlinedButton(onClick = { answerApproval("acceptForSession") }) { Text("本会话允许") }
                            TextButton(onClick = { answerApproval("decline") }) { Text("拒绝") }
                        }
                    } else {
                        Text(
                            "当前版本还不能处理这种请求。为避免智能体一直等待，可以安全拒绝它。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        OutlinedButton(onClick = { rejectUnsupportedRequest() }) {
                            Text("拒绝这个请求")
                        }
                    }
                }
            }
        }

        LazyColumn(
            state = messageListState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (state.entries.isEmpty()) {
                item {
                    Text(
                        "这里会显示 Codex 的回复、执行步骤、命令输出和文件变更。\n首次使用前，请先安装私有运行环境并配置 Responses API 服务商。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            items(state.entries, key = { it.id }) { entry ->
                when {
                    entry.isUser -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Surface(
                                modifier = Modifier.widthIn(max = 340.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Text(
                                    entry.text,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                    entry.isActivity -> {
                        var expanded by remember(entry.id) { mutableStateOf(false) }
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Text(
                                        entry.title,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    TextButton(onClick = { expanded = !expanded }) {
                                        Text(if (expanded) "收起" else "详情")
                                    }
                                }
                                Text(
                                    if (expanded || entry.text.length <= 220) entry.text else entry.text.take(220) + "…",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                    else -> {
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                entry.title.ifBlank { "Codex" },
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(entry.text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Column(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        modifier = Modifier.weight(1f),
                        minLines = 1,
                        maxLines = 4,
                        enabled = !state.isSubmitting &&
                            state.pendingApproval == null && state.pendingUserInput == null,
                        placeholder = { Text("给 Codex 安排任务…") },
                        keyboardOptions = KeyboardOptions(
                            imeAction = if (enterSendsMessage) ImeAction.Send else ImeAction.Default
                        ),
                        keyboardActions = KeyboardActions(
                            onSend = { if (enterSendsMessage) submitPrompt() }
                        )
                    )
                    Button(
                        onClick = { submitPrompt() },
                        enabled = prompt.isNotBlank() && !state.isSubmitting &&
                            state.pendingApproval == null && state.pendingUserInput == null
                    ) {
                        if (state.isSubmitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(if (state.isWorking) "追加" else "发送")
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Box {
                        TextButton(onClick = { toolsMenuExpanded = true }) {
                            Text("＋ 工具")
                        }
                        DropdownMenu(
                            expanded = toolsMenuExpanded,
                            onDismissRequest = { toolsMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("选择项目工作区") },
                                onClick = {
                                    toolsMenuExpanded = false
                                    openWorkspacePicker()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("设置本会话目标") },
                                onClick = {
                                    toolsMenuExpanded = false
                                    openGoalEditor()
                                },
                                enabled = state.threadId != null && !state.isWorking
                            )
                            DropdownMenuItem(
                                text = { Text("审查未提交改动") },
                                onClick = {
                                    toolsMenuExpanded = false
                                    reviewUncommittedChanges()
                                },
                                enabled = state.threadId != null && !state.isWorking
                            )
                            DropdownMenuItem(
                                text = { Text("压缩上下文") },
                                onClick = {
                                    toolsMenuExpanded = false
                                    compactCurrentThread()
                                },
                                enabled = state.threadId != null && !state.isWorking
                            )
                            DropdownMenuItem(
                                text = { Text("MCP 工具管理") },
                                onClick = {
                                    toolsMenuExpanded = false
                                    onOpenMcp()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("服务商设置") },
                                onClick = {
                                    toolsMenuExpanded = false
                                    onOpenProviders()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("打开终端") },
                                onClick = {
                                    toolsMenuExpanded = false
                                    onOpenTerminal()
                                }
                            )
                        }
                    }
                    Text(
                        state.workingDirectory?.substringAfterLast('/') ?: "未选择项目",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = {
                            enterSendsMessage = !enterSendsMessage
                            context.getSharedPreferences("codex_chat_ui", Context.MODE_PRIVATE)
                                .edit()
                                .putBoolean("enter_sends_message", enterSendsMessage)
                                .apply()
                        }
                    ) {
                        Text(if (enterSendsMessage) "回车：发送" else "回车：换行")
                    }
                    if (state.isWorking && !state.activeTurnId.isNullOrBlank()) {
                        TextButton(
                            onClick = {
                                val agent = state.controller ?: return@TextButton
                                val thread = state.threadId ?: return@TextButton
                                val turn = state.activeTurnId ?: return@TextButton
                                scope.launch {
                                    runCatching {
                                        withContext(Dispatchers.IO) { agent.interruptTurn(thread, turn) }
                                    }.onSuccess {
                                        state.status = "已发送停止请求"
                                    }.onFailure {
                                        state.error = it.message ?: "停止任务失败"
                                    }
                                }
                            }
                        ) { Text("停止") }
                    }
                }
            }
        }
    }
    }
}

private fun defaultWorkspace(home: File): String =
    CodexWorkspaceRepository(home).defaultWorkspace().canonicalPath
