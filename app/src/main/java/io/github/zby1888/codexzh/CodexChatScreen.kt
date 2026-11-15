package io.github.zby1888.codexzh

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

internal data class CodexThreadSummary(
    val id: String,
    val title: String,
    val preview: String,
    val workingDirectory: String
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
    val recentThreads = mutableStateListOf<CodexThreadSummary>()
    val models = mutableStateListOf<CodexModelOption>()
    var selectedModelId by mutableStateOf<String?>(null)
    var selectedReasoningEffort by mutableStateOf<String?>(null)
    var isWorking by mutableStateOf(false)
    var isSubmitting by mutableStateOf(false)
    var status by mutableStateOf("尚未连接 Codex")
    var error by mutableStateOf<String?>(null)
    var pendingApproval by mutableStateOf<CodexPendingApproval?>(null)
    val entries = mutableStateListOf<CodexChatEntry>()

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
        if (selected != null && selectedReasoningEffort !in selected.reasoningEfforts) {
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

    fun applyNotification(message: JSONObject) {
        val method = message.optString("method")
        val params = message.optJSONObject("params") ?: JSONObject()
        when (method) {
            "turn/started" -> {
                val turn = params.optJSONObject("turn") ?: JSONObject()
                activeTurnId = turn.optString("id").takeIf { it.isNotBlank() }
                isWorking = true
                status = "Codex 正在处理任务"
            }
            "turn/completed" -> {
                val turn = params.optJSONObject("turn") ?: JSONObject()
                val result = turn.optString("status").ifBlank { "completed" }
                isWorking = false
                activeTurnId = null
                status = when (result) {
                    "completed" -> "本轮已完成"
                    "interrupted" -> "任务已中断"
                    "failed" -> "任务执行失败"
                    else -> "本轮结束：$result"
                }
            }
            "item/agentMessage/delta" -> {
                val itemId = params.optString("itemId")
                append("assistant:$itemId", "Codex", params.optString("delta"))
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
    onOpenProviders: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runtimeManager = remember(context) { RuntimeManager(context) }
    val providerRepository = remember(context) { ProviderRepository(context) }
    var prompt by remember { mutableStateOf("") }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var effortMenuExpanded by remember { mutableStateOf(false) }
    val handler = remember { Handler(Looper.getMainLooper()) }
    val listener = remember(state, handler) {
        object : CodexAppServerClient.Listener {
            override fun onNotification(message: JSONObject) {
                handler.post { state.applyNotification(message) }
            }

            override fun onServerRequest(message: JSONObject) {
                val requestId = message.opt("id") ?: return
                val method = message.optString("method")
                val params = message.optJSONObject("params") ?: JSONObject()
                handler.post {
                    state.pendingApproval = CodexPendingApproval(requestId, method, params)
                    state.status = "Codex 正在等待你的操作确认"
                }
            }

            override fun onDiagnostic(message: String) {
                handler.post { state.status = message }
            }
        }
    }

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
            CodexAgentRuntimeFactory(context).start(listener = listener)
        }.also {
            state.controller = it
            state.status = "已连接 Codex App Server"
        }
    }

    fun refreshThreads() {
        scope.launch {
            state.isSubmitting = true
            state.error = null
            try {
                val agent = ensureAgent()
                val response = withContext(Dispatchers.IO) { agent.listThreads(limit = 30) }
                state.replaceRecentThreads(response)
                state.showRecentThreads = true
            } catch (error: Exception) {
                state.error = error.message?.take(600) ?: "读取会话列表失败"
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

    fun startNewConversation() {
        state.threadId = null
        state.workingDirectory = null
        state.activeTurnId = null
        state.isWorking = false
        state.entries.clear()
        state.pendingApproval = null
        state.showRecentThreads = false
        state.status = "准备开始新会话"
    }

    fun connectAndSend(text: String) {
        if (state.isSubmitting || text.isBlank()) return
        scope.launch {
            state.isSubmitting = true
            state.error = null
            state.entries.add(CodexChatEntry(
                id = "user:${System.nanoTime()}",
                title = "你",
                text = text,
                isUser = true
            ))
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
                    state.status = "已把补充指令发送给 Codex"
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
                    state.status = if (state.isWorking) "已发送，正在等待 Codex 回复" else state.status
                }
            } catch (error: Exception) {
                state.isWorking = false
                state.activeTurnId = null
                state.error = error.message?.take(600) ?: "连接 Codex 时发生未知错误"
                state.status = "操作未完成"
            } finally {
                state.isSubmitting = false
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

    Column(
        modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Card {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Codex 智能体", fontWeight = FontWeight.SemiBold)
                    Text(if (state.controller?.let { it.isConnected } == true) "已连接" else "未连接")
                }
                Text(state.status, style = MaterialTheme.typography.bodySmall)
                state.threadId?.let {
                    Text("会话：${it.take(8)}…", style = MaterialTheme.typography.labelSmall)
                }
                if (providerRepository.getDefaultCodexAgent() == null) {
                    Text("还没有设置 Codex Agent 服务商。", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onOpenProviders) { Text("去设置服务商") }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Box {
                    OutlinedButton(
                        onClick = {
                            if (state.models.isEmpty()) refreshModels()
                            else modelMenuExpanded = true
                        },
                        enabled = !state.isSubmitting
                    ) {
                        Text(
                            state.models.firstOrNull { it.id == state.selectedModelId }?.displayName
                                ?: "读取模型列表"
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
            }
            Column {
                Box {
                    OutlinedButton(
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
            }
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
                Card {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(entry.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(entry.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { refreshThreads() }, enabled = !state.isSubmitting) {
                Text("历史会话")
            }
            TextButton(onClick = { startNewConversation() }, enabled = !state.isSubmitting) {
                Text("新建会话")
            }
            state.workingDirectory?.let {
                Text(it.substringAfterLast('/'), style = MaterialTheme.typography.labelSmall)
            }
        }
        if (state.showRecentThreads) {
            Card {
                Column(
                    Modifier.fillMaxWidth().padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("最近会话", fontWeight = FontWeight.SemiBold)
                    if (state.recentThreads.isEmpty()) {
                        Text("还没有可恢复的会话", style = MaterialTheme.typography.bodySmall)
                    } else {
                        LazyColumn(Modifier.height(180.dp)) {
                            items(state.recentThreads, key = { it.id }) { summary ->
                                TextButton(
                                    onClick = { resumeThread(summary) },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !state.isSubmitting
                                ) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(summary.title, maxLines = 1)
                                        if (summary.workingDirectory.isNotBlank()) {
                                            Text(
                                                summary.workingDirectory,
                                                style = MaterialTheme.typography.labelSmall,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    TextButton(onClick = { state.showRecentThreads = false }) { Text("收起") }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                modifier = Modifier.weight(1f),
                minLines = 1,
                maxLines = 4,
                enabled = !state.isSubmitting && state.pendingApproval == null,
                placeholder = { Text("给 Codex 安排任务…") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    val text = prompt.trim()
                    prompt = ""
                    connectAndSend(text)
                })
            )
            Button(
                onClick = {
                    val text = prompt.trim()
                    prompt = ""
                    connectAndSend(text)
                },
                enabled = prompt.isNotBlank() && !state.isSubmitting && state.pendingApproval == null
            ) {
                if (state.isSubmitting) CircularProgressIndicator()
                else Text(if (state.isWorking) "追加" else "发送")
            }
        }
        if (state.isWorking && !state.activeTurnId.isNullOrBlank()) {
            TextButton(onClick = {
                val agent = state.controller ?: return@TextButton
                val thread = state.threadId ?: return@TextButton
                val turn = state.activeTurnId ?: return@TextButton
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) { agent.interruptTurn(thread, turn) }
                    }.onFailure { state.error = it.message ?: "停止任务失败" }
                }
            }) { Text("停止当前任务") }
        }
    }
}

private fun defaultWorkspace(home: File): String {
    val workspace = File(home, "projects/default")
    if (!workspace.exists()) check(workspace.mkdirs()) { "无法创建默认项目目录" }
    val homePath = home.canonicalFile.path.trimEnd(File.separatorChar) + File.separator
    check(workspace.canonicalFile.path.startsWith(homePath)) { "默认项目目录越界，已拒绝使用" }
    check(workspace.isDirectory) { "默认项目路径不是目录" }
    return workspace.canonicalPath
}
