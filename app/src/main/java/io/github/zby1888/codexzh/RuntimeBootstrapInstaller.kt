package io.github.zby1888.codexzh

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Properties
import java.util.zip.ZipInputStream

internal data class RuntimeInstallProgress(
    val message: String,
    val fraction: Float? = null
)

/**
 * Installs the signed APK's bundled bootstrap into app-private storage.
 * ZIP paths and symlinks are validated before they can be created.
 */
internal class RuntimeBootstrapInstaller(
    private val context: Context,
    private val manager: RuntimeManager
) {
    private val paths = manager.paths

    fun install(onProgress: (RuntimeInstallProgress) -> Unit = {}): RuntimeStatus {
        if (!BuildAbi.supportsArm64()) return manager.inspect()

        var backup: File? = null
        var switched = false
        val staging = File(paths.runtimeRoot, "prefix-staging")
        val oldPrefix = File(paths.runtimeRoot, "prefix-backup")

        return try {
            onProgress(RuntimeInstallProgress("正在准备应用私有目录…"))
            paths.createDirectories()
            onProgress(RuntimeInstallProgress("正在读取并检查运行时清单…"))
            val manifest = readManifest()
            verifyManifest(manifest)
            onProgress(RuntimeInstallProgress("正在校验内置运行环境完整性…", 0f))
            verifyAssetHash(manifest, onProgress)

            deleteTreeNoFollow(staging)
            deleteTreeNoFollow(oldPrefix)
            ensureDirectory(staging)

            onProgress(RuntimeInstallProgress("正在展开 ARM64 运行环境…"))
            val symlinkText = extractArchive(staging, onProgress)
            onProgress(RuntimeInstallProgress("正在恢复运行时文件链接…"))
            restoreSymlinks(staging, symlinkText)

            if (paths.prefix.exists()) {
                if (!paths.prefix.renameTo(oldPrefix)) {
                    throw IllegalStateException("无法备份现有运行环境目录")
                }
                backup = oldPrefix
            }
            if (!staging.renameTo(paths.prefix)) {
                backup?.renameTo(paths.prefix)
                backup = null
                throw IllegalStateException("无法切换到新 ARM64 运行环境")
            }
            switched = true

            onProgress(RuntimeInstallProgress("正在检查 Codex CLI 和辅助程序…"))
            verifyInstalledFiles()
            writeRuntimeMarker(manifest)
            backup?.let { old -> runCatching { deleteTreeNoFollow(old) } }
            backup = null

            val result = manager.inspect()
            if (result.phase == RuntimePhase.RUNTIME_READY) {
                onProgress(RuntimeInstallProgress("运行环境已安装，Codex CLI 启动检查通过。", 1f))
            }
            result
        } catch (error: Exception) {
            if (switched) {
                runCatching { deleteTreeNoFollow(paths.prefix) }
                backup?.let { old ->
                    if (old.exists()) runCatching { old.renameTo(paths.prefix) }
                }
            }
            runCatching { deleteTreeNoFollow(staging) }
            val message = error.message?.let { "安装 ARM64 工具链失败：$it" } ?: "安装 ARM64 工具链失败。"
            onProgress(RuntimeInstallProgress(message))
            RuntimeStatus(RuntimePhase.ERROR, message)
        }
    }

    private fun readManifest(): JSONObject {
        val bytes = context.assets.open(MANIFEST_ASSET).use { it.readBytes() }
        return JSONObject(bytes.toString(Charsets.UTF_8))
    }

    private fun verifyManifest(manifest: JSONObject) {
        check(manifest.optInt("schemaVersion") == 1) { "运行时清单版本不受支持" }
        check(manifest.optString("abi") == "arm64-v8a") { "运行时不是 ARM64 架构" }
        check(manifest.optString("applicationId") == context.packageName) {
            "运行时与当前 APK 包名不匹配，拒绝安装"
        }
        check(manifest.optString("prefix") == "runtime/prefix") { "运行时安装路径不匹配" }
        check(manifest.optString("rootfs") == "files") { "运行时根目录不匹配" }
        check(manifest.optString("archive") == ARCHIVE_ASSET.substringAfterLast('/')) {
            "运行时压缩包名称不匹配"
        }
        check(manifest.optLong("archiveSize") > 0L) { "运行时清单缺少压缩包大小" }
        check(manifest.optLong("maxEntryBytes") == MAX_ENTRY_BYTES) {
            "运行时单文件安全限制与 APK 安装器不匹配"
        }
        check(manifest.optLong("maxTotalBytes") == MAX_TOTAL_BYTES) {
            "运行时总解压安全限制与 APK 安装器不匹配"
        }
        check(manifest.optString("sha256").matches(Regex("^[0-9a-f]{64}$"))) {
            "运行时清单 SHA-256 格式无效"
        }
        check(
            manifest.optJSONObject("runtimePatches")
                ?.optString("codexAndroidCurrentExe") == REQUIRED_ANDROID_PATCH_ID
        ) { "运行环境缺少 Android linker/current_exe 修复，拒绝安装旧版运行时" }
    }

    private fun verifyAssetHash(
        manifest: JSONObject,
        onProgress: (RuntimeInstallProgress) -> Unit
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        var byteCount = 0L
        val expectedBytes = manifest.getLong("archiveSize")
        var lastReportedPercent = -5
        context.assets.open(ARCHIVE_ASSET).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                byteCount += count
                digest.update(buffer, 0, count)
                val percent = ((byteCount * 100L) / expectedBytes).toInt().coerceIn(0, 100)
                if (percent >= lastReportedPercent + 5 || percent == 100) {
                    lastReportedPercent = percent
                    onProgress(RuntimeInstallProgress("正在校验内置文件 · $percent%", percent / 100f))
                }
            }
        }
        check(byteCount == manifest.getLong("archiveSize")) { "运行时资源大小与清单不一致" }
        val actual = digest.digest().joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        check(actual == manifest.getString("sha256")) { "运行时资源校验失败，SHA-256 不一致" }
    }

    private fun extractArchive(
        staging: File,
        onProgress: (RuntimeInstallProgress) -> Unit
    ): String {
        val seen = HashSet<String>()
        var totalBytes = 0L
        var entryCount = 0
        var symlinkText: String? = null

        context.assets.open(ARCHIVE_ASSET).use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    if (entryCount == 1 || entryCount % 150 == 0) {
                        onProgress(RuntimeInstallProgress("正在展开运行环境 · 已处理 $entryCount 项文件"))
                    }
                    check(entryCount <= MAX_ENTRIES) { "运行时文件数量超出安全限制" }
                    val relative = normalizeArchivePath(entry.name)
                    check(seen.add(relative)) { "运行时压缩包包含重复路径：$relative" }

                    if (relative == SYMLINKS_FILE) {
                        check(symlinkText == null) { "运行时包含重复的符号链接清单" }
                        val bytes = readEntryBounded(zip, MAX_SYMLINK_BYTES) { count ->
                            totalBytes += count
                            check(totalBytes <= MAX_TOTAL_BYTES) { "运行时解压体积超出安全限制" }
                        }
                        symlinkText = bytes.toString(Charsets.UTF_8)
                    } else {
                        val destination = File(staging, relative)
                        assertContained(staging, destination)
                        if (entry.isDirectory || entry.name.endsWith("/")) {
                            ensureDirectory(destination)
                        } else {
                            val parent = destination.parentFile
                                ?: throw IllegalStateException("运行时文件缺少父目录")
                            ensureDirectory(parent)
                            check(destination.createNewFile()) { "运行时文件路径已存在：$relative" }
                            var fileBytes = 0L
                            FileOutputStream(destination).use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    val count = zip.read(buffer)
                                    if (count < 0) break
                                    fileBytes += count
                                    totalBytes += count
                                    check(fileBytes <= MAX_ENTRY_BYTES) { "运行时单文件超出安全限制：$relative" }
                                    check(totalBytes <= MAX_TOTAL_BYTES) { "运行时解压体积超出安全限制" }
                                    output.write(buffer, 0, count)
                                }
                            }
                            setFileMode(destination, isExecutablePath(relative))
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
        return symlinkText ?: throw IllegalStateException("运行时压缩包缺少符号链接清单")
    }

    private fun readEntryBounded(
        zip: ZipInputStream,
        limit: Long,
        onBytes: (Long) -> Unit
    ): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var size = 0L
        while (true) {
            val count = zip.read(buffer)
            if (count < 0) break
            size += count
            check(size <= limit) { "符号链接清单过大" }
            onBytes(count.toLong())
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun restoreSymlinks(staging: File, contents: String) {
        val expectedPrefix = "/data/data/${context.packageName}/files/runtime/prefix"
        val seen = HashSet<String>()

        contents.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val separator = line.indexOf('←')
            check(separator > 0 && separator < line.lastIndex) { "符号链接清单格式无效" }
            val target = line.substring(0, separator)
            val rawLink = line.substring(separator + 1)
            val linkPath = normalizeArchivePath(rawLink.removePrefix("./"))
            check(seen.add(linkPath)) { "符号链接路径重复：$linkPath" }
            validateSymlinkTarget(linkPath, target, expectedPrefix)

            val link = File(staging, linkPath)
            assertContained(staging, link)
            val parent = link.parentFile ?: throw IllegalStateException("符号链接缺少父目录")
            ensureDirectory(parent)
            ensureNoSymlinkParents(staging, parent)
            check(lstatOrNull(link) == null) { "符号链接目标路径已存在：$linkPath" }
            Os.symlink(target, link.absolutePath)
        }
    }

    private fun validateSymlinkTarget(link: String, target: String, expectedPrefix: String) {
        check(target.isNotBlank() && !target.contains('\u0000')) { "符号链接目标无效：$link" }
        if (target.startsWith("/")) {
            val allowedSystemPath = listOf(
                "/system/", "/apex/", "/vendor/", "/product/", "/odm/", "/proc/", "/dev/"
            ).any(target::startsWith)
            check(
                target == expectedPrefix || target.startsWith("$expectedPrefix/") || allowedSystemPath
            ) { "符号链接试图访问应用私有前缀以外的位置：$link" }
        } else {
            val stack = ArrayDeque<String>()
            val base = link.substringBeforeLast('/', "")
            (if (base.isEmpty()) target else "$base/$target").split('/').forEach { part ->
                when (part) {
                    "", "." -> Unit
                    ".." -> {
                        check(stack.isNotEmpty()) { "符号链接越过运行时前缀：$link" }
                        stack.removeLast()
                    }
                    else -> stack.addLast(part)
                }
            }
        }
    }

    private fun verifyInstalledFiles() {
        val required = listOf(
            paths.bash, paths.env, paths.git, paths.execPreload,
            paths.codex, paths.codexCodeModeHost
        )
        val missing = required.filterNot { it.exists() }
        check(missing.isEmpty()) {
            "运行时关键文件缺失：${missing.joinToString { it.relativeTo(paths.prefix).path }}"
        }
        check(paths.codex.canExecute()) { "Codex CLI 没有执行权限" }
        check(paths.codexCodeModeHost.canExecute()) { "Code Mode host 没有执行权限" }
    }

    private fun writeRuntimeMarker(manifest: JSONObject) {
        val properties = Properties().apply {
            setProperty("runtimeInstalled", "true")
            setProperty("abi", "arm64-v8a")
            setProperty("applicationId", context.packageName)
            setProperty("sha256", manifest.getString("sha256"))
            setProperty("sourceRef", manifest.optString("sourceRef"))
            setProperty(
                "codexAndroidCurrentExe",
                manifest.optJSONObject("runtimePatches")?.optString("codexAndroidCurrentExe").orEmpty()
            )
            setProperty("installedAtVersion", "2")
        }
        val temporary = File(paths.runtimeRoot, ".runtime.properties.tmp")
        temporary.outputStream().buffered().use { properties.store(it, "Codex Zh Android runtime") }
        Os.rename(temporary.absolutePath, paths.runtimeMarker.absolutePath)
    }

    private fun normalizeArchivePath(raw: String): String {
        check(raw.isNotBlank() && !raw.startsWith("/") && !raw.contains('\\') && !raw.contains('\u0000')) {
            "运行时压缩包包含非法路径"
        }
        val cleaned = raw.removePrefix("./").trimEnd('/')
        val parts = cleaned.split('/')
        check(parts.isNotEmpty() && parts.none { it == ".." || it == "." }) {
            "运行时压缩包路径越界：$raw"
        }
        return parts.filter { it.isNotEmpty() }.joinToString("/").also {
            check(it.isNotBlank()) { "运行时压缩包包含空路径" }
        }
    }

    private fun isExecutablePath(path: String): Boolean =
        path.startsWith("bin/") || path.startsWith("libexec/") || path.startsWith("sbin/")

    private fun setFileMode(file: File, executable: Boolean) {
        Os.chmod(file.absolutePath, if (executable) MODE_0700 else MODE_0600)
    }

    private fun ensureDirectory(directory: File) {
        val stat = lstatOrNull(directory)
        if (stat != null) {
            val type = stat.st_mode and OsConstants.S_IFMT
            check(type == OsConstants.S_IFDIR) { "运行时目录被非目录对象占用：${directory.name}" }
        } else {
            check(directory.mkdirs() || directory.isDirectory) {
                "无法创建运行时目录：${directory.name}"
            }
        }
        Os.chmod(directory.absolutePath, MODE_0700)
    }

    private fun ensureNoSymlinkParents(root: File, directory: File) {
        var current: File? = directory
        while (current != null && current.absolutePath != root.absolutePath) {
            val stat = lstatOrNull(current)
            if (stat != null) {
                check((stat.st_mode and OsConstants.S_IFMT) != OsConstants.S_IFLNK) {
                    "符号链接父目录不允许：${current.name}"
                }
            }
            current = current.parentFile
        }
        check(current != null) { "符号链接父目录越界" }
    }

    private fun assertContained(root: File, child: File) {
        val rootPath = root.canonicalPath.trimEnd(File.separatorChar) + File.separator
        val childPath = child.canonicalPath
        check(childPath.startsWith(rootPath)) { "运行时解包路径越界" }
    }

    private fun lstatOrNull(file: File) = try {
        Os.lstat(file.absolutePath)
    } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ENOENT) null else throw error
    }

    /**
     * File.delete() removes a symlink itself rather than following its target.
     * lstat decides whether to recurse, so deletion never walks through links.
     */
    private fun deleteTreeNoFollow(file: File) {
        val stat = lstatOrNull(file) ?: return
        if ((stat.st_mode and OsConstants.S_IFMT) == OsConstants.S_IFDIR) {
            file.listFiles()?.forEach(::deleteTreeNoFollow)
        }
        check(file.delete()) { "无法清理运行时路径：${file.name}" }
    }

    private object BuildAbi {
        fun supportsArm64(): Boolean =
            android.os.Build.SUPPORTED_ABIS.any { it.equals("arm64-v8a", ignoreCase = true) }
    }

    companion object {
        private const val ARCHIVE_ASSET = "runtime/bootstrap-aarch64.zip"
        private const val MANIFEST_ASSET = "runtime/bootstrap-manifest.json"
        private const val SYMLINKS_FILE = "SYMLINKS.txt"
        private const val REQUIRED_ANDROID_PATCH_ID = "termux-proc-self-exe-v1"
        private const val MAX_ENTRIES = 80_000
        // The pinned Codex 0.169.0 ARM64 executable in the shipped bootstrap is
        // 202,819,272 bytes (about 193.4 MiB). 128 MiB rejected the valid binary.
        // The APK verifies the archive SHA-256 before extraction; keep a bounded
        // 256 MiB per-file cap and a 768 MiB total expansion cap.
        private const val MAX_ENTRY_BYTES = 256L * 1024L * 1024L
        private const val MAX_SYMLINK_BYTES = 4L * 1024L * 1024L
        private const val MAX_TOTAL_BYTES = 768L * 1024L * 1024L
        private const val MODE_0700 = 0x1C0
        private const val MODE_0600 = 0x180
    }
}
