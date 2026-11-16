package io.github.zby1888.codexzh

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.system.exitProcess

/** Private crash diagnostics; terminal breadcrumbs contain fixed event names only. */
internal object CrashLogManager {
    private const val DIRECTORY = "crash-logs"
    private const val MAX_TRACE_BYTES = 1024 * 1024
    private const val MAX_EVENT_LOG_BYTES = 512 * 1024L
    private const val MAX_EXIT_LOGS = 16
    private const val MAX_EXPORTED_FILES = 48
    private val lock = Any()

    @Volatile private var handlerInstalled = false

    fun install(context: Context) {
        synchronized(lock) {
            if (handlerInstalled) return
            val appContext = context.applicationContext
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                runCatching { writeJavaCrash(appContext, thread, error) }
                if (previous != null) previous.uncaughtException(thread, error)
                else {
                    Process.killProcess(Process.myPid())
                    exitProcess(10)
                }
            }
            handlerInstalled = true
        }
    }

    /**
     * Native crash traces are Android tombstone protobufs on API 31+.
     * Preserve the raw stream byte-for-byte for offline decoding.
     */
    fun collectPreviousProcessExits(context: Context): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            recordEvent(context.filesDir, "diagnostics.exit_info_unavailable_api")
            return 0
        }
        val records = try {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            manager.getHistoricalProcessExitReasons(context.packageName, 0, 32)
        } catch (_: Exception) {
            recordEvent(context.filesDir, "diagnostics.exit_info_read_failed")
            return 0
        }

        var written = 0
        records.asSequence()
            .filter { it.processName == context.packageName }
            .filter { it.reason in DIAGNOSTIC_EXIT_REASONS }
            .sortedBy { it.timestamp }
            .forEach { info ->
                val base = "exit-${info.timestamp}-${info.pid}-${info.reason}"
                val summary = File(logDirectory(context), "$base.txt")
                if (summary.exists()) return@forEach

                val trace = try {
                    info.traceInputStream?.use { readLimited(it, MAX_TRACE_BYTES) }
                } catch (_: Exception) { null }

                val traceFile = if (!trace.isNullOrEmpty()) {
                    val suffix = if (info.reason == EXIT_NATIVE_CRASH) "tombstone.pb" else "trace"
                    File(logDirectory(context), "$base.$suffix").also {
                        runCatching { writeAtomically(it, trace) }
                    }.takeIf { it.exists() }
                } else null

                val report = buildString {
                    appendLine("Codex Android process exit report")
                    appendLine("timestamp=${formatTimestamp(info.timestamp)}")
                    appendLine("package=${context.packageName}")
                    appendLine("process=${safeText(info.processName, 160)}")
                    appendLine("pid=${info.pid}")
                    appendLine("reason=${reasonName(info.reason)} (${info.reason})")
                    appendLine("subreason=${info.subReason}")
                    appendLine("status=${info.status}")
                    appendLine("importance=${info.importance}")
                    appendLine("pss_kb=${info.pss}")
                    appendLine("rss_kb=${info.rss}")
                    appendLine("description=${safeText(info.description, 500)}")
                    appendLine("trace_available=${traceFile != null}")
                    if (traceFile != null) {
                        appendLine("trace_file=${traceFile.name}")
                        if (info.reason == EXIT_NATIVE_CRASH) appendLine("trace_format=Android protobuf tombstone")
                    } else {
                        appendLine("trace_note=Android did not provide a trace stream; metadata and event log were saved.")
                    }
                }
                runCatching {
                    writeAtomically(summary, report.toByteArray(Charsets.UTF_8))
                    written++
                }
            }
        if (written > 0) recordEvent(context.filesDir, "diagnostics.previous_exit_imported")
        trimOldExitLogs(context)
        return written
    }

    /** Only fixed event names are accepted. Never pass command text or user content. */
    fun recordEvent(filesDir: File, eventName: String) {
        val safeName = eventName.take(96).takeIf { it.matches(Regex("[A-Za-z0-9_.-]+")) }
            ?: "event.invalid_name"
        synchronized(lock) {
            runCatching {
                val directory = File(filesDir, DIRECTORY)
                if (!directory.isDirectory && !directory.mkdirs()) return
                val eventFile = File(directory, "events.log")
                if (eventFile.exists() && eventFile.length() > MAX_EVENT_LOG_BYTES) {
                    val previous = File(directory, "events.previous.log")
                    if (previous.exists()) previous.delete()
                    eventFile.renameTo(previous)
                }
                FileOutputStream(eventFile, true).use { output ->
                    output.write("${formatTimestamp(System.currentTimeMillis())} $safeName\n".toByteArray(Charsets.UTF_8))
                    output.fd.sync()
                }
            }
        }
    }

    fun listLogFiles(context: Context): List<File> {
        val directory = logDirectory(context)
        if (!directory.isDirectory) return emptyList()
        return directory.listFiles()
            ?.filter { it.isFile && (it.extension == "txt" || it.name == "events.log" || it.name == "events.previous.log") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }

    fun readLog(file: File, maxChars: Int = 48_000): String = runCatching {
        file.inputStream().use { input ->
            val text = String(readLimited(input, maxChars * 4), Charsets.UTF_8)
            if (text.length > maxChars) text.takeLast(maxChars) else text
        }
    }.getOrElse { "读取日志失败：${it.javaClass.simpleName}" }

    fun createExportArchive(context: Context): File {
        val sourceFiles = logDirectory(context).listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".tmp") }
            ?.sortedByDescending { it.lastModified() }
            ?.take(MAX_EXPORTED_FILES)
            .orEmpty()
        val exportDirectory = File(context.cacheDir, "crash-exports")
        check(exportDirectory.isDirectory || exportDirectory.mkdirs()) { "无法创建诊断日志导出目录" }
        val archive = File(exportDirectory, "codex-crash-logs.zip")
        ZipOutputStream(FileOutputStream(archive)).use { zip ->
            zip.putNextEntry(ZipEntry("README.txt"))
            zip.write(("Codex Android private diagnostics.\n" +
                "Includes fixed app events, exit metadata and raw system traces/tombstones when available.\n" +
                "System tombstones may contain device paths, registers and diagnostic data; review before sharing.\n" +
                "The app event log does not record terminal commands/output, chat messages or API credentials.\n")
                .toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            sourceFiles.forEach { file ->
                zip.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return archive
    }

    private fun writeJavaCrash(context: Context, thread: Thread, error: Throwable) {
        val directory = logDirectory(context)
        if (!directory.isDirectory && !directory.mkdirs()) return
        val report = buildString {
            appendLine("Codex Android uncaught exception")
            appendLine("timestamp=${formatTimestamp(System.currentTimeMillis())}")
            appendLine("package=${context.packageName}")
            appendLine("pid=${Process.myPid()}")
            appendLine("thread=${safeText(thread.name, 160)}")
            var current: Throwable? = error
            var depth = 0
            while (current != null && depth < 8) {
                appendLine("exception[$depth]=${current.javaClass.name}")
                current.stackTrace.take(120).forEach { appendLine("    at ${safeText(it.toString(), 500)}") }
                current = current.cause
                depth++
            }
        }
        writeAtomically(
            File(directory, "java-${System.currentTimeMillis()}-${Process.myPid()}.txt"),
            report.toByteArray(Charsets.UTF_8)
        )
    }

    private fun trimOldExitLogs(context: Context) {
        val directory = logDirectory(context)
        val summaries = directory.listFiles()
            ?.filter { it.isFile && it.name.startsWith("exit-") && it.extension == "txt" }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        summaries.drop(MAX_EXIT_LOGS).forEach { summary ->
            val prefix = summary.name.removeSuffix(".txt")
            directory.listFiles()?.filter { it.name == summary.name || it.name.startsWith("$prefix.") }
                ?.forEach { it.delete() }
        }
    }

    private fun logDirectory(context: Context) = File(context.filesDir, DIRECTORY)

    private fun writeAtomically(destination: File, bytes: ByteArray) {
        val temporary = File(destination.parentFile, destination.name + ".tmp")
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        if (destination.exists()) destination.delete()
        check(temporary.renameTo(destination)) { "无法保存诊断日志" }
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 32 * 1024))
        val buffer = ByteArray(8192)
        var remaining = limit
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (count <= 0) break
            output.write(buffer, 0, count)
            remaining -= count
        }
        return output.toByteArray()
    }

    private fun safeText(value: String?, maxLength: Int): String {
        val raw = value.orEmpty().replace(Regex("[\\r\\n\\u0000-\\u001f]"), " ")
        return raw.take(maxLength)
            .replace(Regex("(?i)(bearer\\s+)[A-Za-z0-9._~-]+"), "$1[REDACTED]")
            .replace(Regex("(?i)((?:api[_-]?key|access[_-]?token|refresh[_-]?token)\\s*[:=]\\s*)[^\\s,;]+"), "$1[REDACTED]")
            .replace(Regex("\\bsk-[A-Za-z0-9_-]{12,}\\b"), "[REDACTED]")
    }

    private fun formatTimestamp(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(timestamp))

    private fun reasonName(reason: Int): String = when (reason) {
        2 -> "SIGNALED"
        3 -> "LOW_MEMORY"
        4 -> "CRASH_JAVA"
        5 -> "CRASH_NATIVE"
        6 -> "ANR"
        7 -> "INITIALIZATION_FAILURE"
        9 -> "EXCESSIVE_RESOURCE_USAGE"
        else -> "OTHER"
    }

    private const val EXIT_NATIVE_CRASH = 5
    private val DIAGNOSTIC_EXIT_REASONS = setOf(2, 3, 4, 5, 6, 7, 9)
}
