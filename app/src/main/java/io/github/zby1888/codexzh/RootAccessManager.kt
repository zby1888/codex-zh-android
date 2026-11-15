package io.github.zby1888.codexzh

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal enum class RootAccessState {
    NOT_CHECKED,
    UNAVAILABLE,
    DENIED,
    GRANTED,
    TIMEOUT,
    ERROR
}

internal data class RootAccessStatus(
    val state: RootAccessState,
    val message: String,
    val suPath: String? = null
) {
    val isGranted: Boolean get() = state == RootAccessState.GRANTED && !suPath.isNullOrBlank()

    companion object {
        fun notChecked() = RootAccessStatus(
            RootAccessState.NOT_CHECKED,
            "Root 通道默认关闭。需要时点击申请，系统会弹出 Root 管理器授权窗口。"
        )
    }
}

/**
 * Root is an optional, explicitly requested terminal capability.
 *
 * The Codex App Server and normal PTY always stay under the app UID. This
 * class only probes whether a separate su-backed PTY may be started.
 */
internal class RootAccessManager(context: Context) {
    private val appContext = context.applicationContext

    suspend fun requestAccess(): RootAccessStatus = withContext(Dispatchers.IO) {
        val su = findSuBinary() ?: return@withContext RootAccessStatus(
            RootAccessState.UNAVAILABLE,
            "没有找到可执行的 su。设备需要先安装并正确配置 Magisk、KernelSU 或其他 Root 管理器。"
        )
        verifyAccess(su)
    }

    private fun findSuBinary(): File? {
        val candidates = linkedSetOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/debug_ramdisk/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/magisk/su"
        )
        System.getenv("PATH")
            ?.split(File.pathSeparator)
            ?.filter { it.isNotBlank() }
            ?.forEach { candidates += File(it, "su").absolutePath }

        return candidates.asSequence()
            .map(::File)
            .firstOrNull { it.isFile && it.canExecute() }
    }

    private fun verifyAccess(su: File): RootAccessStatus {
        val output = try {
            File.createTempFile("codex-root-probe-", ".txt", appContext.cacheDir)
        } catch (error: Exception) {
            return RootAccessStatus(
                RootAccessState.ERROR,
                "无法创建 Root 检查临时文件：${error.message ?: "未知错误"}"
            )
        }

        try {
            val process = try {
                ProcessBuilder(su.absolutePath, "-c", "id -u")
                    .redirectErrorStream(true)
                    .redirectOutput(output)
                    .start()
            } catch (error: Exception) {
                return RootAccessStatus(
                    RootAccessState.ERROR,
                    "启动 su 失败：${error.message ?: "未知错误"}"
                )
            }

            var exitCode: Int? = null
            var waitFailure: Exception? = null
            val waiter = Thread({
                try {
                    exitCode = process.waitFor()
                } catch (error: Exception) {
                    waitFailure = error
                }
            }, "codex-root-probe-waiter").apply { isDaemon = true }

            waiter.start()
            waiter.join(ROOT_PROBE_TIMEOUT_MS)
            if (waiter.isAlive) {
                process.destroy()
                waiter.join(PROCESS_STOP_GRACE_MS)
                return RootAccessStatus(
                    RootAccessState.TIMEOUT,
                    "等待 Root 授权超时。请检查 Root 管理器是否弹出授权窗口，再重新申请。"
                )
            }

            waitFailure?.let {
                return RootAccessStatus(
                    RootAccessState.ERROR,
                    "等待 Root 检查进程结束失败：${it.message ?: "未知错误"}"
                )
            }

            val result = output.readText(Charsets.UTF_8).trim()
            if (exitCode == 0 && result.lineSequence().lastOrNull()?.trim() == "0") {
                return RootAccessStatus(
                    RootAccessState.GRANTED,
                    "Root 已授权。只有你主动启动的 Root 终端会使用 root 身份；Codex Agent 仍以应用普通身份运行。",
                    su.absolutePath
                )
            }

            RootAccessStatus(
                RootAccessState.DENIED,
                "Root 未获授权或检查未通过。请在 Root 管理器中允许本应用，然后重新申请。"
            )
        } catch (error: Exception) {
            RootAccessStatus(
                RootAccessState.ERROR,
                "Root 状态检查失败：${error.message ?: "未知错误"}"
            )
        } finally {
            output.delete()
        }
    }

    private companion object {
        const val ROOT_PROBE_TIMEOUT_MS = 60_000L
        const val PROCESS_STOP_GRACE_MS = 1_000L
    }
}
