package com.novadrive.app

import android.content.Context
import com.novadrive.ingress.realtime.VoiceCatalog
import com.novadrive.ingress.realtime.VoiceProviderId

/** Gemini Live thinking level. MINIMAL is rejected by the API (measured); LOW is the default. */
enum class GeminiThinkingLevel(val wireName: String) {
    LOW("LOW"),
    MEDIUM("MEDIUM"),
    HIGH("HIGH"),
    ;

    companion object {
        fun fromWire(raw: String?): GeminiThinkingLevel =
            entries.firstOrNull { it.wireName == raw } ?: LOW
    }
}

/** Persisted, non-secret Gemini Live settings (ADR-010). The API key lives in the Keystore only. */
data class GeminiAppSettings(
    val enabled: Boolean = false,
    val consentAccepted: Boolean = false,
    val model: String = VoiceCatalog.GEMINI_LIVE,
    val endpoint: String = DEFAULT_ENDPOINT,
    val voice: String = DEFAULT_VOICE,
    val thinkingLevel: GeminiThinkingLevel = GeminiThinkingLevel.LOW,
    val silenceDurationMs: Int? = null,
) {
    companion object {
        const val DEFAULT_ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        const val DEFAULT_VOICE = "Kore"
        const val MIN_SILENCE_MS = 100
        const val MAX_SILENCE_MS = 3000
        const val CONSENT_NOTICE =
            "启用 Gemini 后，驾驶员的语音、转写文本和屏幕上下文将发送到位于中国大陆以外的 Google 服务器；" +
                "在中国大陆网络下可能无法连接。\n" +
                "Enabling Gemini sends the driver's voice, transcripts and on-screen context to Google " +
                "servers outside mainland China; it may not be reachable from mainland networks."
    }
}

data class GeminiApiConfig(
    val settings: GeminiAppSettings,
    val apiKey: String,
    val instructions: String,
) {
    override fun toString(): String =
        "GeminiApiConfig(settings=$settings, apiKey=<redacted>, instructions=<${instructions.length} chars>)"
}

object GeminiSettingsValidator {
    private val VOICE_CHARS = ('0'..'9') + ('A'..'Z') + ('a'..'z') + setOf('_', '-')

    fun validateSettings(settings: GeminiAppSettings): String? {
        if (settings.model !in VoiceCatalog.geminiLiveModels) return "GEMINI_MODEL_INVALID"
        if (!settings.endpoint.trim().startsWith("wss://", ignoreCase = true)) return "GEMINI_ENDPOINT_INVALID"
        if (settings.voice.length !in 1..32 || !settings.voice.all { it in VOICE_CHARS }) return "GEMINI_VOICE_INVALID"
        val silence = settings.silenceDurationMs
        if (silence != null && silence !in GeminiAppSettings.MIN_SILENCE_MS..GeminiAppSettings.MAX_SILENCE_MS) {
            return "GEMINI_SILENCE_INVALID"
        }
        return null
    }

    fun validate(settings: GeminiAppSettings, apiKey: String): String? {
        validateSettings(settings)?.let { return it }
        if (apiKey.isBlank()) return "GEMINI_API_KEY_MISSING"
        if (settings.enabled && !settings.consentAccepted) return "GEMINI_CONSENT_MISSING"
        return null
    }
}

/**
 * Decides which realtime provider a session uses. Called once per session at the composition
 * boundary (ADR-010); never re-evaluated mid-session. Gemini is opt-in: anything short of
 * enabled + consented + key present + valid settings keeps the Baidu Flex default.
 */
object VoiceProviderChoice {
    fun resolve(settings: GeminiAppSettings, keyPresent: Boolean): VoiceProviderId =
        if (settings.enabled && settings.consentAccepted && keyPresent &&
            GeminiSettingsValidator.validateSettings(settings) == null
        ) VoiceProviderId.GEMINI_LIVE else VoiceProviderId.BAIDU_FLEX
}

class GeminiSettingsRepository(
    context: Context,
    private val credentials: CredentialStore = AndroidKeystoreCredentialStore(context.applicationContext),
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadSettings(): GeminiAppSettings =
        GeminiAppSettings(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            consentAccepted = prefs.getBoolean(KEY_CONSENT, false),
            model = prefs.getString(KEY_MODEL, null).orEmpty().ifBlank { VoiceCatalog.GEMINI_LIVE },
            endpoint = prefs.getString(KEY_ENDPOINT, null).orEmpty().ifBlank { GeminiAppSettings.DEFAULT_ENDPOINT },
            voice = prefs.getString(KEY_VOICE, null).orEmpty().ifBlank { GeminiAppSettings.DEFAULT_VOICE },
            thinkingLevel = GeminiThinkingLevel.fromWire(prefs.getString(KEY_THINKING, null)),
            silenceDurationMs = if (prefs.contains(KEY_SILENCE)) prefs.getInt(KEY_SILENCE, 0) else null,
        )

    fun save(settings: GeminiAppSettings, key: CredentialUpdate = CredentialUpdate.Keep) {
        GeminiSettingsValidator.validateSettings(settings)?.let { throw IllegalArgumentException(it) }
        credentials.applyCredentialUpdate(CRED_API_KEY, key)
        val editor = prefs.edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putBoolean(KEY_CONSENT, settings.consentAccepted)
            .putString(KEY_MODEL, settings.model.trim())
            .putString(KEY_ENDPOINT, settings.endpoint.trim())
            .putString(KEY_VOICE, settings.voice.trim())
            .putString(KEY_THINKING, settings.thinkingLevel.wireName)
        val silence = settings.silenceDurationMs
        if (silence == null) editor.remove(KEY_SILENCE) else editor.putInt(KEY_SILENCE, silence)
        editor.apply()
    }

    fun keyPresent(): Boolean = !credentials.read(CRED_API_KEY).isNullOrBlank()

    fun clearKey() {
        credentials.clear(CRED_API_KEY)
    }

    fun config(instructions: String): GeminiApiConfig {
        val settings = loadSettings()
        val key = credentials.read(CRED_API_KEY).orEmpty()
        GeminiSettingsValidator.validate(settings, key)?.let { throw IllegalArgumentException(it) }
        return GeminiApiConfig(settings, key, instructions)
    }

    fun choice(): VoiceProviderId = VoiceProviderChoice.resolve(loadSettings(), keyPresent())

    companion object {
        const val CRED_API_KEY = "gemini_api_key"
        private const val PREFS = "nova_gemini_settings"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_CONSENT = "consent_accepted"
        private const val KEY_MODEL = "model"
        private const val KEY_ENDPOINT = "endpoint"
        private const val KEY_VOICE = "voice"
        private const val KEY_THINKING = "thinking_level"
        private const val KEY_SILENCE = "silence_duration_ms"
    }
}
