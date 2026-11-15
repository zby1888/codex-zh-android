package io.github.zby1888.codexzh

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Provider protocols are deliberately explicit. Only CODEX_RESPONSES may be
 * selected for Codex Agent mode; other protocols belong to generic chat adapters.
 */
internal enum class ProviderProtocol(val displayName: String) {
    CODEX_RESPONSES("Codex Agent（Responses API）"),
    OPENAI_CHAT_COMPLETIONS("OpenAI Chat Completions"),
    ANTHROPIC_MESSAGES("Anthropic Messages"),
    GOOGLE_GEMINI("Google Gemini")
}

internal data class ProviderConfig(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val protocol: ProviderProtocol,
    val modelId: String,
    val isDefault: Boolean = false
)

internal data class ProviderCredentials(
    val apiKey: String = "",
    /** Header values may contain credentials, so this map is encrypted as a whole. */
    val customHeaders: Map<String, String> = emptyMap()
)

/**
 * Persists provider metadata separately from encrypted credentials.
 * API keys and custom header values never enter the ordinary config JSON.
 */
internal class ProviderRepository(context: Context) {
    private val preferences = context.getSharedPreferences(CONFIG_PREFS, Context.MODE_PRIVATE)
    private val secretStore = SecretStore(context)

    @Synchronized
    fun list(): List<ProviderConfig> {
        val root = runCatching {
            JSONObject(preferences.getString(PROVIDERS_KEY, "{}") ?: "{}")
        }.getOrElse { JSONObject() }
        val result = mutableListOf<ProviderConfig>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val item = root.optJSONObject(id) ?: continue
            val protocol = runCatching {
                ProviderProtocol.valueOf(item.optString("protocol"))
            }.getOrNull() ?: continue
            result += ProviderConfig(
                id = id,
                displayName = item.optString("displayName"),
                baseUrl = item.optString("baseUrl"),
                protocol = protocol,
                modelId = item.optString("modelId"),
                isDefault = preferences.getString(DEFAULT_PROVIDER_KEY, null) == id
            )
        }
        return result.sortedWith(compareByDescending<ProviderConfig> { it.isDefault }.thenBy { it.displayName })
    }

    @Synchronized
    fun save(
        config: ProviderConfig,
        credentials: ProviderCredentials
    ): ProviderConfig {
        validate(config)
        require(config.id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "服务商 ID 格式不正确" }
        require(credentials.customHeaders.keys.all { it.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}")) }) {
            "自定义请求头名称不合法"
        }
        require(credentials.customHeaders.values.all { value ->
            value.none { it == '\r' || it == '\n' }
        }) { "自定义请求头内容不能包含换行符" }

        val root = readRoot()
        val wasDefault = preferences.getString(DEFAULT_PROVIDER_KEY, null) == config.id
        val firstProvider = root.length() == 0
        val shouldBeDefault = config.isDefault || wasDefault || firstProvider

        // Store secrets first. If metadata persistence fails, the encrypted
        // orphan can be removed later; plaintext is never written to preferences.
        secretStore.put(secretAlias(config.id), encodeCredentials(credentials))
        root.put(config.id, JSONObject()
            .put("displayName", config.displayName.trim())
            .put("baseUrl", normalizeBaseUrl(config.baseUrl))
            .put("protocol", config.protocol.name)
            .put("modelId", config.modelId.trim())
        )
        if (!preferences.edit().putString(PROVIDERS_KEY, root.toString()).commit()) {
            throw IllegalStateException("无法保存服务商配置")
        }
        if (shouldBeDefault) {
            preferences.edit().putString(DEFAULT_PROVIDER_KEY, config.id).apply()
        }
        return config.copy(
            displayName = config.displayName.trim(),
            baseUrl = normalizeBaseUrl(config.baseUrl),
            modelId = config.modelId.trim(),
            isDefault = shouldBeDefault
        )
    }

    @Synchronized
    fun get(providerId: String): ProviderConfig? = list().firstOrNull { it.id == providerId }

    @Synchronized
    fun codexAgentEnvironment(providerId: String): CodexProviderEnvironment {
        val provider = get(providerId) ?: throw IllegalArgumentException("找不到服务商配置")
        require(provider.protocol == ProviderProtocol.CODEX_RESPONSES) {
            "只有 Responses API 兼容服务商可以用于 Codex Agent 模式"
        }
        val credentials = credentials(providerId) ?: ProviderCredentials()
        val prefix = "CODEX_ZH_PROVIDER_" + providerId.uppercase().replace(Regex("[^A-Z0-9]"), "_")
        val values = linkedMapOf<String, String>()
        val apiKeyEnv = if (credentials.apiKey.isNotBlank()) "${prefix}_API_KEY" else null
        if (apiKeyEnv != null) values[apiKeyEnv] = credentials.apiKey
        val headerEnv = linkedMapOf<String, String>()
        credentials.customHeaders.toSortedMap().forEachIndexed { index, (name, value) ->
            val envName = "${prefix}_HEADER_${index + 1}"
            headerEnv[name] = envName
            values[envName] = value
        }
        return CodexProviderEnvironment(apiKeyEnv, headerEnv, values)
    }

    @Synchronized
    fun setDefault(providerId: String) {
        require(readRoot().has(providerId)) { "找不到要设为默认的服务商" }
        preferences.edit().putString(DEFAULT_PROVIDER_KEY, providerId).apply()
    }

    @Synchronized
    fun getDefault(): ProviderConfig? = list().firstOrNull { it.isDefault }

    @Synchronized
    fun credentials(providerId: String): ProviderCredentials? {
        if (!readRoot().has(providerId)) return null
        val payload = secretStore.get(secretAlias(providerId)) ?: return null
        return decodeCredentials(payload)
    }

    @Synchronized
    fun delete(providerId: String) {
        val root = readRoot()
        if (!root.has(providerId)) return
        root.remove(providerId)
        if (!preferences.edit().putString(PROVIDERS_KEY, root.toString()).commit()) {
            throw IllegalStateException("无法更新服务商列表")
        }
        secretStore.delete(secretAlias(providerId))
        if (preferences.getString(DEFAULT_PROVIDER_KEY, null) == providerId) {
            val replacement = root.keys().asSequence().firstOrNull()
            preferences.edit().putString(DEFAULT_PROVIDER_KEY, replacement).apply()
        }
    }

    private fun readRoot(): JSONObject = runCatching {
        JSONObject(preferences.getString(PROVIDERS_KEY, "{}") ?: "{}")
    }.getOrElse { JSONObject() }

    private fun validate(config: ProviderConfig) {
        require(config.displayName.isNotBlank()) { "服务商名称不能为空" }
        require(config.modelId.isNotBlank()) { "模型 ID 不能为空" }
        val uri = Uri.parse(config.baseUrl.trim())
        require(uri.scheme.equals("https", true) ||
            (uri.scheme.equals("http", true) && (uri.host == "127.0.0.1" || uri.host == "localhost"))) {
            "服务商地址必须使用 HTTPS；仅本机回环地址允许 HTTP"
        }
        require(!uri.host.isNullOrBlank()) { "服务商地址格式不正确" }
    }

    private fun normalizeBaseUrl(value: String): String = value.trim().trimEnd('/')

    private fun secretAlias(providerId: String) = "codex_provider_$providerId"

    private fun encodeCredentials(credentials: ProviderCredentials): String {
        val headers = JSONObject()
        credentials.customHeaders.forEach { (name, value) -> headers.put(name, value) }
        return JSONObject()
            .put("apiKey", credentials.apiKey)
            .put("customHeaders", headers)
            .toString()
    }

    private fun decodeCredentials(payload: String): ProviderCredentials {
        val json = JSONObject(payload)
        val headersJson = json.optJSONObject("customHeaders") ?: JSONObject()
        val headers = linkedMapOf<String, String>()
        val keys = headersJson.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            headers[key] = headersJson.optString(key)
        }
        return ProviderCredentials(
            apiKey = json.optString("apiKey"),
            customHeaders = headers
        )
    }

    companion object {
        private const val CONFIG_PREFS = "codex_provider_config_v1"
        private const val PROVIDERS_KEY = "providers"
        private const val DEFAULT_PROVIDER_KEY = "default_provider"
    }
}

/** AES-256-GCM encryption backed by a non-exportable Android Keystore key. */
internal class SecretStore(context: Context) {
    private val preferences = context.getSharedPreferences(SECRET_PREFS, Context.MODE_PRIVATE)
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    @Synchronized
    fun put(alias: String, plaintext: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(alias))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        val packed = ByteArray(cipher.iv.size + ciphertext.size)
        System.arraycopy(cipher.iv, 0, packed, 0, cipher.iv.size)
        System.arraycopy(ciphertext, 0, packed, cipher.iv.size, ciphertext.size)
        val encoded = Base64.encodeToString(packed, Base64.NO_WRAP)
        check(preferences.edit().putString(alias, encoded).commit()) { "无法保存加密凭据" }
    }

    @Synchronized
    fun get(alias: String): String? {
        val encoded = preferences.getString(alias, null) ?: return null
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        require(packed.size > GCM_IV_BYTES) { "加密凭据数据损坏" }
        val iv = packed.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = packed.copyOfRange(GCM_IV_BYTES, packed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(alias), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }

    @Synchronized
    fun delete(alias: String) {
        preferences.edit().remove(alias).commit()
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    private fun getOrCreateKey(alias: String): SecretKey {
        val existing = keyStore.getKey(alias, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val SECRET_PREFS = "codex_provider_secrets_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }
}

internal data class CodexProviderEnvironment(
    val apiKeyEnvironmentName: String?,
    val headerEnvironmentNames: Map<String, String>,
    val values: Map<String, String>
)
