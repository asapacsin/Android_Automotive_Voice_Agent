package com.novadrive.app

import android.content.Context
import com.novadrive.ingress.realtime.VoiceCatalog

enum class BaiduAuthMode(val wireName: String) {
    LEGACY_ACCESS_TOKEN("legacy_access_token"),
    BEARER_API_KEY("bearer_api_key"),
    ;

    companion object {
        fun fromWire(raw: String?): BaiduAuthMode =
            entries.firstOrNull { it.wireName == raw } ?: LEGACY_ACCESS_TOKEN
    }
}

enum class BaiduRuntimeProvider(val wireName: String) {
    FLEX("flex"),
    LITE("lite"),
    ;

    companion object {
        fun fromWire(raw: String?): BaiduRuntimeProvider =
            entries.firstOrNull { it.wireName == raw } ?: FLEX
    }
}

enum class OutputSampleRate(val wireName: String, val hz: Int?) {
    AUTO("auto", null),
    HZ_16000("16000", 16_000),
    HZ_24000("24000", 24_000),
    ;

    companion object {
        fun fromWire(raw: String?): OutputSampleRate =
            entries.firstOrNull { it.wireName == raw } ?: AUTO
    }
}

data class BaiduAppSettings(
    val authMode: BaiduAuthMode = BaiduAuthMode.LEGACY_ACCESS_TOKEN,
    val runtimeProvider: BaiduRuntimeProvider = BaiduRuntimeProvider.FLEX,
    val model: String = VoiceCatalog.BAIDU_FLEX,
    val endpoint: String = DEFAULT_ENDPOINT,
    val tokenEndpoint: String = DEFAULT_TOKEN_ENDPOINT,
    val outputSampleRate: OutputSampleRate = OutputSampleRate.AUTO,
    val instructions: String = PersonaProfiles.DEFAULT_INSTRUCTIONS,
    val voice: String = DEFAULT_VOICE,
    val speed: Double = DEFAULT_SPEED,
) {
    companion object {
        const val DEFAULT_ENDPOINT = "wss://aip.baidubce.com/ws/2.0/speech/v1/realtime"
        const val DEFAULT_TOKEN_ENDPOINT = "https://aip.baidubce.com/oauth/2.0/token"
        const val DEFAULT_VOICE = "default"
        const val DEFAULT_SPEED = 1.1
    }
}

fun BaiduAppSettings.resolvedOutputSampleRateHz(): Int =
    outputSampleRate.hz ?: when (runtimeProvider) {
        BaiduRuntimeProvider.FLEX -> 24_000
        BaiduRuntimeProvider.LITE -> 16_000
    }

data class BaiduCredentials(
    val appId: String,
    val apiKey: String,
    val secretKey: String,
)

data class BaiduCredentialUpdates(
    val appId: CredentialUpdate = CredentialUpdate.Keep,
    val apiKey: CredentialUpdate = CredentialUpdate.Keep,
    val secretKey: CredentialUpdate = CredentialUpdate.Keep,
)

data class BaiduApiConfig(
    val settings: BaiduAppSettings,
    val credentials: BaiduCredentials,
)

object BaiduSettingsValidator {
    private val VOICE_CHARS = ('0'..'9') + ('A'..'Z') + ('a'..'z') + setOf('_', '-')

    fun validate(settings: BaiduAppSettings, credentials: BaiduCredentials): String? {
        validateSettings(settings)?.let { return it }
        if (credentials.apiKey.isBlank()) return "BAIDU_API_KEY_MISSING"
        if (settings.authMode == BaiduAuthMode.LEGACY_ACCESS_TOKEN) {
            if (credentials.appId.isBlank()) return "BAIDU_APP_ID_MISSING"
            if (credentials.secretKey.isBlank()) return "BAIDU_SECRET_KEY_MISSING"
        }
        return null
    }

    fun validateSettings(settings: BaiduAppSettings): String? {
        val allowed = when (settings.runtimeProvider) {
            BaiduRuntimeProvider.FLEX -> VoiceCatalog.baiduFlexModels
            BaiduRuntimeProvider.LITE -> VoiceCatalog.baiduModels
        }
        if (settings.model !in allowed) return "BAIDU_MODEL_INVALID"
        if (settings.endpoint.isBlank()) return "BAIDU_ENDPOINT_MISSING"
        if (!settings.endpoint.trim().startsWith("wss://", ignoreCase = true)) return "BAIDU_ENDPOINT_INVALID"
        if (settings.authMode == BaiduAuthMode.LEGACY_ACCESS_TOKEN) {
            if (!settings.tokenEndpoint.trim().startsWith("https://", ignoreCase = true)) return "BAIDU_TOKEN_ENDPOINT_INVALID"
        }
        if (settings.instructions.length > PersonaProfiles.MAX_INSTRUCTIONS_CHARS) return "BAIDU_INSTRUCTIONS_TOO_LONG"
        if (settings.voice.length !in 1..32 || !settings.voice.all { it in VOICE_CHARS }) return "BAIDU_VOICE_INVALID"
        if (settings.speed !in 0.5..1.5) return "BAIDU_SPEED_INVALID"
        return null
    }
}

class BaiduSettingsRepository(
    context: Context,
    private val credentials: CredentialStore = AndroidKeystoreCredentialStore(context.applicationContext),
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        migrateIfNeeded(context.applicationContext)
    }

    fun loadSettings(): BaiduAppSettings =
        BaiduAppSettings(
            authMode = BaiduAuthMode.fromWire(prefs.getString(KEY_AUTH_MODE, null)),
            runtimeProvider = BaiduRuntimeProvider.fromWire(prefs.getString(KEY_RUNTIME_PROVIDER, null)),
            model = prefs.getString(KEY_MODEL, VoiceCatalog.BAIDU_FLEX).orEmpty().ifBlank { VoiceCatalog.BAIDU_FLEX },
            endpoint = prefs.getString(KEY_ENDPOINT, BaiduAppSettings.DEFAULT_ENDPOINT).orEmpty().ifBlank { BaiduAppSettings.DEFAULT_ENDPOINT },
            tokenEndpoint = prefs.getString(KEY_TOKEN_ENDPOINT, BaiduAppSettings.DEFAULT_TOKEN_ENDPOINT).orEmpty()
                .ifBlank { BaiduAppSettings.DEFAULT_TOKEN_ENDPOINT },
            outputSampleRate = OutputSampleRate.fromWire(prefs.getString(KEY_OUTPUT_SAMPLE_RATE, null)),
            instructions = PersonaProfiles.sanitize(prefs.getString(KEY_INSTRUCTIONS, null)),
            voice = prefs.getString(KEY_VOICE, BaiduAppSettings.DEFAULT_VOICE).orEmpty().ifBlank { BaiduAppSettings.DEFAULT_VOICE },
            speed = prefs.getString(KEY_SPEED, null)?.toDoubleOrNull() ?: BaiduAppSettings.DEFAULT_SPEED,
        )

    fun loadCredentials(): BaiduCredentials =
        BaiduCredentials(
            appId = credentials.read(CRED_APP_ID).orEmpty(),
            apiKey = credentials.read(CRED_API_KEY).orEmpty(),
            secretKey = credentials.read(CRED_SECRET_KEY).orEmpty(),
        )

    fun configuredFields(): Set<String> = buildSet {
        if (credentials.read(CRED_APP_ID).isNullOrBlank().not()) add("app_id")
        if (credentials.read(CRED_API_KEY).isNullOrBlank().not()) add("api_key")
        if (credentials.read(CRED_SECRET_KEY).isNullOrBlank().not()) add("secret_key")
    }

    fun config(settings: BaiduAppSettings = loadSettings(), overrides: BaiduCredentials? = null): BaiduApiConfig {
        val current = overrides ?: loadCredentials()
        BaiduSettingsValidator.validate(settings, current)?.let { throw IllegalArgumentException(it) }
        return BaiduApiConfig(settings, current)
    }

    fun save(settings: BaiduAppSettings, updates: BaiduCredentialUpdates) {
        BaiduSettingsValidator.validateSettings(settings)?.let { throw IllegalArgumentException(it) }
        apply(CRED_APP_ID, updates.appId)
        apply(CRED_API_KEY, updates.apiKey)
        apply(CRED_SECRET_KEY, updates.secretKey)
        prefs.edit()
            .putString(KEY_AUTH_MODE, settings.authMode.wireName)
            .putString(KEY_RUNTIME_PROVIDER, settings.runtimeProvider.wireName)
            .putString(KEY_MODEL, settings.model.trim())
            .putString(KEY_ENDPOINT, settings.endpoint.trim())
            .putString(KEY_TOKEN_ENDPOINT, settings.tokenEndpoint.trim())
            .putString(KEY_OUTPUT_SAMPLE_RATE, settings.outputSampleRate.wireName)
            .putString(KEY_INSTRUCTIONS, settings.instructions.trim())
            .putString(KEY_VOICE, settings.voice.trim())
            .putString(KEY_SPEED, settings.speed.toString())
            .putInt(KEY_SCHEMA, SCHEMA_VERSION)
            .apply()
    }

    fun clearAllCredentials() {
        credentials.clear(CRED_APP_ID)
        credentials.clear(CRED_API_KEY)
        credentials.clear(CRED_SECRET_KEY)
    }

    private fun apply(name: String, update: CredentialUpdate) {
        credentials.applyCredentialUpdate(name, update)
    }

    private fun migrateIfNeeded(context: Context) {
        if (prefs.getInt(KEY_SCHEMA, 0) >= SCHEMA_VERSION) return
        val previousSchema = prefs.getInt(KEY_SCHEMA, 0)
        val oldOwnModel = prefs.getString(KEY_MODEL, null)
        val legacy = context.getSharedPreferences("nova_developer", Context.MODE_PRIVATE)
        val oldModel = oldOwnModel ?: legacy.getString("model", null)
        val preserveLite = previousSchema == 1 || oldModel in VoiceCatalog.baiduModels
        val runtimeProvider = if (preserveLite) BaiduRuntimeProvider.LITE else BaiduRuntimeProvider.FLEX
        val model = if (runtimeProvider == BaiduRuntimeProvider.LITE) {
            oldModel?.takeIf { it in VoiceCatalog.baiduModels } ?: VoiceCatalog.BAIDU_LITE_NEAR
        } else VoiceCatalog.BAIDU_FLEX
        prefs.edit()
            .putString(KEY_AUTH_MODE, BaiduAuthMode.LEGACY_ACCESS_TOKEN.wireName)
            .putString(KEY_RUNTIME_PROVIDER, runtimeProvider.wireName)
            .putString(KEY_MODEL, model)
            .putString(KEY_ENDPOINT, BaiduAppSettings.DEFAULT_ENDPOINT)
            .putString(KEY_TOKEN_ENDPOINT, BaiduAppSettings.DEFAULT_TOKEN_ENDPOINT)
            .putInt(KEY_SCHEMA, SCHEMA_VERSION)
            .apply()
    }

    companion object {
        private const val PREFS = "nova_baidu_settings"
        private const val SCHEMA_VERSION = 2
        private const val KEY_SCHEMA = "schema_version"
        private const val KEY_AUTH_MODE = "auth_mode"
        private const val KEY_RUNTIME_PROVIDER = "runtime_provider"
        private const val KEY_MODEL = "model"
        private const val KEY_ENDPOINT = "endpoint"
        private const val KEY_TOKEN_ENDPOINT = "token_endpoint"
        private const val KEY_OUTPUT_SAMPLE_RATE = "output_sample_rate"
        private const val KEY_INSTRUCTIONS = "instructions"
        private const val KEY_VOICE = "voice"
        private const val KEY_SPEED = "speed"
        private const val CRED_APP_ID = "baidu_app_id"
        private const val CRED_API_KEY = "baidu_api_key"
        private const val CRED_SECRET_KEY = "baidu_secret_key"
    }
}

internal fun CredentialStore.applyCredentialUpdate(name: String, update: CredentialUpdate) {
    when (update) {
        CredentialUpdate.Keep -> Unit
        CredentialUpdate.Clear -> clear(name)
        is CredentialUpdate.Replace -> {
            val value = update.value.trim()
            require(value.isNotEmpty()) { "BAIDU_CREDENTIAL_VALUE_MISSING" }
            write(name, value)
        }
    }
}
