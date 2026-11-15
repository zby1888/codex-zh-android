package io.github.zby1888.codexzh

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import java.util.UUID

@Composable
internal fun ProviderScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repository = remember(context) { ProviderRepository(context) }
    var providers by remember { mutableStateOf(repository.list()) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ProviderConfig?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var modelId by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var headersText by remember { mutableStateOf("") }
    var savedHeaderCount by remember { mutableStateOf(0) }
    var protocol by remember { mutableStateOf(ProviderProtocol.CODEX_RESPONSES) }
    var protocolMenuExpanded by remember { mutableStateOf(false) }

    fun refresh() {
        providers = repository.list()
    }

    fun openNew() {
        editingId = null
        name = ""
        baseUrl = ""
        modelId = ""
        apiKey = ""
        headersText = ""
        savedHeaderCount = 0
        protocol = ProviderProtocol.CODEX_RESPONSES
        message = null
        showEditor = true
    }

    fun openEdit(item: ProviderConfig) {
        val credentials = repository.credentials(item.id)
        editingId = item.id
        name = item.displayName
        baseUrl = item.baseUrl
        modelId = item.modelId
        apiKey = ""
        headersText = ""
        savedHeaderCount = credentials?.customHeaders?.size ?: 0
        protocol = item.protocol
        message = null
        showEditor = true
    }

    fun saveProvider() {
        try {
            val existing = editingId?.let { repository.credentials(it) }
            val parsedHeaders = if (headersText.isBlank()) {
                existing?.customHeaders.orEmpty()
            } else {
                headersText.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .associate { line ->
                        val separator = line.indexOf(':')
                        require(separator > 0) { "请求头格式应为：名称: 值" }
                        line.substring(0, separator).trim() to line.substring(separator + 1).trim()
                    }
            }
            val id = editingId ?: UUID.randomUUID().toString()
            repository.save(
                ProviderConfig(
                    id = id,
                    displayName = name,
                    baseUrl = baseUrl,
                    protocol = protocol,
                    modelId = modelId
                ),
                ProviderCredentials(
                    apiKey = apiKey.ifBlank { existing?.apiKey.orEmpty() },
                    customHeaders = parsedHeaders
                )
            )
            refresh()
            showEditor = false
            message = "服务商配置已保存。连接测试和 Codex 配置同步仍待接入。"
        } catch (error: Exception) {
            message = error.message ?: "保存失败，请检查填写内容"
        }
    }

    Column(
        modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("模型服务商", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Codex Agent 使用 Responses API 兼容服务商；Chat Completions、Anthropic 和 Gemini 作为独立通用聊天协议。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(onClick = ::openNew, modifier = Modifier.fillMaxWidth()) {
                    Text("添加服务商")
                }
            }
        }

        message?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }

        if (providers.isEmpty()) {
            Card {
                Text("还没有添加服务商。可以先添加你正在使用的第三方 API 中转站。", Modifier.padding(16.dp))
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(providers, key = { it.id }) { item ->
                    Card {
                        Column(
                            Modifier.fillMaxWidth().padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row {
                                Text(item.displayName, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                if (item.isDefault) Text("默认", color = MaterialTheme.colorScheme.primary)
                            }
                            Text(item.protocol.displayName, style = MaterialTheme.typography.bodySmall)
                            Text(item.baseUrl, style = MaterialTheme.typography.bodySmall)
                            Text("模型：${item.modelId}", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = {
                                    repository.setDefault(item.id)
                                    refresh()
                                }, enabled = !item.isDefault) { Text("设为默认") }
                                TextButton(onClick = { openEdit(item) }) { Text("编辑") }
                                TextButton(onClick = { pendingDelete = item }) { Text("删除") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showEditor) {
        AlertDialog(
            onDismissRequest = { showEditor = false },
            title = { Text(if (editingId == null) "添加服务商" else "编辑服务商") },
            text = {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(name, { name = it }, label = { Text("显示名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Box {
                        OutlinedButton(onClick = { protocolMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(protocol.displayName)
                        }
                        DropdownMenu(expanded = protocolMenuExpanded, onDismissRequest = { protocolMenuExpanded = false }) {
                            ProviderProtocol.entries.forEach { item ->
                                DropdownMenuItem(
                                    text = { Text(item.displayName) },
                                    onClick = {
                                        protocol = item
                                        protocolMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        baseUrl, { baseUrl = it },
                        label = { Text("API 基础地址") },
                        placeholder = { Text("https://你的中转站/v1") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(modelId, { modelId = it }, label = { Text("模型 ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        apiKey, { apiKey = it },
                        label = { Text("API Key") },
                        placeholder = { Text(if (editingId == null) "填写 API Key" else "留空则保留当前密钥") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        headersText, { headersText = it },
                        label = { Text("自定义请求头（每行一个：名称: 值）") },
                        placeholder = { Text(if (savedHeaderCount > 0) "已保存 $savedHeaderCount 个请求头；留空则保留" else "例如：X-Client: mobile") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "密钥和请求头值会使用 Android Keystore 加密保存。当前只保存配置，尚未执行连接测试或写入 Codex 配置。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = { Button(onClick = ::saveProvider) { Text("保存") } },
            dismissButton = { TextButton(onClick = { showEditor = false }) { Text("取消") } }
        )
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除服务商？") },
            text = { Text("将删除“${item.displayName}”的配置及加密保存的 API 凭据。此操作无法撤销。") },
            confirmButton = {
                Button(onClick = {
                    repository.delete(item.id)
                    pendingDelete = null
                    refresh()
                    message = "服务商已删除"
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } }
        )
    }
}
