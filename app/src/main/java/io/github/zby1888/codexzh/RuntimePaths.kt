package io.github.zby1888.codexzh

import android.content.Context
import java.io.File

/**
 * All writable runtime paths are rooted in this app's private files directory.
 * The /data/data alias is intentional: the runtime binaries are built with
 * that exact absolute prefix, and Android maps it to the app's user-0 data dir.
 */
internal data class RuntimePaths(
    val appFiles: File,
    val appPackageName: String,
    val runtimeRoot: File,
    val prefix: File,
    val home: File,
    val codexHome: File,
    val temp: File
) {
    val bin: File get() = File(prefix, "bin")
    val bash: File get() = File(bin, "bash")
    val env: File get() = File(bin, "env")
    val git: File get() = File(bin, "git")
    val jq: File get() = File(bin, "jq")
    val codex: File get() = File(bin, "codex")
    val codexCodeModeHost: File get() = File(bin, "codex-code-mode-host")
    val execPreload: File get() = File(prefix, "lib/libtermux-exec-ld-preload.so")
    val layoutMarker: File get() = File(runtimeRoot, ".layout.properties")
    val runtimeMarker: File get() = File(runtimeRoot, ".runtime.properties")

    fun environment(): Map<String, String> = mapOf(
        "PREFIX" to prefix.absolutePath,
        "TERMUX_PREFIX" to prefix.absolutePath,
        "TERMUX__PREFIX" to prefix.absolutePath,
        "TERMUX__ROOTFS" to appFiles.absolutePath,
        "TERMUX__HOME" to home.absolutePath,
        "TERMUX_ANDROID_HOME" to home.absolutePath,
        "TERMUX_APP__PACKAGE_NAME" to appPackageName,
        "HOME" to home.absolutePath,
        "CODEX_HOME" to codexHome.absolutePath,
        "TMPDIR" to temp.absolutePath,
        "PATH" to "${bin.absolutePath}:/system/bin",
        "LD_LIBRARY_PATH" to File(prefix, "lib").absolutePath,
        "LD_PRELOAD" to execPreload.absolutePath,
        "SHELL" to bash.absolutePath,
        "TERM" to "xterm-256color",
        "LANG" to "C.UTF-8",
        "LC_ALL" to "C.UTF-8"
    )

    fun createDirectories() {
        assertContained(appFiles, runtimeRoot)
        assertContained(runtimeRoot, prefix)
        assertContained(appFiles, home)
        assertContained(appFiles, codexHome)
        assertContained(runtimeRoot, temp)

        listOf(
            runtimeRoot, prefix, bin, File(prefix, "lib"), File(prefix, "etc"),
            File(prefix, "var/tmp"), home, codexHome, temp
        ).forEach { directory ->
            if (!directory.exists() && !directory.mkdirs()) {
                throw IllegalStateException("无法创建应用私有目录：${directory.name}")
            }
            check(directory.isDirectory) { "运行环境路径不是目录：${directory.name}" }
        }
    }

    private fun assertContained(parent: File, child: File) {
        val parentPath = parent.canonicalPath.trimEnd(File.separatorChar) + File.separator
        val childPath = child.canonicalPath
        check(childPath.startsWith(parentPath)) { "运行环境路径越界，已拒绝初始化" }
    }

    companion object {
        fun from(context: Context): RuntimePaths {
            // The package bootstrap is compiled against /data/data/<applicationId>.
            // Require this alias to resolve to filesDir so baked-in paths and runtime
            // environment variables are byte-for-byte consistent.
            val appFiles = File("/data/data/${context.packageName}/files")
            check(appFiles.canonicalFile == context.filesDir.canonicalFile) {
                "当前应用数据目录与 ARM64 运行时构建路径不匹配；仅支持 Android 主用户的内部存储安装。"
            }
            return RuntimePaths(
                appFiles = appFiles,
                appPackageName = context.packageName,
                runtimeRoot = File(appFiles, "runtime"),
                prefix = File(appFiles, "runtime/prefix"),
                home = File(appFiles, "home"),
                codexHome = File(appFiles, "codex"),
                temp = File(appFiles, "runtime/tmp")
            )
        }
    }
}
