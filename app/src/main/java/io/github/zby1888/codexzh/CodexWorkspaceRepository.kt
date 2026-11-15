package io.github.zby1888.codexzh

import java.io.File

/**
 * Keeps Agent projects inside the Android app's private HOME/projects directory.
 * A workspace is always canonicalized and checked before it is passed to Codex.
 */
internal class CodexWorkspaceRepository(home: File) {
    private val projectsRoot = File(home, "projects")

    fun list(): List<File> {
        ensureProjectsRoot()
        return projectsRoot.listFiles()
            ?.filter { it.isDirectory }
            ?.map { validate(it) }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
    }

    fun create(name: String): File {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty() && cleanName.length <= 64) {
            "项目名称不能为空，且不能超过 64 个字符"
        }
        require(cleanName != "." && cleanName != "..") { "项目名称不合法" }
        require(cleanName.none { it == '/' || it == '\\' || it == '\u0000' }) {
            "项目名称不能包含路径分隔符"
        }
        ensureProjectsRoot()
        val target = File(projectsRoot, cleanName)
        check(target.mkdirs() || target.isDirectory) { "无法创建项目目录：$cleanName" }
        return validate(target)
    }

    fun validate(candidate: File): File {
        ensureProjectsRoot()
        val canonicalRoot = projectsRoot.canonicalFile
        val canonicalCandidate = candidate.canonicalFile
        val prefix = canonicalRoot.path.trimEnd(File.separatorChar) + File.separator
        require(canonicalCandidate.path.startsWith(prefix)) {
            "项目目录必须位于应用私有 HOME/projects 内"
        }
        require(canonicalCandidate.isDirectory) { "项目目录不存在或不是文件夹" }
        return canonicalCandidate
    }

    fun defaultWorkspace(): File {
        return list().firstOrNull { it.name == "default" } ?: create("default")
    }

    private fun ensureProjectsRoot() {
        check(projectsRoot.mkdirs() || projectsRoot.isDirectory) {
            "无法创建应用私有项目目录"
        }
        val canonicalHome = projectsRoot.parentFile?.canonicalFile
            ?: throw IllegalStateException("应用私有 HOME 路径无效")
        val canonicalRoot = projectsRoot.canonicalFile
        val prefix = canonicalHome.path.trimEnd(File.separatorChar) + File.separator
        check(canonicalRoot.path.startsWith(prefix)) {
            "项目根目录越界，已拒绝访问"
        }
    }
}
