package io.github.zby1888.codexzh

import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
    var status by remember { mutableStateOf(RuntimeStatus(RuntimePhase.NOT_PREPARED, "正在检查应用私有运行环境…")) }
    var busy by remember { mutableStateOf(true) }
    var command by remember { mutableStateOf("") }
    var desiredRows by remember { mutableIntStateOf(24) }
    var desiredColumns by remember { mutableIntStateOf(80) }
    val scope = rememberCoroutineScope()
    val rootAccessManager = remember(context) { RootAccessManager(context) }
    var rootStatus by remember { mutableStateOf(RootAccessStatus.notChecked()) }
    var requestingRoot by remember { mutableStateOf(false) }
    var useRootShell by remember { mutableStateOf(false) }
    val workspaceRepository = remember(manager) { CodexWorkspaceRepository(manager.paths.home) }
    val currentWorkspace = remember(chatState.workingDirectory, manager) {
        chatState.workingDirectory ?: workspaceRepository.defaultWorkspace().canonicalPath
    }

    LaunchedEffect(manager) {
        try {
            status = withContext(Dispatchers.IO) { manager.inspect() }
        } catch (error: Exception) {
            status = RuntimeStatus(RuntimePhase.ERROR, error.message ?: "读取运行环境状态失败")
        } finally {
            busy = false
        }
    }

    val actionLabel = when (status.phase) {
        RuntimePhase.UNSUPPORTED_ABI -> "设备不支持"
        RuntimePhase.NOT_PREPARED -> "准备私有目录"
        RuntimePhase.LAYOUT_READY -> "离线安装 ARM64 工具链"
        RuntimePhase.RUNTIME_READY -> "运行环境已就绪"
        RuntimePhase.ERROR -> "重试安装"
    }

    Column(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("当前项目工作区", fontWeight = FontWeight.SemiBold)
                Text(currentWorkspace, style = MaterialTheme.typography.bodySmall)
                if (terminal.isRunning && terminal.workingDirectory != currentWorkspace) {
                    TextButton(onClick = { terminal.changeDirectory(currentWorkspace) }) {
                        Text("让当前终端切换到这个项目")
                    }
                }
            }
        }

        Card {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("独立 ARM64 运行环境", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Text(status.phase.title, style = MaterialTheme.typography.labelSmall)
                }
                Text(status.message, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                busy = true
                                try {
                                    status = withContext(Dispatchers.IO) {
                                        if (status.phase == RuntimePhase.NOT_PREPARED) manager.preparePrivateDirectories()
                                        else installer.install()
                                    }
                                } catch (error: Exception) {
                                    status = RuntimeStatus(RuntimePhase.ERROR, error.message ?: "准备运行环境失败")
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = !busy && !terminal.isRunning &&
                            status.phase != RuntimePhase.UNSUPPORTED_ABI &&
                            status.phase != RuntimePhase.RUNTIME_READY,
                        modifier = Modifier.weight(1f)
                    ) { Text(actionLabel) }

                    Button(
                        onClick = {
                            if (terminal.isRunning) terminal.stop()
                            else scope.launch {
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
                        },
                        enabled = !busy && (terminal.isRunning || status.phase == RuntimePhase.RUNTIME_READY),
                        modifier = Modifier.weight(1f)
                    ) { Text(if (terminal.isRunning) "结束终端" else "启动真实终端") }
                }
                Text(
                    if (terminal.isRunning) {
                        (if (terminal.isRootSession) "Root PTY 已连接 · " else "PTY 已连接 · ") +
                            terminal.workingDirectory.orEmpty()
                    } else terminal.message,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (terminal.isRunning) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Card {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Root 调试通道", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Text(
                        if (rootStatus.isGranted) "已授权" else "默认关闭",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (rootStatus.isGranted) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(rootStatus.message, style = MaterialTheme.typography.bodySmall)
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
                    Text(if (requestingRoot) "正在等待 Root 管理器…" else "申请 / 重新检查 Root 授权")
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("本次终端使用 Root 身份", fontWeight = FontWeight.Medium)
                        Text(
                            "只影响这个 Root 终端；Agent 凭据不会传给它。Root 在项目里新建的文件可能归 root 所有，日常改代码和构建请用 Codex Agent / 普通终端。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = useRootShell,
                        onCheckedChange = { useRootShell = it },
                        enabled = rootStatus.isGranted && !busy && !terminal.isRunning
                    )
                }
            }
        }

        Box(
            Modifier.weight(1f).fillMaxWidth().background(Color(0xFF101318))
                .onSizeChanged { size ->
                    val density = context.resources.displayMetrics.density
                    val cols = (size.width / (8.2f * density)).toInt().coerceIn(24, 120)
                    val rows = (size.height / (18f * density)).toInt().coerceIn(8, 60)
                    if (cols != desiredColumns || rows != desiredRows) {
                        desiredColumns = cols
                        desiredRows = rows
                        terminal.resize(rows, cols)
                    }
                }
        ) {
            TerminalSurface(terminal.frame)
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
        Text(
            "聊天 Agent 与 Shell PTY 使用不同进程和输入输出通道；两个页面共用当前项目目录。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TerminalKeyButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
        Text(label)
    }
}

@Composable
private fun TerminalSurface(frame: TerminalFrame) {
    Canvas(Modifier.fillMaxSize().padding(4.dp)) {
        if (frame.rows <= 0 || frame.columns <= 0) return@Canvas
        val cellWidth = size.width / frame.columns
        val cellHeight = size.height / frame.rows
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textSize = cellHeight * 0.78f
        }
        val metrics = paint.fontMetrics
        val baselineOffset = (cellHeight - metrics.descent - metrics.ascent) / 2f

        frame.cells.forEachIndexed { row, cells ->
            cells.forEachIndexed { column, cell ->
                val reverse = (cell.flags and (1 shl 3)) != 0
                val foreground = if (reverse) cell.background else cell.foreground
                val background = if (reverse) cell.foreground else cell.background
                val left = column * cellWidth
                val top = row * cellHeight
                if (background != null) {
                    drawRect(
                        color = Color(android.graphics.Color.rgb((background shr 16) and 0xff, (background shr 8) and 0xff, background and 0xff)),
                        topLeft = androidx.compose.ui.geometry.Offset(left, top),
                        size = androidx.compose.ui.geometry.Size(cellWidth * maxOf(1, cell.width), cellHeight)
                    )
                }
                paint.color = if (foreground == null) android.graphics.Color.rgb(226, 232, 240)
                else android.graphics.Color.rgb((foreground shr 16) and 0xff, (foreground shr 8) and 0xff, foreground and 0xff)
                paint.isFakeBoldText = (cell.flags and 1) != 0
                paint.isUnderlineText = (cell.flags and (1 shl 2)) != 0
                paint.isStrikeThruText = (cell.flags and (1 shl 4)) != 0
                paint.typeface = Typeface.create(
                    Typeface.MONOSPACE,
                    if ((cell.flags and (1 shl 1)) != 0) Typeface.ITALIC else Typeface.NORMAL
                )
                if (cell.text.isNotEmpty() && cell.width > 0) {
                    drawContext.canvas.nativeCanvas.drawText(cell.text, left, top + baselineOffset, paint)
                }
            }
        }
        if (frame.cursorVisible) {
            val left = frame.cursorColumn * cellWidth
            val top = frame.cursorRow * cellHeight
            val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.argb(190, 230, 237, 243)
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
            }
            drawContext.canvas.nativeCanvas.drawRect(left, top, left + cellWidth, top + cellHeight, cursorPaint)
        }
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
