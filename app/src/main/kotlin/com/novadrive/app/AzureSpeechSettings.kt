package com.novadrive.app

import android.content.Context
import com.novadrive.app.voice.AzureSpeechConfig

/** ADR-016: the owner's assistant voice (Azure AI Speech). Developer setting, off by default. */
data class AzureSpeechSettings(
    val enabled: Boolean = false,
    val region: String = "",
    val voice: String = AzureSpeechConfig.DEFAULT_VOICE,
)

object AzureSpeechSettingsValidator {
    const val KEY_MISSING = "AZURE_KEY_MISSING"
    const val REGION_INVALID = "AZURE_REGION_INVALID"
    const val VOICE_INVALID = "AZURE_VOICE_INVALID"

    /** Card text when the voice fails mid-session (ADR-016 §6); the subtitle stays. */
    const val UNAVAILABLE_MESSAGE = "小诺的声音暂时不可用，回复只显示文字。"

    private val REGION = Regex("^[a-z0-9]{3,32}$")
    private val VOICE = Regex("^[A-Za-z]{2,3}-[A-Za-z]{2,4}-[A-Za-z0-9:]{1,48}$")

    fun validateSettings(settings: AzureSpeechSettings): String? = when {
        !REGION.matches(settings.region.trim().lowercase()) -> REGION_INVALID
        !VOICE.matches(settings.voice.trim()) -> VOICE_INVALID
        else -> null
    }

    fun configProblem(settings: AzureSpeechSettings, keyPresent: Boolean): String? {
        if (!settings.enabled) return null
        return validateSettings(settings) ?: if (keyPresent) null else KEY_MISSING
    }

    fun configOrNull(settings: AzureSpeechSettings, key: String?): AzureSpeechConfig? {
        if (!settings.enabled) return null
        configProblem(settings, !key.isNullOrBlank())?.let { throw IllegalArgumentException(it) }
        return AzureSpeechConfig(
            key = key!!.trim(),
            region = settings.region.trim().lowercase(),
            voice = settings.voice.trim(),
        )
    }

    fun screenMessage(code: String): String? = when (code) {
        KEY_MISSING -> "小诺的声音未配置：请在开发者设置里填写 Azure 语音密钥。"
        REGION_INVALID -> "Azure 语音区域设置有误，请检查开发者设置。"
        VOICE_INVALID -> "Azure 语音音色设置有误，请检查开发者设置。"
        else -> null
    }
}

/** Prefs for the switch/region/voice; the key lives in the Keystore only and is never logged. */
class AzureSpeechSettingsRepository(
    context: Context,
    private val credentials: CredentialStore = AndroidKeystoreCredentialStore(context.applicationContext),
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadSettings(): AzureSpeechSettings =
        AzureSpeechSettings(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            region = prefs.getString(KEY_REGION, null).orEmpty(),
            voice = prefs.getString(KEY_VOICE, null).orEmpty().ifBlank { AzureSpeechConfig.DEFAULT_VOICE },
        )

    fun save(settings: AzureSpeechSettings, key: CredentialUpdate = CredentialUpdate.Keep) {
        if (settings.enabled) {
            AzureSpeechSettingsValidator.validateSettings(settings)?.let { throw IllegalArgumentException(it) }
        }
        credentials.applyCredentialUpdate(CRED_KEY, key)
        prefs.edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putString(KEY_REGION, settings.region.trim().lowercase())
            .putString(KEY_VOICE, settings.voice.trim())
            .apply()
    }

    fun keyPresent(): Boolean = !credentials.read(CRED_KEY).isNullOrBlank()

    fun clearKey() {
        credentials.clear(CRED_KEY)
    }

    fun configProblem(): String? = AzureSpeechSettingsValidator.configProblem(loadSettings(), keyPresent())

    fun config(): AzureSpeechConfig? =
        AzureSpeechSettingsValidator.configOrNull(loadSettings(), credentials.read(CRED_KEY))

    companion object {
        const val CRED_KEY = "azure_speech_key"
        private const val PREFS = "nova_azure_speech_settings"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_REGION = "region"
        private const val KEY_VOICE = "voice"
    }
}
