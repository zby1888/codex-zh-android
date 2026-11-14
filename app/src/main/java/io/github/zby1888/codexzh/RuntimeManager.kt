package io.github.zby1888.codexzh

import android.content.Context
import android.os.Build
import java.util.Properties

internal enum class RuntimePhase(val title: String) {
    UNSUPPORTED_ABI("设备架构不受支持"),
    NOT_PREPARED("私有目录尚未准备"),
    LAYOUT_READY("私有目录已准备"),
    RUNTIME_READY("ARM64 工具链已安装"),
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

        val installed = marker.getProperty("runtimeInstalled") == "true" &&
            marker.getProperty("abi") == "arm64-v8a" &&
            marker.getProperty("applicationId") == paths.appPackageName &&
            paths.bash.isFile && paths.env.exists() && paths.git.exists() &&
            paths.jq.exists() && paths.execPreload.isFile

        return if (installed) {
            RuntimeStatus(
                RuntimePhase.RUNTIME_READY,
                "Bash、Coreutils、Git、jq 和命令路径兼容层已就绪。当前为 Android Bionic/ARM64 用户空间，不是 glibc 桌面 Linux。"
            )
        } else {
            RuntimeStatus(
                RuntimePhase.LAYOUT_READY,
                "应用私有目录已建立。下一步从 APK 内置资源离线安装 ARM64 工具链；不会访问 Termux。"
            )
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
}
