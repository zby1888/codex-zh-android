package io.github.zby1888.codexzh

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CodexZhApp() }
    }
}

private enum class Tab(val label: String, val glyph: String) {
    Chat("聊天", "聊"), Terminal("终端", ">_"), Providers("服务商", "模"), Mcp("MCP 工具", "M")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CodexZhApp() {
    var tab by remember { mutableStateOf(Tab.Chat) }
    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(title = {
                    Column {
                        Text("Codex 中文版", fontWeight = FontWeight.SemiBold)
                        Text("Android ARM64 · 私有运行时初始化阶段", style = MaterialTheme.typography.labelSmall)
                    }
                })
            },
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = { tab = item },
                            icon = { Text(item.glyph) },
                            label = { Text(item.label) }
                        )
                    }
                }
            }
        ) { padding ->
            when (tab) {
                Tab.Chat -> ChatScreen(Modifier.padding(padding))
                Tab.Terminal -> TerminalScreen(Modifier.padding(padding))
                Tab.Providers -> ListScreen(Modifier.padding(padding), "模型服务商",
                    "配置第三方中转站 API、模型 ID、API 基础地址和自定义请求头。",
                    listOf("OpenAI Responses 兼容", "OpenAI Chat Completions 兼容", "Anthropic Messages", "Google Gemini", "自定义 API"))
                Tab.Mcp -> ListScreen(Modifier.padding(padding), "MCP 工具",
                    "目标支持远程 Streamable HTTP 与本地 STDIO MCP 服务。",
                    listOf("服务列表与连接测试", "工具发现和调用进度", "允许 / 拒绝 / 每次询问", "导入 Codex、OpenCode、Operit 配置"))
            }
        }
    }
}

@Composable
private fun ChatScreen(modifier: Modifier = Modifier) {
    var prompt by remember { mutableStateOf("") }
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        InfoCard("聊天工作区", "界面框架已就绪。Codex App Server 与通用模型协议适配器尚未接入。")
        InfoCard("会话", "后续显示消息、流式输出、工具调用进度和结果。")
        Spacer(Modifier.weight(1f))
        OutlinedTextField(
            value = prompt, onValueChange = { prompt = it },
            modifier = Modifier.fillMaxWidth(), minLines = 2,
            placeholder = { Text("输入消息（当前为界面预览）") }
        )
        Button(onClick = { prompt = "" }, enabled = prompt.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text("发送（尚未连接模型）")
        }
    }
}

@Composable
private fun TerminalScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val manager = remember(context) { RuntimeManager(context) }
    var status by remember { mutableStateOf(manager.inspect()) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(manager) {
        status = withContext(Dispatchers.IO) { manager.inspect() }
    }

    Column(
        modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("独立 ARM64 运行环境", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(status.phase.title, style = MaterialTheme.typography.titleSmall)
                Text(status.message, style = MaterialTheme.typography.bodyMedium)
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            try {
                                status = withContext(Dispatchers.IO) {
                                    manager.preparePrivateDirectories()
                                }
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy && status.phase != RuntimePhase.UNSUPPORTED_ABI,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (busy) CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    ) else Text("准备 / 检查私有目录")
                }
            }
        }
        InfoCard(
            "目录隔离",
            "PREFIX：应用私有 files/runtime/prefix\nHOME：应用私有 files/home\nCODEX_HOME：应用私有 files/codex\n不会读取或修改 Termux 的目录。"
        )
        InfoCard(
            "当前能力边界",
            "此步骤只验证私有目录布局和 ARM64 ABI。Linux 基础包、Bash、PTY 终端和 Codex CLI 尚未打包，暂时不能执行终端命令。"
        )
    }
}

@Composable
private fun ListScreen(modifier: Modifier, title: String, body: String, items: List<String>) {
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        InfoCard(title, body)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items) { InfoCard(it, "规划中") }
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
