package com.novadrive.app

import com.novadrive.ingress.realtime.VoiceCatalog
import com.novadrive.ingress.realtime.VoiceProviderId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GeminiSettingsTest {
    private val valid = GeminiAppSettings(enabled = true, consentAccepted = true)

    @Test
    fun defaultsAreOptOut() {
        val s = GeminiAppSettings()
        assertFalse(s.enabled)
        assertFalse(s.consentAccepted)
        assertEquals("Kore", s.voice)
        assertEquals(GeminiThinkingLevel.LOW, s.thinkingLevel)
        assertEquals(VoiceCatalog.GEMINI_LIVE, s.model)
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
        for (enabled in listOf(false, true)) for (consent in listOf(false, true)) for (key in listOf(false, true)) {
            val expected = if (enabled && consent && key) VoiceProviderId.GEMINI_LIVE else VoiceProviderId.BAIDU_FLEX
            assertEquals(expected, VoiceProviderChoice.resolve(GeminiAppSettings(enabled = enabled, consentAccepted = consent), key)) {
                "enabled=$enabled consent=$consent key=$key"
            }
        }
        assertEquals(VoiceProviderId.BAIDU_FLEX, VoiceProviderChoice.resolve(valid.copy(endpoint = "ws://x"), true))
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
        listOf(VoiceCatalog.GEMINI_LIVE_FAST, VoiceCatalog.GEMINI_LIVE).forEach { model ->
            val s = valid.copy(model = model)
            assertNull(GeminiSettingsValidator.validateSettings(s))
            assertEquals(model, s.copy(voice = "Puck").model)
            assertEquals(VoiceProviderId.GEMINI_LIVE, VoiceProviderChoice.resolve(s, keyPresent = true))
        }
    }
}
