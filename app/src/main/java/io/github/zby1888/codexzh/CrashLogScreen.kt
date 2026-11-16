package io.github.zby1888.codexzh

import android.content.ClipData
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun CrashLogScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf(emptyList<File>()) }
    var selectedFile by remember { mutableStateOf<File?>(null) }
    var preview by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("首次打开诊断页会读取 Android 保存的上次进程退出记录。") }

    suspend fun refresh() {
        loading = true
        try {
            val result = withContext(Dispatchers.IO) {
                val imported = CrashLogManager.collectPreviousProcessExits(context)
                CrashLogManager.listLogFiles(context) to imported
            }
            files = result.first
            message = if (result.second > 0) {
                "已从 Android 系统导入 ${result.second} 条上次崩溃/异常退出记录。"
            } else {
                "日志保存在应用私有目录 files/crash-logs；没有发现新的系统退出记录。"
            }
        } catch (error: Exception) {
            message = "读取诊断日志失败：${error.javaClass.simpleName}"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("崩溃与终端诊断", style = MaterialTheme.typography.titleMedium)
                Text(
                    "App 会记录 Java/Kotlin 未处理异常、终端启动阶段事件，并在下次启动时导入 Android 保存的 Native Crash / ANR 记录。不会记录命令、终端输出、聊天内容或 API 密钥。",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(message, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scope.launch { refresh() } }, enabled = !loading) {
                        Text(if (loading) "读取中…" else "刷新日志")
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                runCatching {
                                    val archive = withContext(Dispatchers.IO) {
                                        CrashLogManager.createExportArchive(context)
                                    }
                                    val uri = FileProvider.getUriForFile(
                                        context, "${context.packageName}.diagnostics.files", archive
                                    )
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/zip"
                                        putExtra(Intent.EXTRA_SUBJECT, "Codex Android 崩溃诊断日志")
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        clipData = ClipData.newUri(context.contentResolver, "Codex diagnostics", uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(intent, "导出诊断日志"))
                                }.onFailure {
                                    message = "导出失败：${it.javaClass.simpleName}"
                                }
                            }
                        },
                        enabled = files.isNotEmpty() && !loading
                    ) { Text("导出全部日志") }
                }
            }
        }
        if (files.isEmpty()) {
            Text("还没有诊断日志。再次出现闪退后，重新打开 App，系统保存的退出记录会自动显示在这里。")
        } else {
            Text("日志文件（最新在前）", style = MaterialTheme.typography.titleSmall)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(files, key = { it.name }) { file ->
                    Card(Modifier.fillMaxWidth().clickable {
                        selectedFile = file
                        preview = CrashLogManager.readLog(file)
                    }) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text(file.name, style = MaterialTheme.typography.bodyMedium)
                            Text("${file.length()} 字节", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        Text(
            "Native Crash 的原始 tombstone 由 Android 生成；App 会尽可能把系统 trace 保存为 .pb 文件。系统可能不给出 trace；这种情况下仍会保存退出原因、PID、信号状态和最后的终端事件。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    selectedFile?.let { file ->
        AlertDialog(
            onDismissRequest = { selectedFile = null },
            title = { Text(file.name) },
            text = {
                Text(
                    preview.take(14_000) + if (preview.length > 14_000) "\n…（预览已截断；导出压缩包可取得完整文件）" else "",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = { TextButton(onClick = { selectedFile = null }) { Text("关闭") } }
        )
    }
}
