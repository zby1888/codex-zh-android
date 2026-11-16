package io.github.zby1888.codexzh

import org.json.JSONArray
import org.json.JSONObject

/**
 * Version-scoped MCP operations exposed by the pinned Codex App Server.
 * This is the protocol layer; UI approvals and connection editing are separate.
 */
internal class CodexMcpController(
    private val client: CodexAppServerClient
) {
    fun listServers(
        threadId: String? = null,
        serverName: String? = null,
        cursor: String? = null,
        limit: Int? = null
    ): JSONObject {
        val params = JSONObject()
        if (!threadId.isNullOrBlank()) params.put("threadId", threadId)
        if (!serverName.isNullOrBlank()) params.put("serverName", serverName)
        if (!cursor.isNullOrBlank()) params.put("cursor", cursor)
        if (limit != null) params.put("limit", limit)
        return client.request("mcpServerStatus/list", params)
    }

    fun refreshServers(): JSONObject =
        client.request("mcpServer/refresh", JSONObject())

    fun reloadConfiguration(): JSONObject =
        client.request("config/mcpServer/reload", JSONObject())

    /**
     * Adds or updates one MCP server in the private CODEX_HOME config.
     * Server names are restricted so the quoted config key path is unambiguous.
     * Secret values must never be placed in the config JSON; use process
     * environment references managed by the encrypted credential store.
     */
    fun upsertServerConfig(serverName: String, config: JSONObject): JSONObject {
        require(serverName.matches(Regex("[A-Za-z0-9_-]{1,64}"))) {
            "MCP 服务名称只能包含英文字母、数字、下划线和连字符（最多 64 位）"
        }
        require(config.length() > 0) { "MCP 服务配置不能为空" }
        val edits = JSONArray().put(
            JSONObject()
                .put("keyPath", "mcp_servers.\"$serverName\"")
                .put("mergeStrategy", "upsert")
                .put("value", config)
        )
        return client.request(
            "config/batchWrite",
            JSONObject().put("edits", edits).put("reloadUserConfig", true)
        )
    }

    /** Removes one MCP server entry from the private Codex config. */
    fun removeServerConfig(serverName: String): JSONObject {
        require(serverName.matches(Regex("[A-Za-z0-9_-]{1,64}"))) {
            "MCP 服务名称只能包含英文字母、数字、下划线和连字符（最多 64 位）"
        }
        val edits = JSONArray().put(
            JSONObject()
                .put("keyPath", "mcp_servers.\"$serverName\"")
                .put("mergeStrategy", "replace")
                .put("value", JSONObject.NULL)
        )
        return client.request(
            "config/batchWrite",
            JSONObject().put("edits", edits).put("reloadUserConfig", true)
        )
    }

    fun readResource(
        serverName: String,
        uri: String,
        threadId: String? = null
    ): JSONObject {
        require(serverName.isNotBlank()) { "MCP 服务名称不能为空" }
        require(uri.isNotBlank()) { "MCP 资源地址不能为空" }
        val params = JSONObject()
            .put("server", serverName)
            .put("uri", uri)
        if (!threadId.isNullOrBlank()) params.put("threadId", threadId)
        return client.request("mcpServer/resource/read", params)
    }

    fun callTool(
        threadId: String,
        serverName: String,
        toolName: String,
        arguments: JSONObject = JSONObject()
    ): JSONObject {
        require(threadId.isNotBlank()) { "会话 ID 不能为空" }
        require(serverName.isNotBlank()) { "MCP 服务名称不能为空" }
        require(toolName.isNotBlank()) { "MCP 工具名称不能为空" }
        return client.request(
            "mcpServer/tool/call",
            JSONObject()
                .put("threadId", threadId)
                .put("server", serverName)
                .put("tool", toolName)
                .put("arguments", arguments)
        )
    }

    fun startOAuthLogin(
        serverName: String,
        threadId: String? = null,
        scopes: List<String>? = null,
        timeoutSeconds: Int? = null
    ): JSONObject {
        require(serverName.isNotBlank()) { "MCP 服务名称不能为空" }
        val params = JSONObject().put("name", serverName)
        if (!threadId.isNullOrBlank()) params.put("threadId", threadId)
        if (!scopes.isNullOrEmpty()) {
            val scopeArray = JSONArray()
            scopes.forEach(scopeArray::put)
            params.put("scopes", scopeArray)
        }
        if (timeoutSeconds != null) params.put("timeoutSecs", timeoutSeconds)
        return client.request("mcpServer/oauth/login", params)
    }
}
