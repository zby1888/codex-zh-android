package io.github.zby1888.codexzh

import android.content.Context
import java.io.File

/**
 * All writable runtime paths are rooted in this app's private files directory.
 * This class deliberately has no Termux path or shared-storage fallback.
 */
internal data class RuntimePaths(
    val appFiles: File,
    val runtimeRoot: File,
    val prefix: File,
    val home: File,
    val codexHome: File,
    val temp: File
) {
    val bin: File get() = File(prefix, "bin")
    val bash: File get() = File(bin, "bash")
    val env: File get() = File(bin, "env")
    val layoutMarker: File get() = File(runtimeRoot, ".layout.properties")

    fun environment(): Map<String, String> = mapOf(
        "PREFIX" to prefix.absolutePath,
        "HOME" to home.absolutePath,
        "CODEX_HOME" to codexHome.absolutePath,
        "TMPDIR" to temp.absolutePath,
        "PATH" to "${bin.absolutePath}:/system/bin",
        "LD_LIBRARY_PATH" to File(prefix, "lib").absolutePath,
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
            val appFiles = context.filesDir.canonicalFile
            val runtimeRoot = File(appFiles, "runtime")
            return RuntimePaths(
                appFiles = appFiles,
                runtimeRoot = runtimeRoot,
                prefix = File(runtimeRoot, "prefix"),
                home = File(appFiles, "home"),
                codexHome = File(appFiles, "codex"),
                temp = File(runtimeRoot, "tmp")
            )
        }
    }
}
