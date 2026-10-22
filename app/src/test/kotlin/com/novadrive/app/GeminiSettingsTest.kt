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
    fun defaultsPreferQwenWithoutConsent() {
        val s = GeminiAppSettings()
        assertEquals(VoiceProviderPreference.QWEN, s.provider)
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

    /** The session provider: Gemini Live while Qwen is frozen (owner, 2026-10-22), else Qwen. */
    private val SESSION = if (VoiceProviderChoice.QWEN_FROZEN) VoiceProviderId.GEMINI_LIVE else VoiceProviderId.QWEN

    @Test
    fun resolverAlwaysSelectsQwenRegardlessOfStoredPreference() {
        for (pref in VoiceProviderPreference.entries) for (consent in listOf(false, true)) for (key in listOf(false, true)) {
            assertEquals(
                SESSION,
                VoiceProviderChoice.resolve(GeminiAppSettings(provider = pref, consentAccepted = consent), key),
            ) { "pref=$pref consent=$consent key=$key" }
        }
        assertEquals(SESSION, VoiceProviderChoice.resolve(valid.copy(endpoint = "ws://x"), true))
    }

    @Test
    fun preferenceWireNamesAndUnsetFallsBackToQwen() {
        assertEquals("gemini", VoiceProviderPreference.GEMINI.wireName)
        assertEquals("baidu", VoiceProviderPreference.BAIDU.wireName)
        assertEquals("qwen", VoiceProviderPreference.QWEN.wireName)
        assertEquals(VoiceProviderPreference.QWEN, VoiceProviderPreference.fromWire("qwen"))
        assertEquals(VoiceProviderPreference.BAIDU, VoiceProviderPreference.fromWire("baidu"))
        assertEquals(VoiceProviderPreference.GEMINI, VoiceProviderPreference.fromWire("gemini"))
        assertEquals(VoiceProviderPreference.QWEN, VoiceProviderPreference.fromWire(null))
        assertEquals(VoiceProviderPreference.QWEN, VoiceProviderPreference.fromWire("garbage"))
    }

    private fun qwenStartCode(
        qwenSettings: QwenAppSettings,
        qwenKey: String,
        storedPreference: VoiceProviderPreference = VoiceProviderPreference.GEMINI,
    ): Pair<VoiceProviderId, String?> {
        val geminiStored = GeminiAppSettings(provider = storedPreference)
        @Suppress("UNUSED_VARIABLE") val stored = geminiStored
        val choice = VoiceProviderId.QWEN // the Qwen start path itself, also while Qwen is frozen
        var baiduTouched = false
        var geminiTouched = false
        var qwenTouched = false
        val code = try {
            sessionConfigFor(
                choice,
                gemini = { geminiTouched = true; GeminiSettingsValidator.configOrThrow(geminiStored, qwenKey, "persona") },
                baidu = { baiduTouched = true; error("Baidu must not be opened") },
                qwen = {
                    qwenTouched = true
                    QwenSettingsValidator.configOrThrow(qwenSettings, qwenKey, "persona")
                },
            )
            null
        } catch (failure: IllegalArgumentException) {
            failure.message
        }
        assertFalse(baiduTouched)
        assertFalse(geminiTouched)
        assertTrue(qwenTouched)
        return choice to code
    }

    @Test
    fun productSessionInvokesOnlyQwenAndMissingQwenConfigNeverOpensGeminiOrBaidu() {
        val ready = QwenAppSettings(consentAccepted = true, workspaceId = "ws")
        assertEquals(VoiceProviderId.QWEN to "QWEN_API_KEY_MISSING", qwenStartCode(ready, ""))
        assertEquals(VoiceProviderId.QWEN to "QWEN_API_KEY_MISSING", qwenStartCode(ready, " "))
        assertEquals(VoiceProviderId.QWEN to "QWEN_CONSENT_MISSING", qwenStartCode(QwenAppSettings(workspaceId = "ws"), "k"))
        assertEquals(VoiceProviderId.QWEN to "QWEN_WORKSPACE_MISSING", qwenStartCode(QwenAppSettings(consentAccepted = true), "k"))
        assertEquals(
            VoiceProviderId.QWEN to "QWEN_API_KEY_MISSING",
            qwenStartCode(ready, "", storedPreference = VoiceProviderPreference.BAIDU),
        )
        val qwenOk = QwenAppSettings(consentAccepted = true, workspaceId = "ws")
        var geminiTouched = false
        var baiduTouched = false
        sessionConfigFor(
            VoiceProviderId.QWEN,
            gemini = { geminiTouched = true; error("gemini") },
            baidu = { baiduTouched = true; error("baidu") },
            qwen = { QwenSettingsValidator.configOrThrow(qwenOk, "k", "persona") },
        )
        assertFalse(geminiTouched)
        assertFalse(baiduTouched)
    }

    @Test
    fun startProblemForQwenDoesNotCallGeminiOrAzureEvenWhenTheyWouldFail() {
        var geminiCalls = 0
        var azureCalls = 0
        val code = VoiceProviderChoice.startProblem(
            VoiceProviderId.QWEN,
            qwen = { "QWEN_API_KEY_MISSING" },
            gemini = { geminiCalls++; "GEMINI_API_KEY_MISSING" },
            azure = { azureCalls++; "AZURE_SPEECH_KEY_MISSING" },
        )
        assertEquals("QWEN_API_KEY_MISSING", code)
        assertEquals(0, geminiCalls)
        assertEquals(0, azureCalls)
    }

    // ---- debug provider override (decided by the decider, 2026-10-15) ----

    @Test
    fun releaseAppIgnoresTheDebugOverride() {
        for (pref in VoiceProviderPreference.entries) {
            val s = GeminiAppSettings(provider = pref, consentAccepted = true)
            assertEquals(SESSION, VoiceProviderChoice.resolveForSession(s, true, debuggable = false, geminiOverride = true))
        }
    }

    @Test
    fun debugAppWithoutOverrideIsQwenAsToday() {
        val s = GeminiAppSettings(provider = VoiceProviderPreference.GEMINI, consentAccepted = true)
        assertEquals(SESSION, VoiceProviderChoice.resolveForSession(s, true, debuggable = true, geminiOverride = false))
        assertEquals(VoiceProviderChoice.resolve(s, true), VoiceProviderChoice.resolveForSession(s, true, true, false))
    }

    @Test
    fun debugAppWithOverrideIsGemini() {
        assertEquals(VoiceProviderId.GEMINI_LIVE, VoiceProviderChoice.resolveForSession(GeminiAppSettings(), false, debuggable = true, geminiOverride = true))
    }

    @Test
    fun overrideWithoutGeminiKeyFailsWithGeminiCodeAndNeverOpensQwen() {
        // The stored preference is QWEN, where configProblem passes silently: the override must not.
        val stored = GeminiAppSettings(provider = VoiceProviderPreference.QWEN, consentAccepted = true)
        assertNull(GeminiSettingsValidator.configProblem(stored, keyPresent = false))
        assertEquals("GEMINI_API_KEY_MISSING", GeminiSettingsValidator.debugOverrideProblem(stored, keyPresent = false))
        assertEquals("GEMINI_CONSENT_MISSING", GeminiSettingsValidator.debugOverrideProblem(GeminiAppSettings(), keyPresent = true))
        assertNull(GeminiSettingsValidator.debugOverrideProblem(stored, keyPresent = true))
        var qwenCalls = 0
        val code = VoiceProviderChoice.startProblem(
            VoiceProviderChoice.resolveForSession(stored, false, debuggable = true, geminiOverride = true),
            qwen = { qwenCalls++; null },
            gemini = { GeminiSettingsValidator.debugOverrideProblem(stored, keyPresent = false) },
            azure = { null },
        )
        assertEquals("GEMINI_API_KEY_MISSING", code)
        assertEquals(0, qwenCalls)
        var qwenBuilt = false
        val failure = runCatching {
            sessionConfigFor(
                VoiceProviderId.GEMINI_LIVE,
                gemini = { GeminiSettingsValidator.configOrThrow(GeminiSettingsValidator.debugOverrideSettings(stored), "", "i") },
                baidu = { error("baidu") },
                qwen = { qwenBuilt = true; error("qwen") },
            )
        }.exceptionOrNull()
        assertEquals("GEMINI_API_KEY_MISSING", failure?.message)
        assertFalse(qwenBuilt)
    }

    @Test
    fun overrideUsesTheHarnessModelEvenWhenTheStoredOneIsInvalid() {
        val stored = GeminiAppSettings(consentAccepted = true, model = "not-a-model")
        assertEquals(VoiceCatalog.geminiExperimentOrder.first(), GeminiSettingsValidator.debugOverrideSettings(stored).model)
        assertNull(GeminiSettingsValidator.debugOverrideProblem(stored, keyPresent = true))
        assertEquals("GEMINI_VOICE_INVALID", GeminiSettingsValidator.debugOverrideProblem(stored.copy(voice = "bad voice"), keyPresent = true))
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
    fun bothDeclaredGeminiModelsAreValidAndStillResolveToQwenForTheProductSession() {
        listOf(VoiceCatalog.GEMINI_LIVE_FAST, VoiceCatalog.GEMINI_LIVE_EXTENDED).forEach { model ->
            val s = valid.copy(model = model)
            assertNull(GeminiSettingsValidator.validateSettings(s))
            assertEquals(model, s.copy(voice = "Puck").model)
            assertEquals(SESSION, VoiceProviderChoice.resolve(s, keyPresent = true))
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
        val validGemini = GeminiAppSettings(provider = VoiceProviderPreference.GEMINI, consentAccepted = true)
        assertNull(GeminiSettingsValidator.configProblem(GeminiAppSettings(provider = VoiceProviderPreference.BAIDU), keyPresent = false))
        assertNull(GeminiSettingsValidator.configProblem(GeminiAppSettings(provider = VoiceProviderPreference.QWEN), keyPresent = false))
        assertEquals("GEMINI_API_KEY_MISSING", GeminiSettingsValidator.configProblem(validGemini, keyPresent = false))
        assertEquals("GEMINI_CONSENT_MISSING", GeminiSettingsValidator.configProblem(GeminiAppSettings(provider = VoiceProviderPreference.GEMINI), keyPresent = true))
        assertEquals("GEMINI_VOICE_INVALID", GeminiSettingsValidator.configProblem(validGemini.copy(voice = "bad voice"), keyPresent = true))
        assertNull(GeminiSettingsValidator.configProblem(validGemini, keyPresent = true))
    }

    @Test
    fun theOldPrefilledVoiceSwitchesOnceToTheNewDefault() {
        assertEquals("Leda", geminiVoiceAfterOneTimeSwitch("Kore", alreadySwitched = false))
        assertEquals("Kore", geminiVoiceAfterOneTimeSwitch("Kore", alreadySwitched = true))
        assertEquals("Erinome", geminiVoiceAfterOneTimeSwitch("Erinome", alreadySwitched = false))
        assertEquals(null, geminiVoiceAfterOneTimeSwitch(null, alreadySwitched = false))
    }
}
