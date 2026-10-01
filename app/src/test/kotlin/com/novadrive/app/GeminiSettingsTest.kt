package com.novadrive.app

import com.novadrive.ingress.realtime.VoiceCatalog
import com.novadrive.ingress.realtime.VoiceProviderId
import com.novadrive.app.voice.sessionConfigFor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GeminiSettingsTest {
    private val valid = GeminiAppSettings(consentAccepted = true)

    @Test
    fun defaultsPreferGeminiWithoutConsent() {
        val s = GeminiAppSettings()
        assertEquals(VoiceProviderPreference.GEMINI, s.provider)
        assertFalse(s.consentAccepted)
        assertEquals("Leda", s.voice)
        assertEquals(GeminiThinkingLevel.LOW, s.thinkingLevel)
        assertEquals(VoiceCatalog.GEMINI_LIVE_DEFAULT, s.model)
        assertEquals("gemini-3.8-live", s.model)
        assertNull(s.silenceDurationMs)
        assertNull(GeminiSettingsValidator.validateSettings(s))
        assertTrue(GeminiAppSettings.CONSENT_NOTICE.contains("Google"))
    }

    @Test
    fun validatorCodes() {
        assertEquals("GEMINI_MODEL_INVALID", GeminiSettingsValidator.validateSettings(valid.copy(model = "gpt")))
        assertEquals("GEMINI_ENDPOINT_INVALID", GeminiSettingsValidator.validateSettings(valid.copy(endpoint = "https://x")))
        assertEquals("GEMINI_VOICE_INVALID", GeminiSettingsValidator.validateSettings(valid.copy(voice = "")))
        assertEquals("GEMINI_VOICE_INVALID", GeminiSettingsValidator.validateSettings(valid.copy(voice = "a b")))
        assertEquals("GEMINI_VOICE_INVALID", GeminiSettingsValidator.validateSettings(valid.copy(voice = "a".repeat(33))))
        assertEquals("GEMINI_SILENCE_INVALID", GeminiSettingsValidator.validateSettings(valid.copy(silenceDurationMs = 99)))
        assertEquals("GEMINI_SILENCE_INVALID", GeminiSettingsValidator.validateSettings(valid.copy(silenceDurationMs = 3001)))
        assertNull(GeminiSettingsValidator.validateSettings(valid.copy(silenceDurationMs = 100)))
        assertNull(GeminiSettingsValidator.validateSettings(valid.copy(silenceDurationMs = 3000)))
        assertEquals("GEMINI_API_KEY_MISSING", GeminiSettingsValidator.validate(valid, " "))
        assertEquals("GEMINI_CONSENT_MISSING", GeminiSettingsValidator.validate(valid.copy(consentAccepted = false), "k"))
        assertNull(GeminiSettingsValidator.validate(valid, "k"))
        assertNull(GeminiSettingsValidator.validateSettings(valid.copy(consentAccepted = false)))
    }

    @Test
    fun resolverTruthTable() {
        for (pref in VoiceProviderPreference.entries) for (consent in listOf(false, true)) for (key in listOf(false, true)) {
            val expected = if (pref == VoiceProviderPreference.BAIDU) VoiceProviderId.BAIDU_FLEX else VoiceProviderId.GEMINI_LIVE
            assertEquals(expected, VoiceProviderChoice.resolve(GeminiAppSettings(provider = pref, consentAccepted = consent), key)) {
                "pref=$pref consent=$consent key=$key"
            }
        }
        // Invalid settings do not fall back to Baidu either; the config path reports the code.
        assertEquals(VoiceProviderId.GEMINI_LIVE, VoiceProviderChoice.resolve(valid.copy(endpoint = "ws://x"), true))
    }

    @Test
    fun preferenceWireNamesAndStoredDefaultIsGemini() {
        assertEquals("gemini", VoiceProviderPreference.GEMINI.wireName)
        assertEquals("baidu", VoiceProviderPreference.BAIDU.wireName)
        assertEquals(VoiceProviderPreference.BAIDU, VoiceProviderPreference.fromWire("baidu"))
        assertEquals(VoiceProviderPreference.GEMINI, VoiceProviderPreference.fromWire(null))
        assertEquals(VoiceProviderPreference.GEMINI, VoiceProviderPreference.fromWire("garbage"))
    }

    private fun configCode(settings: GeminiAppSettings, key: String): Pair<VoiceProviderId, String?> {
        val choice = VoiceProviderChoice.resolve(settings, key.isNotBlank())
        var baiduTouched = false
        val code = try {
            sessionConfigFor(
                choice,
                gemini = { GeminiSettingsValidator.configOrThrow(settings, key, "persona") },
                baidu = { baiduTouched = true; error("Baidu must not be opened") },
            )
            null
        } catch (failure: IllegalArgumentException) {
            failure.message
        }
        assertFalse(baiduTouched)
        return choice to code
    }

    @Test
    fun geminiPreferenceWithMissingKeyOrConsentFailsWithCodeAndNeverOpensBaidu() {
        assertEquals(VoiceProviderId.GEMINI_LIVE to "GEMINI_API_KEY_MISSING", configCode(GeminiAppSettings(), ""))
        assertEquals(VoiceProviderId.GEMINI_LIVE to "GEMINI_API_KEY_MISSING", configCode(valid, " "))
        assertEquals(VoiceProviderId.GEMINI_LIVE to "GEMINI_CONSENT_MISSING", configCode(GeminiAppSettings(), "k"))
        assertEquals(VoiceProviderId.GEMINI_LIVE to "GEMINI_ENDPOINT_INVALID", configCode(valid.copy(endpoint = "ws://x"), "k"))
        assertEquals(VoiceProviderId.GEMINI_LIVE to null, configCode(valid, "k"))
    }

    @Test
    fun configCodesMapToOneHonestSentence() {
        assertEquals("语音服务未配置：请在开发者设置里填写 Gemini 密钥。", GeminiSettingsValidator.screenMessage("GEMINI_API_KEY_MISSING"))
        assertEquals("请先在开发者设置里同意语音数据跨境传输提示。", GeminiSettingsValidator.screenMessage("GEMINI_CONSENT_MISSING"))
        assertEquals("语音服务设置有误，请检查开发者设置。", GeminiSettingsValidator.screenMessage("GEMINI_VOICE_INVALID"))
        assertNull(GeminiSettingsValidator.screenMessage("BAIDU_API_KEY_MISSING"))
    }

    @Test
    fun configToStringRedactsKey() {
        val key = "secret-" + "k".repeat(30)
        val text = GeminiApiConfig(valid, key, "persona").toString()
        assertFalse(text.contains(key))
        assertFalse(text.contains("kkkkkkkk"))
        assertTrue(text.contains("<redacted>"))
    }

    @Test
    fun thinkingLevelFromWireFallsBackToLow() {
        assertEquals(GeminiThinkingLevel.HIGH, GeminiThinkingLevel.fromWire("HIGH"))
        assertEquals(GeminiThinkingLevel.LOW, GeminiThinkingLevel.fromWire("MINIMAL"))
        assertEquals(GeminiThinkingLevel.LOW, GeminiThinkingLevel.fromWire(null))
    }

    @Test
    fun bothDeclaredGeminiModelsAreValidAndSelectGemini() {
        listOf(VoiceCatalog.GEMINI_LIVE_FAST, VoiceCatalog.GEMINI_LIVE_EXTENDED).forEach { model ->
            val s = valid.copy(model = model)
            assertNull(GeminiSettingsValidator.validateSettings(s))
            assertEquals(model, s.copy(voice = "Puck").model)
            assertEquals(VoiceProviderId.GEMINI_LIVE, VoiceProviderChoice.resolve(s, keyPresent = true))
        }
    }

    @Test
    fun unsetModelLoadsAsDefaultAndSavedExtendedIsPreserved() {
        assertEquals("gemini-3.8-live", storedGeminiModelOrDefault(null))
        assertEquals("gemini-3.8-live", storedGeminiModelOrDefault("  "))
        assertEquals(VoiceCatalog.GEMINI_LIVE_EXTENDED, storedGeminiModelOrDefault(VoiceCatalog.GEMINI_LIVE_EXTENDED))
    }

    @Test
    fun aSavedExtendedModelIsSwitchedToTheLiveModelOnlyOnce() {
        assertEquals(VoiceCatalog.GEMINI_LIVE_DEFAULT, geminiModelAfterOneTimeSwitch(VoiceCatalog.GEMINI_LIVE_EXTENDED, alreadySwitched = false))
        assertEquals(VoiceCatalog.GEMINI_LIVE_EXTENDED, geminiModelAfterOneTimeSwitch(VoiceCatalog.GEMINI_LIVE_EXTENDED, alreadySwitched = true))
        assertEquals(VoiceCatalog.GEMINI_LIVE_DEFAULT, geminiModelAfterOneTimeSwitch(VoiceCatalog.GEMINI_LIVE_DEFAULT, alreadySwitched = false))
        assertEquals(null, geminiModelAfterOneTimeSwitch(null, alreadySwitched = false))
    }

    @Test
    fun configProblemTruthTable() {
        val valid = GeminiAppSettings(consentAccepted = true)
        assertNull(GeminiSettingsValidator.configProblem(GeminiAppSettings(provider = VoiceProviderPreference.BAIDU), keyPresent = false))
        assertEquals("GEMINI_API_KEY_MISSING", GeminiSettingsValidator.configProblem(valid, keyPresent = false))
        assertEquals("GEMINI_CONSENT_MISSING", GeminiSettingsValidator.configProblem(GeminiAppSettings(), keyPresent = true))
        assertEquals("GEMINI_VOICE_INVALID", GeminiSettingsValidator.configProblem(valid.copy(voice = "bad voice"), keyPresent = true))
        assertNull(GeminiSettingsValidator.configProblem(valid, keyPresent = true))
    }

    @Test
    fun theOldPrefilledVoiceSwitchesOnceToTheNewDefault() {
        assertEquals("Leda", geminiVoiceAfterOneTimeSwitch("Kore", alreadySwitched = false))
        assertEquals("Kore", geminiVoiceAfterOneTimeSwitch("Kore", alreadySwitched = true))
        assertEquals("Erinome", geminiVoiceAfterOneTimeSwitch("Erinome", alreadySwitched = false))
        assertEquals(null, geminiVoiceAfterOneTimeSwitch(null, alreadySwitched = false))
    }
}
