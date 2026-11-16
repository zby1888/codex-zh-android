package io.github.zby1888.codexzh

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * First-run setup for a Responses API-compatible server and model.
 * Provider credentials are persisted through ProviderRepository's Android
 * Keystore-backed secret store, never as plaintext preferences or logs.
 */
@Composable
internal fun CodexSetupWizardScreen(
    modifier: Modifier = Modifier,
    state: CodexChatUiState,
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember(context) { ProviderRepository(context) }
    var step by rememberSaveable { mutableStateOf(0) }
    var displayName by rememberSaveable { mutableStateOf("我的 API 中转站") }
    var baseUrl by rememberSaveable { mutableStateOf("") }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var modelId by rememberSaveable { mutableStateOf("") }
    var headersText by rememberSaveable { mutableStateOf("") }
    var providerId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedModelId by rememberSaveable { mutableStateOf("") }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("先填写你正在使用的 API 中转站信息。") }
    var error by remember { mutableStateOf<String?>(null) }

    fun parseHeaders(value: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        value.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            val separator = line.indexOf(':')
            require(separator > 0) { "请求头格式应为：名称: 值" }
            val name = line.substring(0, separator).trim()
            val headerValue = line.substring(separator + 1).trim()
            require(name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}"))) {
                "请求头名称不合法：$name"
            }
            require(headerValue.isNotEmpty() && headerValue.none { it == '\\r' || it == '\\n' }) {
                "请求头值不能为空或包含换行：$name"
            }
            require(!result.containsKey(name)) { "请求头重复：$name" }
            result[name] = headerValue
        }
        return result
    }

    fun saveAndConnect() {
        if (busy) return
        val cleanName = displayName.trim()
        val cleanUrl = baseUrl.trim()
        val cleanModel = modelId.trim()
        if (cleanName.isBlank() || cleanUrl.isBlank() || cleanModel.isBlank()) {
            error = "请填写服务名称、API 基础地址和模型 ID。"
            return
        }
        if (apiKey.isBlank() && providerId == null) {
            error = "请填写 API Key。"
            return
        }
        val headers = try {
            parseHeaders(headersText)
        } catch (failure: Exception) {
            error = failure.message ?: "自定义请求头格式错误"
            return
        }
        scope.launch {
            busy = true
            error = null
            status = "正在安全保存服务商配置…"
            try {
                val id = providerId ?: UUID.randomUUID().toString()
                withContext(Dispatchers.IO) {
                    val existing = repository.credentials(id)
                    repository.save(
                        ProviderConfig(
                            id = id,
                            displayName = cleanName,
                            baseUrl = cleanUrl,
                            protocol = ProviderProtocol.CODEX_RESPONSES,
                            modelId = cleanModel,
                            isCodexAgentDefault = true
                        ),
                        ProviderCredentials(
                            apiKey = apiKey.ifBlank { existing?.apiKey.orEmpty() },
                            customHeaders = if (headers.isEmpty()) {
                                existing?.customHeaders.orEmpty()
                            } else {
                                headers
                            }
                        )
                    )
                }
                providerId = id
                status = "配置已加密保存，正在启动 Codex 并读取模型目录…"
                val agent = withContext(Dispatchers.IO) {
                    CodexAgentRuntimeFactory(context).start(listener = state.appServerListener)
                }
                state.controller?.takeIf { it !== agent }?.let { previous ->
                    withContext(Dispatchers.IO) { previous.close() }
                }
                state.controller = agent
                val modelResponse = try {
                    withContext(Dispatchers.IO) { agent.listModels(includeHidden = false) }
                } catch (failure: Exception) {
                    error = "服务器已保存，但模型目录读取失败：${failure.message ?: "未知错误"}。可以手动使用上面填写的模型 ID。"
                    null
                }
                if (modelResponse != null) {
                    state.replaceModels(modelResponse)
                    val matched = state.models.firstOrNull { it.id == cleanModel }
                        ?: state.models.firstOrNull { it.isDefault }
                        ?: state.models.firstOrNull()
                    if (matched != null) {
                        state.selectModel(matched)
                        selectedModelId = matched.id
                    } else {
                        state.selectModelId(cleanModel)
                        selectedModelId = cleanModel
                    }
                    status = if (state.models.isEmpty()) {
                        "已连接服务器，但没有返回模型目录；可以使用手动填写的模型 ID。"
                    } else {
                        "服务器连接成功，已读取 ${state.models.size} 个模型。请选择本次要使用的模型。"
                    }
                } else {
                    state.selectModelId(cleanModel)
                    selectedModelId = cleanModel
                    status = "Codex 已启动。请确认模型 ID 与中转站开放的模型名称一致。"
                }
                step = 1
            } catch (failure: Exception) {
                error = failure.message?.take(600) ?: "连接服务器失败"
                status = "服务商信息已保留；修正配置后可以重试。"
            } finally {
                busy = false
            }
        }
    }

    fun finishSetup() {
        val chosen = selectedModelId.trim().ifBlank { modelId.trim() }
        if (chosen.isBlank()) {
            error = "请选择模型，或填写中转站提供的模型 ID。"
            return
        }
        val id = providerId ?: return
        scope.launch {
            busy = true
            error = null
            try {
                withContext(Dispatchers.IO) {
                    val config = repository.get(id)
                        ?: throw IllegalStateException("找不到刚才保存的服务商配置")
                    val credentials = repository.credentials(id)
                        ?: throw IllegalStateException("读取加密凭据失败，请重新填写 API Key")
                    repository.save(config.copy(modelId = chosen, isCodexAgentDefault = true), credentials)
                }
                state.selectModelId(chosen)
                state.status = "服务器和模型已设置，Codex 已准备就绪"
                onComplete()
            } catch (failure: Exception) {
                error = failure.message?.take(600) ?: "保存模型设置失败"
            } finally {
                busy = false
            }
        }
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Codex 中文版", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(
            if (step == 0) "连接你的模型服务器" else "选择要使用的模型",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            if (step == 0) "先完成这一步，打开软件后 Codex 就能自动连接，不必每次手动选择服务商。"
            else "服务器已接入。选择默认模型后即可进入聊天；以后仍可在服务商页面修改。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(2) { index ->
                Card(
                    modifier = Modifier.weight(1f),
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = if (index == step) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        if (index == 0) "1  服务器" else "2  模型",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
        Card {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (step == 0) {
                    Text("服务器设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Codex Agent 需要兼容 OpenAI Responses API 的服务器。普通 Chat Completions 地址不能直接代替；请使用中转站提供的 Responses API 地址。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it },
                        label = { Text("服务名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("API 基础地址") },
                        placeholder = { Text("https://api.example.com/v1") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API Key") },
                        placeholder = { Text("粘贴中转站提供的密钥") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = modelId,
                        onValueChange = { modelId = it },
                        label = { Text("模型 ID") },
                        placeholder = { Text("填写中转站提供的模型名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = headersText,
                        onValueChange = { headersText = it },
                        label = { Text("自定义请求头（可选，每行一个）") },
                        placeholder = { Text("Header-Name: Value") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "API Key 和请求头值使用 Android Keystore 加密保存，不会明文写进普通配置或日志。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = ::saveAndConnect,
                        enabled = !busy && baseUrl.isNotBlank() && modelId.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.width(18.dp), strokeWidth = 2.dp)
                        else Text("保存并连接服务器")
                    }
                } else {
                    Text("模型设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(status, style = MaterialTheme.typography.bodySmall)
                    if (state.models.isNotEmpty()) {
                        Box {
                            OutlinedButton(
                                onClick = { modelMenuExpanded = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    state.models.firstOrNull { it.id == selectedModelId }?.displayName
                                        ?: selectedModelId.ifBlank { "选择模型" }
                                )
                            }
                            DropdownMenu(
                                expanded = modelMenuExpanded,
                                onDismissRequest = { modelMenuExpanded = false }
                            ) {
                                state.models.forEach { model ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(model.displayName)
                                                Text(model.id, style = MaterialTheme.typography.labelSmall)
                                            }
                                        },
                                        onClick = {
                                            selectedModelId = model.id
                                            state.selectModel(model)
                                            modelMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                        Button(
                            onClick = {
                                val selected = state.models.firstOrNull { it.id == selectedModelId }
                                    ?: state.models.firstOrNull { it.isDefault }
                                selected?.let {
                                    selectedModelId = it.id
                                    state.selectModel(it)
                                }
                                finishSetup()
                            },
                            enabled = !busy && selectedModelId.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (busy) "正在保存…" else "完成设置并进入聊天") }
                    } else {
                        OutlinedTextField(
                            value = selectedModelId,
                            onValueChange = {
                                selectedModelId = it
                                modelId = it
                            },
                            label = { Text("手动填写模型 ID") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = ::finishSetup,
                            enabled = !busy && selectedModelId.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("保存模型并进入聊天") }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { step = 0 }, enabled = !busy) { Text("返回服务器设置") }
                        TextButton(
                            onClick = ::saveAndConnect,
                            enabled = !busy && providerId != null
                        ) { Text("重新连接 / 刷新模型") }
                    }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (status.isNotBlank()) {
                    Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text(
            "连接信息只保存在本应用私有空间。后续可在“服务商”页面编辑服务器、API Key 和模型。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
