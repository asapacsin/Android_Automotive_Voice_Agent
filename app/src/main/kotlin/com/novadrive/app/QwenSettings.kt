package com.novadrive.app

import android.content.Context
import com.novadrive.ingress.realtime.VoiceCatalog

/**
 * Persisted, non-secret Qwen-Omni realtime settings (SPEC-021). The API key lives in the Keystore
 * only. The workspace id is not secret but is never logged. Selected only by an explicit QWEN
 * preference (SPEC-021 step 3).
 */
data class QwenAppSettings(
    val consentAccepted: Boolean = false,
    val model: String = VoiceCatalog.QWEN_OMNI_FLASH,
    val workspaceId: String = "",
    /** Model wire name -> cloned voice id; a cloned voice works only with the model it was made for. */
    val customVoices: Map<String, String> = DEFAULT_CUSTOM_VOICES,
    val vadType: String = DEFAULT_VAD,
    val silenceDurationMs: Int = DEFAULT_SILENCE_MS,
) {
    override fun toString(): String =
        "QwenAppSettings(consentAccepted=$consentAccepted, model=$model, workspaceId=<redacted>, " +
            "customVoices=$customVoices, vadType=$vadType, silenceDurationMs=$silenceDurationMs)"

    /** The voice this session speaks in: the custom voice bound to [model], else the stock [DEFAULT_VOICE]. */
    fun sessionVoice(): String = customVoices[model]?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_VOICE

    companion object {
        const val REGION_HOST_SUFFIX = "ap-southeast-1.maas.aliyuncs.com"
        /** The stock voice, for a model without a cloned voice (and when the owner types it in). */
        const val DEFAULT_VOICE = "Maia"

        /**
         * The product voice, Ayano (owner, 2026-10-12): his own synthetic AI voice, cloned once per
         * model with tools/qwen-omni-voice/clone_voice.py (ADR-017 §6). Voice names, not secrets;
         * they work only on the owner's DashScope account, the one the app is configured with.
         */
        val DEFAULT_CUSTOM_VOICES: Map<String, String> = mapOf(
            VoiceCatalog.QWEN_OMNI_FLASH to "qwen-omni-vc-ayano-voice-20261012104331910-313d",
            VoiceCatalog.QWEN_OMNI_PLUS_35 to "qwen-omni-vc-ayano-voice-20261012104327598-7805",
        )
        const val DEFAULT_CUSTOM_VOICE_NAME = "Ayano"
        const val VAD_SEMANTIC = "semantic_vad"
        const val VAD_SERVER = "server_vad"
        const val DEFAULT_VAD = VAD_SEMANTIC
        val VAD_TYPES: Set<String> = setOf(VAD_SEMANTIC, VAD_SERVER)
        const val DEFAULT_SILENCE_MS = 800
        const val MIN_SILENCE_MS = 200
        const val MAX_SILENCE_MS = 6000
        const val CONSENT_NOTICE =
            "启用通义千问 Omni 后，驾驶员的语音、转写文本和屏幕上下文将发送到位于新加坡（中国大陆以外）的阿里云服务器。\n" +
                "Enabling Qwen-Omni sends the driver's voice, transcripts and on-screen context to Alibaba " +
                "Cloud servers in Singapore, outside mainland China."
    }
}

data class QwenApiConfig(
    val settings: QwenAppSettings,
    val apiKey: String,
    val instructions: String,
) {
    override fun toString(): String =
        "QwenApiConfig(settings=$settings, apiKey=<redacted>, instructions=<${instructions.length} chars>)"
}

object QwenSettings {
    fun endpointUrl(settings: QwenAppSettings): String =
        "wss://${settings.workspaceId.trim()}.${QwenAppSettings.REGION_HOST_SUFFIX}/api-ws/v1/realtime?model=${settings.model.trim()}"
}

object QwenSettingsValidator {
    private val VOICE_CHARS = ('0'..'9') + ('A'..'Z') + ('a'..'z') + setOf('_', '-')
    /** The workspace id is one DNS label of the endpoint host. */
    const val MAX_WORKSPACE_LENGTH = 63
    private val WORKSPACE_CHARS = ('0'..'9') + ('A'..'Z') + ('a'..'z') + setOf('-')

    const val MAX_VOICE_LENGTH = 80

    private fun voiceValid(voice: String) = voice.length in 1..MAX_VOICE_LENGTH && voice.all { it in VOICE_CHARS }

    fun validateSettings(settings: QwenAppSettings): String? {
        if (settings.model !in VoiceCatalog.qwenModels) return "QWEN_MODEL_INVALID"
        val workspace = settings.workspaceId.trim()
        if (workspace.isEmpty()) return "QWEN_WORKSPACE_MISSING"
        if (workspace.length > MAX_WORKSPACE_LENGTH || !workspace.all { it in WORKSPACE_CHARS }) return "QWEN_WORKSPACE_INVALID"
        for ((model, voice) in settings.customVoices) {
            if (voice.isBlank()) continue
            if (model !in VoiceCatalog.qwenModels || !voiceValid(voice.trim())) return "QWEN_VOICE_INVALID"
        }
        if (!voiceValid(settings.sessionVoice())) return "QWEN_VOICE_INVALID"
        if (settings.silenceDurationMs !in QwenAppSettings.MIN_SILENCE_MS..QwenAppSettings.MAX_SILENCE_MS) {
            return "QWEN_SILENCE_INVALID"
        }
        if (settings.vadType !in QwenAppSettings.VAD_TYPES) return "QWEN_VAD_INVALID"
        return null
    }

    fun validate(settings: QwenAppSettings, apiKey: String): String? {
        validateSettings(settings)?.let { return it }
        if (apiKey.isBlank()) return "QWEN_API_KEY_MISSING"
        if (!settings.consentAccepted) return "QWEN_CONSENT_MISSING"
        return null
    }

    /** Builds the Qwen session config or throws [IllegalArgumentException] with the config code. */
    fun configOrThrow(settings: QwenAppSettings, apiKey: String, instructions: String): QwenApiConfig {
        validate(settings, apiKey)?.let { throw IllegalArgumentException(it) }
        return QwenApiConfig(settings, apiKey, instructions)
    }

    /**
     * The Qwen session config for a start (SPEC-021). Qwen speaks in its own voice, so an enabled
     * Azure assistant voice is only noted, never read: its key and completeness play no part.
     */
    fun sessionConfig(azureVoiceEnabled: Boolean, build: () -> QwenApiConfig): QwenApiConfig {
        if (azureVoiceEnabled) DebugVoiceLog.log("assistant_voice ignored reason=provider_speaks")
        return build()
    }

    /** The config code a Qwen start would fail with, or null; same order as [validate]. Presence only. */
    fun configProblem(settings: QwenAppSettings, keyPresent: Boolean): String? {
        validateSettings(settings)?.let { return it }
        if (!keyPresent) return "QWEN_API_KEY_MISSING"
        if (!settings.consentAccepted) return "QWEN_CONSENT_MISSING"
        return null
    }

    /** The one honest on-screen sentence for a Qwen config code; null for non-Qwen codes. */
    fun message(code: String): String? = when {
        code == "QWEN_API_KEY_MISSING" -> "语音服务未配置：请在开发者设置里填写通义千问密钥。"
        code == "QWEN_CONSENT_MISSING" -> "请先在开发者设置里同意语音数据跨境传输提示。"
        code == "QWEN_WORKSPACE_MISSING" -> "语音服务未配置：请在开发者设置里填写通义千问工作空间 ID。"
        code.startsWith("QWEN_") -> "语音服务设置有误，请检查开发者设置。"
        else -> null
    }
}

class QwenSettingsRepository(
    context: Context,
    private val credentials: CredentialStore = AndroidKeystoreCredentialStore(context.applicationContext),
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadSettings(): QwenAppSettings =
        QwenAppSettings(
            consentAccepted = prefs.getBoolean(KEY_CONSENT, false),
            model = prefs.getString(KEY_MODEL, null).orEmpty().ifBlank { VoiceCatalog.QWEN_OMNI_FLASH },
            workspaceId = prefs.getString(KEY_WORKSPACE, null).orEmpty(),
            // A field left empty means the default voice (Ayano) for that model.
            customVoices = VoiceCatalog.qwenModels.keys.mapNotNull { model ->
                (prefs.getString(KEY_VOICE_PREFIX + model, null)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: QwenAppSettings.DEFAULT_CUSTOM_VOICES[model])?.let { model to it }
            }.toMap(),
            vadType = prefs.getString(KEY_VAD, null).orEmpty().ifBlank { QwenAppSettings.DEFAULT_VAD },
            silenceDurationMs = prefs.getInt(KEY_SILENCE, QwenAppSettings.DEFAULT_SILENCE_MS),
        )

    fun save(settings: QwenAppSettings, key: CredentialUpdate = CredentialUpdate.Keep) {
        QwenSettingsValidator.validateSettings(settings)?.let { throw IllegalArgumentException(it) }
        credentials.applyCredentialUpdate(CRED_API_KEY, key)
        val edit = prefs.edit().remove(KEY_LEGACY_VOICE)
        VoiceCatalog.qwenModels.keys.forEach { model ->
            val voice = settings.customVoices[model]?.trim().orEmpty()
            val isDefault = voice.isEmpty() || voice == QwenAppSettings.DEFAULT_CUSTOM_VOICES[model]
            if (isDefault) edit.remove(KEY_VOICE_PREFIX + model) else edit.putString(KEY_VOICE_PREFIX + model, voice)
        }
        edit
            .putBoolean(KEY_CONSENT, settings.consentAccepted)
            .putString(KEY_MODEL, settings.model.trim())
            .putString(KEY_WORKSPACE, settings.workspaceId.trim())
            .putString(KEY_VAD, settings.vadType)
            .putInt(KEY_SILENCE, settings.silenceDurationMs)
            .apply()
    }

    fun keyPresent(): Boolean = !credentials.read(CRED_API_KEY).isNullOrBlank()

    fun clearKey() {
        credentials.clear(CRED_API_KEY)
    }

    /** The session config; throws [IllegalArgumentException] with a `QWEN_*` code. Never falls back. */
    fun config(instructions: String): QwenApiConfig =
        QwenSettingsValidator.configOrThrow(loadSettings(), credentials.read(CRED_API_KEY).orEmpty(), instructions)

    /** See [QwenSettingsValidator.configProblem]. */
    fun configProblem(): String? = QwenSettingsValidator.configProblem(loadSettings(), keyPresent())

    companion object {
        const val CRED_API_KEY = "qwen_api_key"
        private const val PREFS = "nova_qwen_settings"
        private const val KEY_CONSENT = "consent_accepted"
        private const val KEY_MODEL = "model"
        private const val KEY_WORKSPACE = "workspace_id"
        /** Pre-per-model single voice; never read, removed on save. */
        private const val KEY_LEGACY_VOICE = "voice"
        const val KEY_VOICE_PREFIX = "qwen_voice_"
        private const val KEY_VAD = "vad_type"
        private const val KEY_SILENCE = "silence_duration_ms"
    }
}
