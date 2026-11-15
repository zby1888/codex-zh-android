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
    val chatState = remember { CodexChatUiState() }
    DisposableEffect(chatState) {
        onDispose { chatState.closeInBackground() }
    }
    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(title = {
                    Column {
                        Text("Codex 中文版", fontWeight = FontWeight.SemiBold)
                        Text("Android ARM64 · 私有运行时阶段", style = MaterialTheme.typography.labelSmall)
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
                Tab.Chat -> CodexChatScreen(Modifier.padding(padding), chatState) { tab = Tab.Providers }
                Tab.Terminal -> TerminalScreen(Modifier.padding(padding), chatState)
                Tab.Providers -> ProviderScreen(Modifier.padding(padding))
                Tab.Mcp -> CodexMcpScreen(Modifier.padding(padding), chatState) { tab = Tab.Providers }
            }
        }
    }
}

@Composable
private fun TerminalScreen(modifier: Modifier = Modifier, chatState: CodexChatUiState) {
    val context = LocalContext.current
    val manager = remember(context) { RuntimeManager(context) }
    val installer = remember(context) { RuntimeBootstrapInstaller(context, manager) }
    var status by remember { mutableStateOf(RuntimeStatus(RuntimePhase.NOT_PREPARED, "正在检查应用私有运行环境…")) }
    var busy by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(manager) {
        try {
            status = withContext(Dispatchers.IO) { manager.inspect() }
        } finally {
            busy = false
        }
    }

    val actionLabel = when (status.phase) {
        RuntimePhase.UNSUPPORTED_ABI -> "设备不支持"
        RuntimePhase.NOT_PREPARED -> "准备私有目录"
        RuntimePhase.LAYOUT_READY -> "离线安装 ARM64 工具链"
        RuntimePhase.RUNTIME_READY -> "运行环境已安装"
        RuntimePhase.ERROR -> "重试安装"
    }

    Column(
        modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        InfoCard(
            "当前项目工作区",
            chatState.workingDirectory
                ?: "尚未选择项目。可以先到“聊天”页面选择或创建项目；聊天和终端会共用这个应用私有目录。"
        )
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
                                    if (status.phase == RuntimePhase.NOT_PREPARED) {
                                        manager.preparePrivateDirectories()
                                    } else {
                                        installer.install()
                                    }
                                }
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy && status.phase != RuntimePhase.UNSUPPORTED_ABI &&
                        status.phase != RuntimePhase.RUNTIME_READY,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (busy) CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    ) else Text(actionLabel)
                }
            }
        }
        InfoCard(
            "目录隔离",
            "PREFIX：应用私有 files/runtime/prefix\nHOME：应用私有 files/home\nCODEX_HOME：应用私有 files/codex\n不会读取或修改 Termux 的目录。"
        )
        InfoCard(
            "运行时说明",
            "首批工具链包含 Bash、Coreutils、Git、OpenSSL、CA 证书和 jq。它是 Android Bionic/ARM64 用户空间，不是 glibc 桌面 Linux。"
        )
        InfoCard(
            "终端状态",
            "此页面目前展示共享项目目录和运行环境状态。真正的交互式 PTY、命令输入、终端模拟器和多终端会话仍在开发，当前不要把这里当作已经可执行命令的终端。"
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
