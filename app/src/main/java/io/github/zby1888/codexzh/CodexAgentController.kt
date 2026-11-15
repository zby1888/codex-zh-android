package io.github.zby1888.codexzh

import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable

internal data class CodexThread(
    val id: String,
    val modelProvider: String?,
    val preview: String?
)

internal data class CodexTurn(
    val id: String,
    val status: String?
)

/**
 * Version-scoped convenience layer for common Codex Agent operations.
 *
 * Method names and request fields are checked against the App Server protocol
 * schemas shipped with the pinned Codex rust-v0.169.0 source. UI code should
 * depend on this controller rather than hand-building JSON-RPC messages.
 */
internal class CodexAgentController(
    private val client: CodexAppServerClient
) : Closeable {

    val isConnected: Boolean get() = client.isInitialized

    fun connect(): JSONObject = client.connect()

    fun mcpController(): CodexMcpController = CodexMcpController(client)

    fun respondToServerRequest(requestId: Any, result: JSONObject) =
        client.respond(requestId, result)

    fun rejectServerRequest(requestId: Any, code: Int, message: String, data: Any? = null) =
        client.respondError(requestId, code, message, data)

    fun listModels(
        modelProvider: String? = null,
        includeHidden: Boolean = false,
        cursor: String? = null
    ): JSONObject {
        val params = JSONObject()
            .put("includeHidden", includeHidden)
        if (!modelProvider.isNullOrBlank()) params.put("modelProvider", modelProvider)
        if (!cursor.isNullOrBlank()) params.put("cursor", cursor)
        return client.request("model/list", params)
    }

    fun startThread(
        workingDirectory: String,
        model: String? = null,
        modelProvider: String? = null
    ): CodexThread {
        require(workingDirectory.isNotBlank()) { "项目目录不能为空" }
        val params = JSONObject().put("cwd", workingDirectory)
        if (!model.isNullOrBlank()) params.put("model", model)
        if (!modelProvider.isNullOrBlank()) params.put("modelProvider", modelProvider)

        val thread = client.request("thread/start", params).optJSONObject("thread")
            ?: throw IllegalStateException("Codex App Server 没有返回会话信息")
        val id = thread.optString("id").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Codex App Server 返回的会话 ID 为空")
        return CodexThread(
            id = id,
            modelProvider = thread.optString("modelProvider").takeIf { it.isNotBlank() },
            preview = thread.optString("preview").takeIf { it.isNotBlank() }
        )
    }

    fun resumeThread(
        threadId: String,
        workingDirectory: String? = null,
        model: String? = null
    ): CodexThread {
        require(threadId.isNotBlank()) { "会话 ID 不能为空" }
        val params = JSONObject().put("threadId", threadId)
        if (!workingDirectory.isNullOrBlank()) params.put("cwd", workingDirectory)
        if (!model.isNullOrBlank()) params.put("model", model)

        val thread = client.request("thread/resume", params).optJSONObject("thread")
            ?: throw IllegalStateException("Codex App Server 没有返回会话信息")
        val id = thread.optString("id").takeIf { it.isNotBlank() } ?: threadId
        return CodexThread(
            id = id,
            modelProvider = thread.optString("modelProvider").takeIf { it.isNotBlank() },
            preview = thread.optString("preview").takeIf { it.isNotBlank() }
        )
    }

    fun startTurn(
        threadId: String,
        text: String,
        workingDirectory: String? = null,
        model: String? = null,
        effort: String? = null
    ): CodexTurn {
        require(threadId.isNotBlank()) { "会话 ID 不能为空" }
        require(text.isNotBlank()) { "消息内容不能为空" }

        val input = JSONArray().put(
            JSONObject()
                .put("type", "text")
                .put("text", text)
        )
        val params = JSONObject()
            .put("threadId", threadId)
            .put("input", input)
        if (!workingDirectory.isNullOrBlank()) params.put("cwd", workingDirectory)
        if (!model.isNullOrBlank()) params.put("model", model)
        if (!effort.isNullOrBlank()) params.put("effort", effort)

        val turn = client.request("turn/start", params).optJSONObject("turn")
            ?: throw IllegalStateException("Codex App Server 没有返回轮次信息")
        val id = turn.optString("id").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Codex App Server 返回的轮次 ID 为空")
        return CodexTurn(id = id, status = turn.optString("status").takeIf { it.isNotBlank() })
    }

    fun steerTurn(threadId: String, expectedTurnId: String, text: String) {
        require(threadId.isNotBlank()) { "会话 ID 不能为空" }
        require(expectedTurnId.isNotBlank()) { "当前轮次 ID 不能为空" }
        require(text.isNotBlank()) { "追加内容不能为空" }
        val input = JSONArray().put(
            JSONObject().put("type", "text").put("text", text)
        )
        client.request(
            "turn/steer",
            JSONObject()
                .put("threadId", threadId)
                .put("expectedTurnId", expectedTurnId)
                .put("input", input)
        )
    }

    fun interruptTurn(threadId: String, turnId: String) {
        require(threadId.isNotBlank()) { "会话 ID 不能为空" }
        require(turnId.isNotBlank()) { "轮次 ID 不能为空" }
        client.request(
            "turn/interrupt",
            JSONObject().put("threadId", threadId).put("turnId", turnId)
        )
    }

    fun listTurns(
        threadId: String,
        cursor: String? = null,
        limit: Int? = null,
        sortDirection: String = "desc"
    ): JSONObject {
        require(threadId.isNotBlank()) { "会话 ID 不能为空" }
        val params = JSONObject()
            .put("threadId", threadId)
            .put("sortDirection", sortDirection)
        if (!cursor.isNullOrBlank()) params.put("cursor", cursor)
        if (limit != null) params.put("limit", limit)
        return client.request("thread/turns/list", params)
    }

    fun listThreads(
        archived: Boolean = false,
        cursor: String? = null,
        limit: Int? = null,
        searchTerm: String? = null,
        workingDirectory: String? = null
    ): JSONObject {
        val params = JSONObject().put("archived", archived)
        if (!cursor.isNullOrBlank()) params.put("cursor", cursor)
        if (limit != null) params.put("limit", limit)
        if (!searchTerm.isNullOrBlank()) params.put("searchTerm", searchTerm)
        if (!workingDirectory.isNullOrBlank()) params.put("cwd", workingDirectory)
        return client.request("thread/list", params)
    }

    fun listItems(
        threadId: String,
        turnId: String? = null,
        cursor: String? = null,
        limit: Int? = null,
        sortDirection: String = "asc"
    ): JSONObject {
        require(threadId.isNotBlank()) { "会话 ID 不能为空" }
        val params = JSONObject()
            .put("threadId", threadId)
            .put("sortDirection", sortDirection)
        if (!turnId.isNullOrBlank()) params.put("turnId", turnId)
        if (!cursor.isNullOrBlank()) params.put("cursor", cursor)
        if (limit != null) params.put("limit", limit)
        return client.request("thread/items/list", params)
    }

    fun renameThread(threadId: String, name: String) {
        require(threadId.isNotBlank()) { "会话 ID 不能为空" }
        require(name.isNotBlank()) { "会话名称不能为空" }
        client.request(
            "thread/setName",
            JSONObject().put("threadId", threadId).put("name", name)
        )
    }

    fun compactThread(threadId: String) {
        require(threadId.isNotBlank()) { "会话 ID 不能为空" }
        client.request("thread/compact/start", JSONObject().put("threadId", threadId))
    }

    /**
     * Reverts history from beforeTurnId onward. The UI must show a confirmation
     * explaining that this removes that turn and every later turn from history.
     */
    fun revertHistoryFrom(threadId: String, beforeTurnId: String) {
        require(threadId.isNotBlank()) { "会话 ID 不能为空" }
        require(beforeTurnId.isNotBlank()) { "目标轮次 ID 不能为空" }
        client.request(
            "thread/revert",
            JSONObject().put("threadId", threadId).put("beforeTurnId", beforeTurnId)
        )
    }

    fun startReview(threadId: String, target: JSONObject) {
        require(threadId.isNotBlank()) { "会话 ID 不能为空" }
        require(target.has("type")) { "审查目标类型不能为空" }
        client.request(
            "review/start",
            JSONObject().put("threadId", threadId).put("target", target)
        )
    }

    fun getGoal(threadId: String): JSONObject {
        require(threadId.isNotBlank()) { "会话 ID 不能为空" }
        return client.request("thread/goal/get", JSONObject().put("threadId", threadId))
    }

    fun setGoal(
        threadId: String,
        objective: String? = null,
        tokenBudget: Long? = null,
        status: String? = null
    ) {
        require(threadId.isNotBlank()) { "会话 ID 不能为空" }
        val params = JSONObject().put("threadId", threadId).put("origin", "user")
        if (objective != null) params.put("objective", objective)
        if (tokenBudget != null) params.put("tokenBudget", tokenBudget)
        if (!status.isNullOrBlank()) params.put("status", status)
        client.request("thread/goal/set", params)
    }

    fun clearGoal(threadId: String) {
        require(threadId.isNotBlank()) { "会话 ID 不能为空" }
        client.request(
            "thread/goal/clear",
            JSONObject().put("threadId", threadId).put("origin", "user")
        )
    }

    fun deleteThread(threadId: String) {
        require(threadId.isNotBlank()) { "会话 ID 不能为空" }
        client.request("thread/delete", JSONObject().put("threadId", threadId))
    }

    fun forkThread(threadId: String, lastTurnId: String? = null): CodexThread {
        require(threadId.isNotBlank()) { "会话 ID 不能为空" }
        val params = JSONObject().put("threadId", threadId)
        if (!lastTurnId.isNullOrBlank()) params.put("lastTurnId", lastTurnId)
        val thread = client.request("thread/fork", params).optJSONObject("thread")
            ?: throw IllegalStateException("Codex App Server 没有返回分支会话信息")
        val id = thread.optString("id").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Codex App Server 返回的分支会话 ID 为空")
        return CodexThread(
            id = id,
            modelProvider = thread.optString("modelProvider").takeIf { it.isNotBlank() },
            preview = thread.optString("preview").takeIf { it.isNotBlank() }
        )
    }

    fun archiveThread(threadId: String) {
        require(threadId.isNotBlank()) { "会话 ID 不能为空" }
        client.request("thread/archive", JSONObject().put("threadId", threadId))
    }

    fun unarchiveThread(threadId: String) {
        require(threadId.isNotBlank()) { "会话 ID 不能为空" }
        client.request("thread/unarchive", JSONObject().put("threadId", threadId))
    }

    override fun close() = client.close()
}
