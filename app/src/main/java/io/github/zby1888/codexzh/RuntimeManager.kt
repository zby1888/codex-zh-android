package io.github.zby1888.codexzh

import android.content.Context
import android.os.Build
import java.io.File
import java.util.Properties
import org.json.JSONObject

internal enum class RuntimePhase(val title: String) {
    UNSUPPORTED_ABI("设备架构不受支持"),
    NOT_PREPARED("私有目录尚未准备"),
    LAYOUT_READY("私有目录已准备"),
    RUNTIME_READY("Codex CLI 已可启动"),
    ERROR("初始化失败")
}

internal data class RuntimeStatus(
    val phase: RuntimePhase,
    val message: String
)

internal class RuntimeManager(context: Context) {
    val paths: RuntimePaths = RuntimePaths.from(context)

    fun inspect(): RuntimeStatus {
        if (!supportsArm64()) {
            return RuntimeStatus(
                RuntimePhase.UNSUPPORTED_ABI,
                "当前设备未报告 arm64-v8a ABI，不能安装本项目的 ARM64 运行时。"
            )
        }

        val layoutExists = paths.layoutMarker.isFile &&
            paths.runtimeRoot.isDirectory &&
            paths.prefix.isDirectory &&
            paths.home.isDirectory &&
            paths.codexHome.isDirectory

        if (!layoutExists) {
            return RuntimeStatus(
                RuntimePhase.NOT_PREPARED,
                "尚未创建应用私有目录。准备操作只创建目录和布局标记，不下载文件、不运行脚本，也不访问 Termux。"
            )
        }

        val marker = Properties()
        if (paths.runtimeMarker.isFile) {
            runCatching {
                paths.runtimeMarker.inputStream().buffered().use { marker.load(it) }
            }
        }

        val baseRuntimeInstalled = marker.getProperty("runtimeInstalled") == "true" &&
            marker.getProperty("abi") == "arm64-v8a" &&
            marker.getProperty("applicationId") == paths.appPackageName &&
            marker.getProperty("codexAndroidCurrentExe") == REQUIRED_ANDROID_PATCH_ID &&
            hasAndroidLauncherPatch() &&
            paths.bash.isFile && paths.env.exists() && paths.git.exists() &&
            paths.execPreload.isFile && paths.codex.isFile && paths.codexCodeModeHost.isFile

        if (!baseRuntimeInstalled) {
            return RuntimeStatus(
                RuntimePhase.LAYOUT_READY,
                "检测到尚未安装或版本过旧的私有运行环境。将从本 APK 重新部署 ARM64 工具链和 Android 启动修复；不会访问 Termux。"
            )
        }

        val codex = File(paths.prefix, "bin/codex")
        val helper = paths.codexCodeModeHost
        if (!codex.isFile || !codex.canExecute() || !helper.isFile || !helper.canExecute()) {
            return RuntimeStatus(
                RuntimePhase.ERROR,
                "基础 ARM64 工具链已安装，但本次运行环境缺少 Codex CLI 或 codex-code-mode-host。请点击下方按钮更新运行环境。"
            )
        }

        return try {
            val output = runCodexVersion(codex, helper)
            RuntimeStatus(
                RuntimePhase.RUNTIME_READY,
                "已在应用私有环境中实际执行 codex --version：$output\nCodex CLI 和辅助程序均已通过启动检查。"
            )
        } catch (error: Exception) {
            RuntimeStatus(
                RuntimePhase.ERROR,
                "基础 ARM64 工具链已安装，但 codex --version 检查未通过：${error.message ?: "未知错误"}\n可以重新安装内置运行环境后再检查。"
            )
        }
    }

    private fun hasAndroidLauncherPatch(): Boolean = runCatching {
        JSONObject(paths.runtimePatchMarker.readText(Charsets.UTF_8))
            .optString("codexAndroidCurrentExe") == REQUIRED_ANDROID_PATCH_ID
    }.getOrDefault(false)

    private fun runCodexVersion(codex: File, helper: File): String {
        check(codex.isFile && codex.canExecute()) { "codex 文件不存在或没有执行权限" }
        check(helper.isFile && helper.canExecute()) { "codex-code-mode-host 文件不存在或没有执行权限" }
        val outputFile = File.createTempFile("codex-version-", ".txt", paths.temp)
        try {
            val builder = ProcessBuilder(paths.commandFor(codex, listOf("--version")))
                .directory(paths.home)
                .redirectErrorStream(true)
                .redirectOutput(outputFile)
            builder.environment().putAll(paths.environment())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.environment()["TERMUX_EXEC__PROC_SELF_EXE"] = codex.absolutePath
            }
            val process = builder.start()

            // Process.waitFor(timeout, unit) and destroyForcibly() are not
            // available on every API level supported by this app (minSdk 24).
            // Wait on a daemon thread and use Thread.join(), which is API-safe.
            var exitCode: Int? = null
            var waitFailure: Exception? = null
            val waiter = Thread {
                try {
                    exitCode = process.waitFor()
                } catch (error: Exception) {
                    waitFailure = error
                }
            }.apply { isDaemon = true }
            waiter.start()
            waiter.join(PROCESS_TIMEOUT_MS)
            if (waiter.isAlive) {
                process.destroy()
                waiter.join(PROCESS_TERMINATION_GRACE_MS)
                throw IllegalStateException("执行 codex --version 超时（15 秒）")
            }
            waitFailure?.let { throw IllegalStateException("等待 Codex CLI 退出失败", it) }

            val output = outputFile.readText(Charsets.UTF_8).trim()
            val code = exitCode ?: throw IllegalStateException("没有取得 Codex CLI 退出状态")
            check(code == 0) {
                "codex --version 退出码 $code：$output"
            }
            check(output.contains(EXPECTED_CODEX_VERSION)) {
                "版本不匹配，期望 $EXPECTED_CODEX_VERSION，实际输出：${output.take(240)}"
            }
            val helperOutputFile = File.createTempFile("codex-host-help-", ".txt", paths.temp)
            try {
                val helperBuilder = ProcessBuilder(paths.commandFor(helper, listOf("--help")))
                    .directory(paths.home)
                    .redirectErrorStream(true)
                    .redirectOutput(helperOutputFile)
                helperBuilder.environment().putAll(paths.environment())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    helperBuilder.environment()["TERMUX_EXEC__PROC_SELF_EXE"] = helper.absolutePath
                }
                val helperProcess = helperBuilder.start()
                var helperExitCode: Int? = null
                var helperWaitFailure: Exception? = null
                val helperWaiter = Thread {
                    try { helperExitCode = helperProcess.waitFor() }
                    catch (error: Exception) { helperWaitFailure = error }
                }.apply { isDaemon = true }
                helperWaiter.start()
                helperWaiter.join(PROCESS_TIMEOUT_MS)
                if (helperWaiter.isAlive) {
                    helperProcess.destroy()
                    helperWaiter.join(PROCESS_TERMINATION_GRACE_MS)
                    throw IllegalStateException("codex-code-mode-host --help 启动超时")
                }
                helperWaitFailure?.let { throw IllegalStateException("等待 Code Mode host 退出失败", it) }
                val helperOutput = helperOutputFile.readText(Charsets.UTF_8)
                check(helperExitCode == 0 && helperOutput.contains("Usage:")) {
                    "codex-code-mode-host 启动检查失败，退出码 $helperExitCode：${helperOutput.take(240)}"
                }
            } finally {
                helperOutputFile.delete()
            }
            return output.lineSequence().firstOrNull().orEmpty().ifBlank {
                "codex $EXPECTED_CODEX_VERSION"
            } + "；Code Mode host 启动检查通过"
        } finally {
            outputFile.delete()
        }
    }

    fun preparePrivateDirectories(): RuntimeStatus {
        if (!supportsArm64()) return inspect()

        return try {
            paths.createDirectories()
            val marker = Properties().apply {
                setProperty("layoutVersion", "1")
                setProperty("abi", "arm64-v8a")
                setProperty("runtimeInstalled", "false")
            }
            paths.layoutMarker.outputStream().buffered().use {
                marker.store(it, "Codex Zh Android private runtime layout")
            }
            inspect()
        } catch (error: Exception) {
            RuntimeStatus(
                RuntimePhase.ERROR,
                error.message?.let { "无法准备私有目录：$it" } ?: "无法准备应用私有目录。"
            )
        }
    }

    private fun supportsArm64(): Boolean =
        Build.SUPPORTED_ABIS.any { it.equals("arm64-v8a", ignoreCase = true) }

    companion object {
        private const val EXPECTED_CODEX_VERSION = "0.169.0"
        private const val REQUIRED_ANDROID_PATCH_ID = "termux-proc-self-exe-v1"
        private const val PROCESS_TIMEOUT_MS = 15_000L
        private const val PROCESS_TERMINATION_GRACE_MS = 1_000L
    }
}
