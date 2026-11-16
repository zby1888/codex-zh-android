package io.github.zby1888.codexzh

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        CrashLogManager.install(applicationContext)
        CrashLogManager.recordEvent(filesDir, "app.on_create")
        super.onCreate(savedInstanceState)
        lifecycleScope.launch(Dispatchers.IO) {
            CrashLogManager.collectPreviousProcessExits(applicationContext)
        }
        setContent { CodexZhApp() }
    }
}

private enum class Tab(val label: String, val glyph: String) {
    Chat("聊天", "聊"), Terminal("终端", ">_"), Providers("服务商", "模"), Mcp("MCP 工具", "M"), Diagnostics("诊断", "记")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CodexZhApp() {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(Tab.Chat) }
    val chatState = remember { CodexChatUiState() }
    val runtimeManager = remember(context) { RuntimeManager(context) }
    val runtimeInstaller = remember(context) { RuntimeBootstrapInstaller(context, runtimeManager) }
    val terminalSession = remember(runtimeManager) { PtyTerminalSession(runtimeManager.paths) }

    DisposableEffect(chatState, terminalSession) {
        onDispose {
            chatState.closeInBackground()
            terminalSession.closeInBackground()
        }
    }

    MaterialTheme {
        Scaffold(
            topBar = {
                if (tab != Tab.Chat) {
                    TopAppBar(title = {
                        Column {
                            Text("Codex 中文版", fontWeight = FontWeight.SemiBold)
                            Text("Android ARM64 · 私有运行时", style = MaterialTheme.typography.labelSmall)
                        }
                    })
                }
            },
            bottomBar = {
                if (tab != Tab.Chat) {
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
            }
        ) { padding ->
            when (tab) {
                Tab.Chat -> CodexChatScreen(
                    modifier = Modifier.padding(padding),
                    state = chatState,
                    onOpenProviders = { tab = Tab.Providers },
                    onOpenMcp = { tab = Tab.Mcp },
                    onOpenTerminal = { tab = Tab.Terminal }
                )
                Tab.Terminal -> TerminalScreen(
                    Modifier.padding(padding), chatState, runtimeManager, runtimeInstaller, terminalSession
                )
                Tab.Providers -> ProviderScreen(Modifier.padding(padding))
                Tab.Mcp -> CodexMcpScreen(Modifier.padding(padding), chatState) { tab = Tab.Providers }
                Tab.Diagnostics -> CrashLogScreen(Modifier.padding(padding))
            }
        }
    }
}

@Composable
private fun TerminalScreen(
    modifier: Modifier = Modifier,
    chatState: CodexChatUiState,
    manager: RuntimeManager,
    installer: RuntimeBootstrapInstaller,
    terminal: PtyTerminalSession
) {
    val context = LocalContext.current
    var status by remember {
        mutableStateOf(RuntimeStatus(RuntimePhase.NOT_PREPARED, "正在检查应用私有运行环境…"))
    }
    var busy by remember { mutableStateOf(true) }
    var command by remember { mutableStateOf("") }
    var desiredRows by remember { mutableIntStateOf(24) }
    var desiredColumns by remember { mutableIntStateOf(80) }
    val terminalPreferences = remember(context) {
        context.getSharedPreferences("codex_terminal_ui", 0)
    }
    var terminalFontScale by remember(terminalPreferences) {
        mutableFloatStateOf(terminalPreferences.getFloat("font_scale", 1.2f).coerceIn(0.8f, 1.8f))
    }
    var terminalWidthPx by remember { mutableIntStateOf(0) }
    var terminalHeightPx by remember { mutableIntStateOf(0) }
    var showRootDetails by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val rootAccessManager = remember(context) { RootAccessManager(context) }
    var rootStatus by remember { mutableStateOf(RootAccessStatus.notChecked()) }
    var requestingRoot by remember { mutableStateOf(false) }
    var useRootShell by remember { mutableStateOf(false) }
    val workspaceRepository = remember(manager) { CodexWorkspaceRepository(manager.paths.home) }
    val currentWorkspace = remember(chatState.workingDirectory, manager) {
        chatState.workingDirectory ?: workspaceRepository.defaultWorkspace().canonicalPath
    }
    val density = context.resources.displayMetrics.density

    LaunchedEffect(manager) {
        try {
            status = withContext(Dispatchers.IO) { manager.inspect() }
        } catch (error: Exception) {
            status = RuntimeStatus(RuntimePhase.ERROR, error.message ?: "读取运行环境状态失败")
        } finally {
            busy = false
        }
    }

    LaunchedEffect(terminalWidthPx, terminalHeightPx, terminalFontScale) {
        if (terminalWidthPx > 0 && terminalHeightPx > 0) {
            // Keep monospace cells aligned while allowing genuine font-size control.
            val columns = (terminalWidthPx / (10.5f * density * terminalFontScale))
                .toInt().coerceIn(20, 160)
            val rows = (terminalHeightPx / (22f * density * terminalFontScale))
                .toInt().coerceIn(8, 80)
            if (columns != desiredColumns || rows != desiredRows) {
                desiredColumns = columns
                desiredRows = rows
                terminal.resize(rows, columns)
            }
        }
    }

    val actionLabel = when (status.phase) {
        RuntimePhase.UNSUPPORTED_ABI -> "设备不支持"
        RuntimePhase.NOT_PREPARED -> "准备私有目录"
        RuntimePhase.LAYOUT_READY -> "安装 ARM64 工具链"
        RuntimePhase.RUNTIME_READY -> "运行环境已就绪"
        RuntimePhase.ERROR -> "重试安装"
    }

    Column(
        modifier.fillMaxSize().imePadding().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Compact project/runtime toolbar; the terminal output owns most of the screen.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    currentWorkspace.substringAfterLast('/').ifBlank { "当前项目" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Text(
                    if (terminal.isRunning) {
                        (if (terminal.isRootSession) "Root PTY · " else "PTY · ") +
                            terminal.workingDirectory.orEmpty()
                    } else {
                        "${status.phase.title} · ${status.message}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (terminal.isRunning) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            if (status.phase != RuntimePhase.RUNTIME_READY) {
                OutlinedButton(
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
                            } catch (error: Exception) {
                                status = RuntimeStatus(
                                    RuntimePhase.ERROR,
                                    error.message ?: "准备运行环境失败"
                                )
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy && !terminal.isRunning &&
                        status.phase != RuntimePhase.UNSUPPORTED_ABI
                ) { Text(actionLabel) }
            }
            Spacer(Modifier.width(6.dp))
            Button(
                onClick = {
                    if (terminal.isRunning) {
                        terminal.stop()
                    } else {
                        scope.launch {
                            busy = true
                            try {
                                check(status.phase == RuntimePhase.RUNTIME_READY) {
                                    "请先安装并检查 ARM64 运行环境"
                                }
                                val validated = withContext(Dispatchers.IO) {
                                    workspaceRepository.validate(java.io.File(currentWorkspace)).canonicalPath
                                }
                                val suPath = if (useRootShell) {
                                    rootStatus = rootAccessManager.requestAccess()
                                    check(rootStatus.isGranted && !rootStatus.suPath.isNullOrBlank()) {
                                        rootStatus.message
                                    }
                                    rootStatus.suPath
                                } else null
                                terminal.start(validated, desiredRows, desiredColumns, rootSuPath = suPath)
                            } catch (error: Exception) {
                                status = status.copy(message = error.message ?: "启动 PTY 终端失败")
                            } finally {
                                busy = false
                            }
                        }
                    }
                },
                enabled = !busy && (terminal.isRunning || status.phase == RuntimePhase.RUNTIME_READY)
            ) { Text(if (terminal.isRunning) "结束" else "启动") }
        }

        if (terminal.isRunning && terminal.workingDirectory != currentWorkspace) {
            TextButton(
                onClick = { terminal.changeDirectory(currentWorkspace) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("切换到当前项目目录")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Text("终端输出", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("字号", style = MaterialTheme.typography.labelSmall)
            TextButton(
                onClick = {
                    terminalFontScale = (terminalFontScale - 0.1f).coerceAtLeast(0.8f)
                    terminalPreferences.edit().putFloat("font_scale", terminalFontScale).apply()
                },
                enabled = terminalFontScale > 0.8f
            ) { Text("A−") }
            Text(
                "${(terminalFontScale * 16f).toInt()}",
                style = MaterialTheme.typography.labelMedium
            )
            TextButton(
                onClick = {
                    terminalFontScale = (terminalFontScale + 0.1f).coerceAtMost(1.8f)
                    terminalPreferences.edit().putFloat("font_scale", terminalFontScale).apply()
                },
                enabled = terminalFontScale < 1.8f
            ) { Text("A＋") }
        }

        Box(
            Modifier.weight(1f).fillMaxWidth()
                .background(Color(0xFF101318))
                .onSizeChanged { size ->
                    terminalWidthPx = size.width
                    terminalHeightPx = size.height
                }
        ) {
            TerminalSurface(terminal.frame) { terminal.loadScrollback() }
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TerminalKeyButton("Ctrl+C", terminal.isRunning) { terminal.sendInput("\u0003") }
            TerminalKeyButton("Ctrl+D", terminal.isRunning) { terminal.sendInput("\u0004") }
            TerminalKeyButton("Tab", terminal.isRunning) { terminal.sendInput("\t") }
            TerminalKeyButton("Esc", terminal.isRunning) { terminal.sendInput("\u001b") }
            TerminalKeyButton("↑", terminal.isRunning) { terminal.sendInput("\u001b[A") }
            TerminalKeyButton("↓", terminal.isRunning) { terminal.sendInput("\u001b[B") }
            TerminalKeyButton("←", terminal.isRunning) { terminal.sendInput("\u001b[D") }
            TerminalKeyButton("→", terminal.isRunning) { terminal.sendInput("\u001b[C") }
            TerminalKeyButton("清屏", terminal.isRunning) { terminal.sendInput("\u001b[2J\u001b[H") }
            TextButton(onClick = { showRootDetails = !showRootDetails }) {
                Text(if (showRootDetails) "收起 Root" else "Root 设置")
            }
        }

        if (showRootDetails) {
            Card {
                Column(
                    Modifier.fillMaxWidth().padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("Root 调试终端", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Text(
                            if (rootStatus.isGranted) "已授权" else "默认关闭",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Text(rootStatus.message, style = MaterialTheme.typography.labelSmall)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    requestingRoot = true
                                    try {
                                        rootStatus = rootAccessManager.requestAccess()
                                        if (!rootStatus.isGranted) useRootShell = false
                                    } finally {
                                        requestingRoot = false
                                    }
                                }
                            },
                            enabled = !requestingRoot && !busy && !terminal.isRunning
                        ) {
                            Text(if (requestingRoot) "等待 Root 授权…" else "申请 / 检查 Root")
                        }
                        Spacer(Modifier.weight(1f))
                        Text("本次使用 Root", style = MaterialTheme.typography.bodySmall)
                        Switch(
                            checked = useRootShell,
                            onCheckedChange = { useRootShell = it },
                            enabled = rootStatus.isGranted && !busy && !terminal.isRunning
                        )
                    }
                    Text(
                        "Root 只用于独立终端，不会提升 Codex Agent。Root 创建的项目文件可能归 root 所有。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                enabled = terminal.isRunning,
                label = { Text("输入命令或交互内容") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (command.isNotEmpty()) {
                        terminal.sendLine(command)
                        command = ""
                    }
                })
            )
            Button(
                onClick = {
                    if (command.isNotEmpty()) {
                        terminal.sendLine(command)
                        command = ""
                    }
                },
                enabled = terminal.isRunning && command.isNotEmpty()
            ) { Text("发送") }
        }
    }
}

@Composable
private fun TerminalKeyButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
        Text(label)
    }
}

@Composable
private fun TerminalSurface(frame: TerminalFrame, onScrollbackRequested: () -> Unit) {
    AndroidView(
        factory = { context ->
            TerminalSoftwareView(context).apply {
                setScrollbackLoader(onScrollbackRequested)
                updateFrame(frame)
            }
        },
        update = { view ->
            view.setScrollbackLoader(onScrollbackRequested)
            view.updateFrame(frame)
        },
        modifier = Modifier.fillMaxSize().padding(4.dp)
    )
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
