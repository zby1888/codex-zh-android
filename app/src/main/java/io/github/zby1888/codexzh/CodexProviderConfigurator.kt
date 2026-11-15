package io.github.zby1888.codexzh

import org.json.JSONArray
import org.json.JSONObject

/**
 * Writes only non-secret provider metadata into the private Codex config.
 * Credentials remain in Android Keystore and are injected into the child
 * App Server process through environment-variable references.
 */
internal class CodexProviderConfigurator(
    private val repository: ProviderRepository
) {

    fun environmentFor(providerId: String): Map<String, String> =
        repository.codexAgentEnvironment(providerId).values

    fun applyToAppServer(
        client: CodexAppServerClient,
        providerId: String
    ): JSONObject {
        val provider = repository.get(providerId)
            ?: throw IllegalArgumentException("找不到服务商配置")
        require(provider.protocol == ProviderProtocol.CODEX_RESPONSES) {
            "当前服务商不是 Responses API 类型，不能用于 Codex Agent"
        }

        val environment = repository.codexAgentEnvironment(providerId)
        val providerConfig = JSONObject()
            .put("name", provider.displayName)
            .put("base_url", provider.baseUrl)
            .put("wire_api", "responses")

        environment.apiKeyEnvironmentName?.let {
            providerConfig.put("env_key", it)
        }
        if (environment.headerEnvironmentNames.isNotEmpty()) {
            val headers = JSONObject()
            environment.headerEnvironmentNames.forEach { (headerName, envName) ->
                headers.put(headerName, envName)
            }
            providerConfig.put("env_http_headers", headers)
        }

        val edits = JSONArray()
            .put(
                JSONObject()
                    .put("keyPath", "model_providers.${provider.id}")
                    .put("mergeStrategy", "replace")
                    .put("value", providerConfig)
            )
            .put(
                JSONObject()
                    .put("keyPath", "model_provider")
                    .put("mergeStrategy", "replace")
                    .put("value", provider.id)
            )

        return client.request(
            "config/batchWrite",
            JSONObject()
                .put("edits", edits)
                .put("reloadUserConfig", true)
        )
    }
}
