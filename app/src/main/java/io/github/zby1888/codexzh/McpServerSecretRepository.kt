package io.github.zby1888.codexzh

import android.content.Context
import org.json.JSONObject

/**
 * Stores MCP environment secrets encrypted with the Android Keystore-backed
 * SecretStore. Only environment-variable names are kept in ordinary preferences;
 * plaintext values are supplied to the Codex App Server child process at launch.
 */
internal class McpServerSecretRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )
    private val secretStore = SecretStore(context.applicationContext)

    @Synchronized
    fun saveValues(serverName: String, values: Map<String, String>): Map<String, String> {
        requireServerName(serverName)
        val root = readRoot()
        val serverIndex = root.optJSONObject(serverName) ?: JSONObject()
        val savedNames = linkedMapOf<String, String>()

        values.toSortedMap().forEach { (key, value) ->
            require(key.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}"))) {
                "环境变量名称不合法：$key"
            }
            require(value.none { it == '\\r' || it == '\\n' }) {
                "环境变量值不能包含换行符：$key"
            }
            if (value.isEmpty()) return@forEach

            val envName = environmentName(serverName, key)
            secretStore.put(secretAlias(serverName, key), value)
            serverIndex.put(key, envName)
            savedNames[key] = envName
        }

        if (serverIndex.length() > 0) {
            root.put(serverName, serverIndex)
        }
        check(preferences.edit().putString(ROOT_KEY, root.toString()).commit()) {
            "无法保存 MCP 凭据索引"
        }
        return savedNames
    }

    @Synchronized
    fun environment(): Map<String, String> {
        val root = readRoot()
        val values = linkedMapOf<String, String>()
        val servers = root.keys()
        while (servers.hasNext()) {
            val serverName = servers.next()
            val serverIndex = root.optJSONObject(serverName) ?: continue
            val keys = serverIndex.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val envName = serverIndex.optString(key)
                if (envName.isBlank()) continue
                val secret = secretStore.get(secretAlias(serverName, key)) ?: continue
                values[envName] = secret
            }
        }
        return values
    }

    @Synchronized
    fun removeServer(serverName: String) {
        requireServerName(serverName)
        val root = readRoot()
        val serverIndex = root.optJSONObject(serverName)
        if (serverIndex != null) {
            val keys = serverIndex.keys()
            val aliases = mutableListOf<String>()
            while (keys.hasNext()) aliases += secretAlias(serverName, keys.next())
            aliases.forEach(secretStore::delete)
        }
        root.remove(serverName)
        check(preferences.edit().putString(ROOT_KEY, root.toString()).commit()) {
            "无法更新 MCP 凭据索引"
        }
    }

    @Synchronized
    fun environmentNames(serverName: String): Map<String, String> {
        requireServerName(serverName)
        val serverIndex = readRoot().optJSONObject(serverName) ?: return emptyMap()
        val result = linkedMapOf<String, String>()
        val keys = serverIndex.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result[key] = serverIndex.optString(key)
        }
        return result
    }

    private fun readRoot(): JSONObject = runCatching {
        JSONObject(preferences.getString(ROOT_KEY, "{}") ?: "{}")
    }.getOrElse { JSONObject() }

    private fun secretAlias(serverName: String, key: String) =
        "codex_mcp_${serverName}_${key}"

    private fun environmentName(serverName: String, key: String): String {
        val serverPrefix = serverName.uppercase().replace('-', '_')
        return "CODEX_ZH_MCP_${serverPrefix}_${key.uppercase()}"
    }

    private fun requireServerName(serverName: String) {
        require(serverName.matches(Regex("[A-Za-z0-9_-]{1,64}"))) {
            "MCP 服务名称只能包含英文字母、数字、下划线和连字符（最多 64 位）"
        }
    }

    private companion object {
        const val PREFS_NAME = "codex_mcp_secret_index_v1"
        const val ROOT_KEY = "server_environment_names"
    }
}
