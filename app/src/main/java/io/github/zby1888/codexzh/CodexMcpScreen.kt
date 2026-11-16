package io.github.zby1888.codexzh

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
    val secretRepository = remember(context) { McpServerSecretRepository(context) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var transport by remember { mutableStateOf("stdio") }
    var serverName by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var argumentsText by remember { mutableStateOf("") }
    var workingDirectory by remember { mutableStateOf("") }
    var environmentText by remember { mutableStateOf("") }
    var httpUrl by remember { mutableStateOf("") }
    var bearerToken by remember { mutableStateOf("") }
    var httpHeadersText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<String?>(null) }

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

    suspend fun readServers(agent: CodexAgentController) {
        val mcp = agent.mcpController()
        val result = withContext(Dispatchers.IO) {
            mcp.listServers(threadId = state.threadId, limit = 50)
        }
        state.replaceMcpServers(result)
    }

    fun saveServer() {
        val cleanName = serverName.trim()
        val secretValues = linkedMapOf<String, String>()
        val headerSecretKeys = linkedMapOf<String, String>()
        val config = try {
            require(cleanName.matches(Regex("[A-Za-z0-9_-]{1,64}"))) {
                "服务名称只能使用英文字母、数字、下划线和连字符（最多 64 位）"
            }
            if (transport == "stdio") {
                val cleanCommand = command.trim()
                require(cleanCommand.isNotBlank()) { "请填写私有运行环境中的命令或程序路径" }
                val args = argumentsText.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toList()
                parseEnvironmentLines(environmentText).also(secretValues::putAll)
                val argsArray = org.json.JSONArray()
                args.forEach { argsArray.put(it) }
                val config = org.json.JSONObject()
                    .put("command", cleanCommand)
                    .put("args", argsArray)
                if (workingDirectory.isNotBlank()) config.put("cwd", workingDirectory.trim())
                config
            } else {
                val uri = android.net.Uri.parse(httpUrl.trim())
                require(uri.scheme.equals("https", true) ||
                    (uri.scheme.equals("http", true) && (uri.host == "127.0.0.1" || uri.host == "localhost"))) {
                    "远程 MCP 地址必须使用 HTTPS；仅本机回环地址允许 HTTP"
                }
                require(!uri.host.isNullOrBlank()) { "MCP 服务地址格式不正确" }
                if (bearerToken.isNotBlank()) secretValues["BEARER_TOKEN"] = bearerToken
                parseHttpHeaderLines(httpHeadersText).entries.forEachIndexed { index, entry ->
                    val secretKey = "HTTP_HEADER_${index + 1}"
                    secretValues[secretKey] = entry.value
                    headerSecretKeys[entry.key] = secretKey
                }
                org.json.JSONObject().put("url", httpUrl.trim().trimEnd('/'))
            }
        } catch (failure: Exception) {
            error = failure.message ?: "MCP 配置不正确"
            return
        }

        scope.launch {
            busy = true
            error = null
            message = null
            try {
                val agent = ensureAgent()
                val envNames = withContext(Dispatchers.IO) {
                    if (secretValues.isEmpty()) emptyMap()
                    else secretRepository.saveValues(cleanName, secretValues)
                }
                if (transport == "stdio" && envNames.isNotEmpty()) {
                    val envVars = org.json.JSONArray()
                    envNames.values.forEach { envVars.put(it) }
                    config.put("env_vars", envVars)
                }
                if (transport == "http") {
                    envNames["BEARER_TOKEN"]?.let {
                        config.put("bearer_token_env_var", it)
                    }
                    if (headerSecretKeys.isNotEmpty()) {
                        val environmentHeaders = org.json.JSONObject()
                        headerSecretKeys.forEach { (headerName, secretKey) ->
                            envNames[secretKey]?.let { environmentName ->
                                environmentHeaders.put(headerName, environmentName)
                            }
                        }
                        if (environmentHeaders.length() > 0) {
                            config.put("env_http_headers", environmentHeaders)
                        }
                    }
                }
                withContext(Dispatchers.IO) {
                    agent.mcpController().upsertServerConfig(cleanName, config)
                }
                if (secretValues.isNotEmpty()) {
                    state.needsAgentRestart = true
                }
                readServers(agent)
                showAddDialog = false
                serverName = ""
                command = ""
                argumentsText = ""
                workingDirectory = ""
                environmentText = ""
                httpUrl = ""
                bearerToken = ""
                httpHeadersText = ""
                message = if (secretValues.isNotEmpty()) {
                    "MCP 配置已保存。密钥已加密；重启 Codex 后新环境变量才会生效。"
                } else {
                    "MCP 服务配置已保存。请检查连接状态和工具列表。"
                }
            } catch (failure: Exception) {
                error = failure.message?.take(600) ?: "保存 MCP 配置失败"
            } finally {
                busy = false
            }
        }
    }

    fun startOAuthLogin(serverName: String) {
        if (busy) return
        scope.launch {
            busy = true
            error = null
            message = null
            try {
                val agent = ensureAgent()
                val response = withContext(Dispatchers.IO) {
                    agent.mcpController().startOAuthLogin(
                        serverName = serverName,
                        threadId = state.threadId
                    )
                }
                val url = response.optString("authorizationUrl")
                require(url.isNotBlank()) { "Codex 没有返回 OAuth 授权地址" }
                val uri = Uri.parse(url)
                require(uri.scheme.equals("https", true)) { "OAuth 授权地址不是 HTTPS，已拒绝打开" }
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                state.mcpOAuthMessages[serverName] = "已打开浏览器，请完成授权后返回此页面"
                message = "已打开 $serverName 的授权页面"
            } catch (failure: Exception) {
                error = failure.message?.take(600) ?: "启动 OAuth 登录失败"
            } finally {
                busy = false
            }
        }
    }

    fun removeServer(name: String) {
        scope.launch {
            busy = true
            error = null
            message = null
            try {
                val agent = ensureAgent()
                withContext(Dispatchers.IO) {
                    agent.mcpController().removeServerConfig(name)
                }
                val hadSecrets = secretRepository.environmentNames(name).isNotEmpty()
                withContext(Dispatchers.IO) { secretRepository.removeServer(name) }
                if (hadSecrets) state.needsAgentRestart = true
                readServers(agent)
                message = if (hadSecrets) {
                    "已删除 MCP 服务：$name。旧凭据已从安全存储删除；重启 Codex 后会清除当前进程中的旧环境变量。"
                } else {
                    "已删除 MCP 服务：$name"
                }
            } catch (failure: Exception) {
                error = failure.message?.take(600) ?: "删除 MCP 服务失败"
            } finally {
                busy = false
            }
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

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { if (!busy) showAddDialog = false },
            title = { Text("添加 MCP 服务") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "服务名只用于 Codex 配置标识。STDIO 命令必须存在于 App 自己的私有运行环境中，不会调用外部 Termux。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (transport == "stdio") {
                            Button(onClick = { transport = "stdio" }) { Text("本地 STDIO") }
                            OutlinedButton(onClick = { transport = "http" }) { Text("远程 HTTP") }
                        } else {
                            OutlinedButton(onClick = { transport = "stdio" }) { Text("本地 STDIO") }
                            Button(onClick = { transport = "http" }) { Text("远程 HTTP") }
                        }
                    }
                    OutlinedTextField(
                        value = serverName,
                        onValueChange = { serverName = it },
                        label = { Text("服务名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (transport == "stdio") {
                        OutlinedTextField(
                            value = command,
                            onValueChange = { command = it },
                            label = { Text("命令或程序路径") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = argumentsText,
                            onValueChange = { argumentsText = it },
                            label = { Text("参数（每行一个）") },
                            minLines = 2,
                            maxLines = 4,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = workingDirectory,
                            onValueChange = { workingDirectory = it },
                            label = { Text("工作目录（可留空）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = environmentText,
                            onValueChange = { environmentText = it },
                            label = { Text("环境变量（每行 KEY=VALUE）") },
                            minLines = 2,
                            maxLines = 5,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "环境变量值使用 Android Keystore 加密保存；Codex 启动子进程时才会注入。不要把密钥放进命令参数。",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        OutlinedTextField(
                            value = httpUrl,
                            onValueChange = { httpUrl = it },
                            label = { Text("MCP Streamable HTTP 地址") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = bearerToken,
                            onValueChange = { bearerToken = it },
                            label = { Text("Bearer Token（可留空）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                        )
                        OutlinedTextField(
                            value = httpHeadersText,
                            onValueChange = { httpHeadersText = it },
                            label = { Text("自定义请求头（每行 Header-Name: Value）") },
                            minLines = 2,
                            maxLines = 4,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "Bearer Token 和自定义请求头的值会使用 Android Keystore 加密保存，不会写进 config.toml 或日志。保存后需重启 Codex 才会注入新凭据。",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = { saveServer() }, enabled = !busy) {
                    Text("保存并加载")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }, enabled = !busy) { Text("取消") }
            }
        )
    }

    deleteTarget?.let { name ->
        AlertDialog(
            onDismissRequest = { if (!busy) deleteTarget = null },
            title = { Text("删除 MCP 服务？") },
            text = { Text("这会从应用私有 CODEX_HOME 的 MCP 配置中移除“$name”，并请求 Codex 重新加载配置。") },
            confirmButton = {
                Button(
                    onClick = {
                        deleteTarget = null
                        removeServer(name)
                    },
                    enabled = !busy
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }, enabled = !busy) { Text("取消") }
            }
        )
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
                    OutlinedButton(onClick = { showAddDialog = true }, enabled = !busy) { Text("添加 MCP") }
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

        if (state.needsAgentRestart) {
            Card {
                Column(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("需要重启 Codex", fontWeight = FontWeight.SemiBold)
                    Text(
                        "新增或删除 MCP 密钥后，只有重启私有 Codex 进程才能更新进程环境。重启会断开当前连接；会话可重新恢复。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(
                        onClick = {
                            if (state.isWorking) return@Button
                            scope.launch {
                                busy = true
                                error = null
                                try {
                                    val old = state.controller
                                    state.controller = null
                                    state.activeTurnId = null
                                    state.isWorking = false
                                    withContext(Dispatchers.IO) { old?.close() }
                                    val agent = ensureAgent()
                                    val threadId = state.threadId
                                    if (!threadId.isNullOrBlank()) {
                                        withContext(Dispatchers.IO) {
                                            agent.resumeThread(threadId, state.workingDirectory)
                                        }
                                    }
                                    state.needsAgentRestart = false
                                    readServers(agent)
                                    message = "Codex 已重新启动，MCP 凭据已重新加载"
                                } catch (failure: Exception) {
                                    error = failure.message?.take(600) ?: "重启 Codex 失败"
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = !busy && !state.isWorking
                    ) { Text(if (state.isWorking) "任务结束后再重启" else "重启 Codex 并重新连接") }
                }
            }
        }

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
                            state.mcpOAuthMessages[server.name]?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                            if (server.authStatus == "notLoggedIn" || server.runtimeStatus == "authenticationRequired") {
                                OutlinedButton(
                                    onClick = { startOAuthLogin(server.name) },
                                    enabled = !busy
                                ) { Text("登录并授权") }
                            }
                            Text("已发现 ${server.toolNames.size} 个工具", style = MaterialTheme.typography.labelMedium)
                            server.toolNames.take(30).forEach { tool ->
                                Text("• $tool", style = MaterialTheme.typography.bodySmall)
                            }
                            if (server.toolNames.size > 30) {
                                Text("还有 ${server.toolNames.size - 30} 个工具…", style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(
                                onClick = { deleteTarget = server.name },
                                enabled = !busy
                            ) { Text("删除此服务", color = MaterialTheme.colorScheme.error) }
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

private fun parseEnvironmentLines(text: String): Map<String, String> {
    val result = linkedMapOf<String, String>()
    text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
        val separator = line.indexOf('=')
        require(separator > 0) { "环境变量格式不正确：$line。请使用 KEY=VALUE" }
        val key = line.substring(0, separator).trim()
        val value = line.substring(separator + 1)
        require(key.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}"))) {
            "环境变量名称不合法：$key"
        }
        require(value.none { it == '\r' || it == '\n' }) {
            "环境变量值不能包含换行符：$key"
        }
        require(!result.containsKey(key)) { "环境变量重复：$key" }
        result[key] = value
    }
    return result
}


private fun parseHttpHeaderLines(text: String): Map<String, String> {
    val headers = linkedMapOf<String, String>()
    text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
        val separator = line.indexOf(':')
        require(separator > 0) { "请求头格式不正确：$line。请使用 Header-Name: Value" }
        val name = line.substring(0, separator).trim()
        val value = line.substring(separator + 1).trim()
        require(name.matches(Regex("[A-Za-z0-9-]+"))) {
            "请求头名称不合法：$name"
        }
        require(value.isNotEmpty()) { "请求头值不能为空：$name" }
        require(value.none { it == '\r' || it == '\n' }) {
            "请求头值不能包含换行符：$name"
        }
        require(!headers.containsKey(name)) { "请求头重复：$name" }
        headers[name] = value
    }
    return headers
}
