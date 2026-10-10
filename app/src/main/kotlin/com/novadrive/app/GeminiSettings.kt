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

/**
 * Which realtime provider the owner stored (ADR-013). The product session always uses Qwen-Omni
 * (owner 2026-10-09); wire values gemini and baidu still parse but do not select the session.
 */
enum class VoiceProviderPreference(val wireName: String) {
    GEMINI("gemini"),
    BAIDU("baidu"),
    QWEN("qwen"),
    ;

    companion object {
        fun fromWire(raw: String?): VoiceProviderPreference =
            entries.firstOrNull { it.wireName == raw } ?: QWEN
    }
}

/** Persisted, non-secret Gemini Live settings (ADR-010). The API key lives in the Keystore only. */
data class GeminiAppSettings(
    val provider: VoiceProviderPreference = VoiceProviderPreference.QWEN,
    val consentAccepted: Boolean = false,
    val model: String = VoiceCatalog.GEMINI_LIVE_DEFAULT,
    val endpoint: String = DEFAULT_ENDPOINT,
    val voice: String = DEFAULT_VOICE,
    val thinkingLevel: GeminiThinkingLevel = GeminiThinkingLevel.LOW,
    val silenceDurationMs: Int? = null,
) {
    companion object {
        const val DEFAULT_ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        /**
         * B-034 (owner, 2026-10-02: Kore sounded old; wants a young, clear, poised voice): Leda,
         * Google's "youthful" prebuilt voice, highest-pitched of the A/B with Erinome
         * (tools/gemini-live-probe/voice_ab.py). Provisional until the owner picks by ear.
         */
        const val DEFAULT_VOICE = "Leda"

        /** The default before B-034; a stored copy of it was prefilled, never chosen. */
        const val PREVIOUS_DEFAULT_VOICE = "Kore"
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
    /** ADR-016: when set, this voice speaks Gemini's words and Gemini's own audio is discarded. */
    val assistantVoice: com.novadrive.app.voice.AzureSpeechConfig? = null,
) {
    override fun toString(): String =
        "GeminiApiConfig(settings=$settings, apiKey=<redacted>, instructions=<${instructions.length} chars>, " +
            "assistantVoice=${assistantVoice ?: "none"})"
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
        if (!settings.consentAccepted) return "GEMINI_CONSENT_MISSING"
        return null
    }

    /** Builds the Gemini session config or throws [IllegalArgumentException] with the config code. */
    fun configOrThrow(settings: GeminiAppSettings, apiKey: String, instructions: String): GeminiApiConfig {
        validate(settings, apiKey)?.let { throw IllegalArgumentException(it) }
        return GeminiApiConfig(settings, apiKey, instructions)
    }

    /**
     * The config code a start would fail with, or null. Null for an explicit BAIDU or QWEN
     * preference (Gemini is not used); otherwise the same order as [validate]. Takes presence, never the key.
     */
    fun configProblem(settings: GeminiAppSettings, keyPresent: Boolean): String? {
        if (settings.provider != VoiceProviderPreference.GEMINI) return null
        validateSettings(settings)?.let { return it }
        if (!keyPresent) return "GEMINI_API_KEY_MISSING"
        if (!settings.consentAccepted) return "GEMINI_CONSENT_MISSING"
        return null
    }

    /** The one honest on-screen sentence for a voice-provider config code; null for non-Gemini codes. */
    fun screenMessage(code: String): String? = when {
        code == "GEMINI_API_KEY_MISSING" -> "语音服务未配置：请在开发者设置里填写 Gemini 密钥。"
        code == "GEMINI_CONSENT_MISSING" -> "请先在开发者设置里同意语音数据跨境传输提示。"
        code.startsWith("GEMINI_") -> "语音服务设置有误，请检查开发者设置。"
        else -> null
    }
}

/**
 * Decides which realtime provider the product session uses. Called once per session at the
 * composition boundary (ADR-010); never re-evaluated mid-session. The product session is
 * Qwen-Omni (Maia); stored gemini/baidu preferences do not change [resolve]. Missing Qwen
 * key, workspace or consent fails the start with its code and never opens Gemini or Baidu.
 */
object VoiceProviderChoice {
    @Suppress("UNUSED_PARAMETER")
    fun resolve(settings: GeminiAppSettings, keyPresent: Boolean): VoiceProviderId = VoiceProviderId.QWEN

    /**
     * Config problem for a start, following [choice] only. QWEN runs [qwen]; GEMINI_LIVE runs
     * [gemini] then [azure] if Gemini reported no problem; other choices return null.
     */
    fun startProblem(
        choice: VoiceProviderId,
        qwen: () -> String?,
        gemini: () -> String?,
        azure: () -> String?,
    ): String? = when (choice) {
        VoiceProviderId.QWEN -> qwen()
        VoiceProviderId.GEMINI_LIVE -> gemini() ?: azure()
        else -> null
    }
}

/**
 * An unset model loads as the default; a saved model is kept as is, except for the one-time
 * switch below.
 */
internal fun storedGeminiModelOrDefault(stored: String?): String =
    stored.orEmpty().ifBlank { VoiceCatalog.GEMINI_LIVE_DEFAULT }

/**
 * One-time switch (owner, 2026-09-30: "switch the live one"): a saved extended-thinking model
 * becomes the default once. After that, whatever the owner picks is kept, extended included.
 */
internal fun geminiModelAfterOneTimeSwitch(stored: String?, alreadySwitched: Boolean): String? =
    if (!alreadySwitched && stored == VoiceCatalog.GEMINI_LIVE_EXTENDED) VoiceCatalog.GEMINI_LIVE_DEFAULT else stored

/**
 * One-time switch (B-034): the settings screen saved the prefilled voice, so a stored "Kore" is the
 * old default, not a choice. It becomes the new default once; after that any saved voice is kept,
 * Kore included.
 */
internal fun geminiVoiceAfterOneTimeSwitch(stored: String?, alreadySwitched: Boolean): String? =
    if (!alreadySwitched && stored?.trim() == GeminiAppSettings.PREVIOUS_DEFAULT_VOICE) GeminiAppSettings.DEFAULT_VOICE else stored

class GeminiSettingsRepository(
    context: Context,
    private val credentials: CredentialStore = AndroidKeystoreCredentialStore(context.applicationContext),
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadSettings(): GeminiAppSettings {
        switchModelOnce()
        switchVoiceOnce()
        return loadStored()
    }

    private fun switchVoiceOnce() {
        if (prefs.getBoolean(KEY_VOICE_SWITCHED_B034, false)) return
        val stored = prefs.getString(KEY_VOICE, null)
        val switched = geminiVoiceAfterOneTimeSwitch(stored, alreadySwitched = false)
        val editor = prefs.edit().putBoolean(KEY_VOICE_SWITCHED_B034, true)
        if (switched != stored && switched != null) editor.putString(KEY_VOICE, switched)
        editor.apply()
    }

    private fun switchModelOnce() {
        if (prefs.getBoolean(KEY_MODEL_SWITCHED_011, false)) return
        val stored = prefs.getString(KEY_MODEL, null)
        val switched = geminiModelAfterOneTimeSwitch(stored, alreadySwitched = false)
        val editor = prefs.edit().putBoolean(KEY_MODEL_SWITCHED_011, true)
        if (switched != stored && switched != null) editor.putString(KEY_MODEL, switched)
        editor.apply()
    }

    private fun loadStored(): GeminiAppSettings =
        GeminiAppSettings(
            provider = VoiceProviderPreference.fromWire(prefs.getString(KEY_PROVIDER, null)),
            consentAccepted = prefs.getBoolean(KEY_CONSENT, false),
            model = storedGeminiModelOrDefault(prefs.getString(KEY_MODEL, null)),
            endpoint = prefs.getString(KEY_ENDPOINT, null).orEmpty().ifBlank { GeminiAppSettings.DEFAULT_ENDPOINT },
            voice = prefs.getString(KEY_VOICE, null).orEmpty().ifBlank { GeminiAppSettings.DEFAULT_VOICE },
            thinkingLevel = GeminiThinkingLevel.fromWire(prefs.getString(KEY_THINKING, null)),
            silenceDurationMs = if (prefs.contains(KEY_SILENCE)) prefs.getInt(KEY_SILENCE, 0) else null,
        )

    fun save(settings: GeminiAppSettings, key: CredentialUpdate = CredentialUpdate.Keep) {
        GeminiSettingsValidator.validateSettings(settings)?.let { throw IllegalArgumentException(it) }
        credentials.applyCredentialUpdate(CRED_API_KEY, key)
        val editor = prefs.edit()
            .putString(KEY_PROVIDER, settings.provider.wireName)
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
        return GeminiSettingsValidator.configOrThrow(settings, key, instructions)
    }

    /** See [GeminiSettingsValidator.configProblem]. */
    fun configProblem(): String? = GeminiSettingsValidator.configProblem(loadSettings(), keyPresent())

    fun choice(): VoiceProviderId = VoiceProviderChoice.resolve(loadSettings(), keyPresent())

    companion object {
        const val CRED_API_KEY = "gemini_api_key"
        private const val PREFS = "nova_gemini_settings"
        /** ADR-013; replaces the old opt-in "enabled" key, which is now ignored. */
        private const val KEY_PROVIDER = "provider_preference"
        private const val KEY_CONSENT = "consent_accepted"
        private const val KEY_MODEL = "model"
        private const val KEY_MODEL_SWITCHED_011 = "model_switched_adr011"
        private const val KEY_ENDPOINT = "endpoint"
        private const val KEY_VOICE = "voice"
        private const val KEY_VOICE_SWITCHED_B034 = "voice_switched_b034"
        private const val KEY_THINKING = "thinking_level"
        private const val KEY_SILENCE = "silence_duration_ms"
    }
}
