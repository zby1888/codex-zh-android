package io.github.zby1888.codexzh

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun CodexMcpScreen(
    modifier: Modifier = Modifier,
    state: CodexChatUiState,
    onOpenProviders: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runtimeManager = remember(context) { RuntimeManager(context) }
    val providerRepository = remember(context) { ProviderRepository(context) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

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
        }
    }

    fun refresh() {
        if (busy) return
        scope.launch {
            busy = true
            error = null
            message = null
            try {
                val agent = ensureAgent()
                val mcp = agent.mcpController()
                withContext(Dispatchers.IO) { mcp.refreshServers() }
                val result = withContext(Dispatchers.IO) {
                    mcp.listServers(threadId = state.threadId, limit = 50)
                }
                state.replaceMcpServers(result)
                message = "MCP 服务状态已更新"
            } catch (failure: Exception) {
                error = failure.message?.take(600) ?: "读取 MCP 服务状态失败"
            } finally {
                busy = false
            }
        }
    }

    Column(
        modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("MCP 工具", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "查看 Codex 当前加载的 MCP 服务、连接状态和已发现的工具。服务实际连接由私有环境中的 Codex 管理。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = ::refresh, enabled = !busy) {
                        if (busy) CircularProgressIndicator()
                        else Text(if (state.controller?.isConnected == true) "刷新服务状态" else "连接 Codex 并读取")
                    }
                    OutlinedButton(onClick = onOpenProviders) { Text("服务商设置") }
                }
                Text(
                    if (state.controller?.isConnected == true) "Codex App Server：已连接"
                    else "Codex App Server：尚未连接",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }

        error?.let {
            Card {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("读取失败", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                    Text(it, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { error = null }) { Text("知道了") }
                }
            }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }

        if (state.mcpServers.isEmpty()) {
            Card {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("还没有读取到 MCP 服务", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "先点击“连接 Codex 并读取”。如果列表仍为空，请检查应用私有 CODEX_HOME 中的 MCP 配置。下一步会补上在 App 内添加、编辑和删除 MCP 服务的表单。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.mcpServers, key = { it.name }) { server ->
                    Card {
                        Column(
                            Modifier.fillMaxWidth().padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(server.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                Text(runtimeStatusLabel(server.runtimeStatus), style = MaterialTheme.typography.labelMedium)
                            }
                            Text(
                                "认证：${authStatusLabel(server.authStatus)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (server.httpOrigin.isNotBlank()) {
                                Text(server.httpOrigin, style = MaterialTheme.typography.bodySmall)
                            }
                            server.error?.let {
                                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                            Text("已发现 ${server.toolNames.size} 个工具", style = MaterialTheme.typography.labelMedium)
                            server.toolNames.take(30).forEach { tool ->
                                Text("• $tool", style = MaterialTheme.typography.bodySmall)
                            }
                            if (server.toolNames.size > 30) {
                                Text("还有 ${server.toolNames.size - 30} 个工具…", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun runtimeStatusLabel(value: String): String = when (value) {
    "connected" -> "已连接"
    "starting" -> "正在连接"
    "authenticationRequired" -> "需要授权"
    "failed" -> "连接失败"
    "cancelled" -> "已取消"
    "disabled" -> "已停用"
    "notStarted" -> "未启动"
    else -> "状态未知"
}

private fun authStatusLabel(value: String): String = when (value) {
    "notLoggedIn" -> "尚未登录"
    "bearerToken" -> "令牌认证"
    "oAuth" -> "OAuth"
    "unsupported" -> "暂不支持"
    else -> "未知"
}
