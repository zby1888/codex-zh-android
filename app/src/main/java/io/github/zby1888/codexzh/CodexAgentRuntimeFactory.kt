package io.github.zby1888.codexzh

import android.content.Context

/**
 * Creates a fully isolated Agent connection using the selected Responses API
 * provider. The API key is injected into the child process environment only.
 */
internal class CodexAgentRuntimeFactory(context: Context) {
    private val appContext = context.applicationContext
    private val runtimeManager = RuntimeManager(appContext)
    private val providerRepository = ProviderRepository(appContext)
    private val providerConfigurator = CodexProviderConfigurator(providerRepository)

    fun start(
        providerId: String? = null,
        listener: CodexAppServerClient.Listener = NoopAppServerListener
    ): CodexAgentController {
        val selectedProviderId = providerId ?: providerRepository.getDefaultCodexAgent()?.id
            ?: throw IllegalStateException("请先添加并设置一个 Responses API 服务商为 Codex Agent 默认")
        val runtimeStatus = runtimeManager.inspect()
        check(runtimeStatus.phase == RuntimePhase.RUNTIME_READY) {
            "Codex 私有运行环境尚未就绪：${runtimeStatus.message}"
        }

        val client = CodexAppServerClient(
            paths = runtimeManager.paths,
            listener = listener,
            environmentOverrides = providerConfigurator.environmentFor(selectedProviderId)
        )
        try {
            client.connect()
            providerConfigurator.applyToAppServer(client, selectedProviderId)
            return CodexAgentController(client)
        } catch (error: Exception) {
            client.close()
            throw error
        }
    }

    private object NoopAppServerListener : CodexAppServerClient.Listener {
        override fun onNotification(message: org.json.JSONObject) = Unit
        override fun onServerRequest(message: org.json.JSONObject) = Unit
        override fun onDiagnostic(message: String) = Unit
    }
}
