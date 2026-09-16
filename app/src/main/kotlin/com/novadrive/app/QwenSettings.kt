package com.novadrive.app

import android.content.Context
import com.novadrive.ingress.realtime.VoiceCatalog
import com.novadrive.ingress.realtime.VoiceProviderId

enum class ConnectionMode(val wireName: String) {
    BAIDU_DIRECT("baidu_direct"),
    QWEN_DIRECT("qwen_direct"),
    BACKEND_PROXY("backend_proxy"),
    ;

    companion object {
        fun fromWire(raw: String?): ConnectionMode =
            entries.firstOrNull { it.wireName == raw } ?: BAIDU_DIRECT
    }
}

data class VoiceAppSettings(
    val connectionMode: ConnectionMode = ConnectionMode.BAIDU_DIRECT,
    val qwenModel: String = VoiceCatalog.QWEN_FLASH,
    val qwenEndpoint: String = DEFAULT_QWEN_ENDPOINT,
    val backendUrl: String = "http://10.0.2.2:8000",
    val backendProvider: VoiceProviderId = VoiceProviderId.FAKE,
    val backendModel: String = VoiceCatalog.FAKE_MODEL,
) {
    companion object {
        const val DEFAULT_QWEN_ENDPOINT = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime"
    }
}

data class QwenApiConfig(
    val apiKey: String,
    val model: String,
    val endpoint: String,
)

sealed interface ApiKeyUpdate {
    data object Keep : ApiKeyUpdate
    data object Clear : ApiKeyUpdate
    data class Replace(val value: String) : ApiKeyUpdate
}

interface ApiKeyStore {
    fun read(): String?
    fun replace(value: String)
    fun clear()
}

class ApiKeyManager(private val store: ApiKeyStore) {
    fun read(): String? = store.read()?.takeIf { it.isNotBlank() }

    fun apply(update: ApiKeyUpdate) {
        when (update) {
            ApiKeyUpdate.Keep -> Unit
            ApiKeyUpdate.Clear -> store.clear()
            is ApiKeyUpdate.Replace -> {
                val value = update.value.trim()
                require(value.isNotEmpty()) { "QWEN_API_KEY_MISSING" }
                store.replace(value)
            }
        }
    }
}

object QwenSettingsValidator {
    fun validate(apiKey: String?, model: String, endpoint: String): String? {
        if (apiKey.isNullOrBlank()) return "QWEN_API_KEY_MISSING"
        if (model.isBlank()) return "QWEN_MODEL_MISSING"
        if (endpoint.isBlank()) return "QWEN_ENDPOINT_MISSING"
        val normalized = endpoint.trim().lowercase()
        if (!normalized.startsWith("ws://") && !normalized.startsWith("wss://")) {
            return "QWEN_ENDPOINT_INVALID"
        }
        if (!normalized.startsWith("wss://")) return "QWEN_TLS_REQUIRED"
        return null
    }
}

class DeveloperSettingsRepository(
    context: Context,
    secretStore: ApiKeyStore = AndroidKeystoreApiKeyStore(context.applicationContext),
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val keys = ApiKeyManager(secretStore)

    init {
        migrateIfNeeded()
    }

    fun load(): VoiceAppSettings {
        val provider =
            runCatching { VoiceProviderId.fromWire(prefs.getString(KEY_BACKEND_PROVIDER, null).orEmpty()) }
                .getOrDefault(VoiceProviderId.FAKE)
        val backendModel =
            prefs.getString(KEY_BACKEND_MODEL, null)?.trim()?.takeIf { it.isNotEmpty() }
                ?: VoiceCatalog.defaultModel(provider)
        return VoiceAppSettings(
            connectionMode = ConnectionMode.fromWire(prefs.getString(KEY_CONNECTION_MODE, null)),
            qwenModel = prefs.getString(KEY_QWEN_MODEL, VoiceCatalog.QWEN_FLASH).orEmpty().ifBlank { VoiceCatalog.QWEN_FLASH },
            qwenEndpoint = prefs.getString(KEY_QWEN_ENDPOINT, VoiceAppSettings.DEFAULT_QWEN_ENDPOINT).orEmpty()
                .ifBlank { VoiceAppSettings.DEFAULT_QWEN_ENDPOINT },
            backendUrl = prefs.getString(KEY_BACKEND_URL, appContext.getString(R.string.nova_backend_url)).orEmpty()
                .ifBlank { appContext.getString(R.string.nova_backend_url) },
            backendProvider = provider,
            backendModel = backendModel,
        )
    }

    fun hasApiKey(): Boolean = keys.read() != null

    fun readApiKey(): String? = keys.read()

    fun qwenConfig(settings: VoiceAppSettings = load(), keyOverride: String? = null): QwenApiConfig {
        val key = keyOverride?.trim()?.takeIf { it.isNotEmpty() } ?: keys.read()
        val error = QwenSettingsValidator.validate(key, settings.qwenModel, settings.qwenEndpoint)
        if (error != null) throw IllegalArgumentException(error)
        return QwenApiConfig(key.orEmpty(), settings.qwenModel.trim(), settings.qwenEndpoint.trim())
    }

    fun save(settings: VoiceAppSettings, keyUpdate: ApiKeyUpdate = ApiKeyUpdate.Keep) {
        if (settings.connectionMode == ConnectionMode.QWEN_DIRECT) {
            val effectiveKey =
                when (keyUpdate) {
                    ApiKeyUpdate.Clear -> null
                    ApiKeyUpdate.Keep -> keys.read()
                    is ApiKeyUpdate.Replace -> keyUpdate.value
                }
            QwenSettingsValidator.validate(effectiveKey, settings.qwenModel, settings.qwenEndpoint)?.let {
                throw IllegalArgumentException(it)
            }
        }
        keys.apply(keyUpdate)
        prefs.edit()
            .putString(KEY_CONNECTION_MODE, settings.connectionMode.wireName)
            .putString(KEY_QWEN_MODEL, settings.qwenModel.trim())
            .putString(KEY_QWEN_ENDPOINT, settings.qwenEndpoint.trim())
            .putString(KEY_BACKEND_URL, settings.backendUrl.trim())
            .putString(KEY_BACKEND_PROVIDER, settings.backendProvider.wireName)
            .putString(KEY_BACKEND_MODEL, settings.backendModel.trim())
            .putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
            .apply()
    }

    private fun migrateIfNeeded() {
        if (prefs.getInt(KEY_SCHEMA_VERSION, 0) >= SCHEMA_VERSION) return
        val oldProvider = prefs.getString(LEGACY_PROVIDER, null)?.trim()?.lowercase()?.replace('-', '_')
        val oldModel = prefs.getString(LEGACY_MODEL, null)?.trim()
        val backendProvider =
            runCatching { VoiceProviderId.fromWire(oldProvider.orEmpty()) }.getOrDefault(VoiceProviderId.FAKE)
        val backendModel =
            oldModel?.takeIf { model ->
                runCatching { VoiceCatalog.providerForModel(model) }.getOrNull() == backendProvider
            } ?: VoiceCatalog.defaultModel(backendProvider)
        prefs.edit()
            .putString(KEY_CONNECTION_MODE, ConnectionMode.QWEN_DIRECT.wireName)
            .putString(KEY_QWEN_MODEL, VoiceCatalog.QWEN_FLASH)
            .putString(KEY_QWEN_ENDPOINT, VoiceAppSettings.DEFAULT_QWEN_ENDPOINT)
            .putString(KEY_BACKEND_PROVIDER, backendProvider.wireName)
            .putString(KEY_BACKEND_MODEL, backendModel)
            .putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
            .apply()
    }

    companion object {
        private const val PREFS = "nova_developer"
        private const val SCHEMA_VERSION = 1
        private const val KEY_SCHEMA_VERSION = "direct_qwen_schema"
        private const val KEY_CONNECTION_MODE = "connection_mode"
        private const val KEY_QWEN_MODEL = "qwen_model"
        private const val KEY_QWEN_ENDPOINT = "qwen_endpoint"
        private const val KEY_BACKEND_URL = "backend_url"
        private const val KEY_BACKEND_PROVIDER = "backend_provider"
        private const val KEY_BACKEND_MODEL = "backend_model"
        private const val LEGACY_PROVIDER = "provider"
        private const val LEGACY_MODEL = "model"
    }
}
