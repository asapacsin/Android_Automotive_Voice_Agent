package com.novadrive.app

import com.novadrive.ingress.realtime.VoiceCatalog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QwenSettingsTest {
    private val valid = QwenAppSettings(consentAccepted = true, workspaceId = "ws-abc123")

    @Test
    fun defaults() {
        val s = QwenAppSettings()
        assertFalse(s.consentAccepted)
        assertEquals(VoiceCatalog.QWEN_OMNI_FLASH, s.model)
        assertEquals("qwen3.8-omni-flash-realtime", s.model)
        assertEquals("Maia", s.sessionVoice())
        assertEquals("semantic_vad", s.vadType)
        assertEquals(800, s.silenceDurationMs)
        assertEquals("", s.workspaceId)
        assertTrue(QwenAppSettings.CONSENT_NOTICE.contains("Singapore"))
        assertTrue(QwenAppSettings.CONSENT_NOTICE.contains("新加坡"))
    }

    @Test
    fun validatorCodes() {
        assertNull(QwenSettingsValidator.validateSettings(valid))
        assertEquals("QWEN_MODEL_INVALID", QwenSettingsValidator.validateSettings(valid.copy(model = "gpt")))
        assertEquals("QWEN_WORKSPACE_MISSING", QwenSettingsValidator.validateSettings(valid.copy(workspaceId = " ")))
        assertEquals("QWEN_WORKSPACE_INVALID", QwenSettingsValidator.validateSettings(valid.copy(workspaceId = "a.b")))
        assertEquals("QWEN_WORKSPACE_INVALID", QwenSettingsValidator.validateSettings(valid.copy(workspaceId = "a/b")))
        assertEquals("QWEN_WORKSPACE_INVALID", QwenSettingsValidator.validateSettings(valid.copy(workspaceId = "a".repeat(65))))
        assertNull(QwenSettingsValidator.validateSettings(valid.copy(workspaceId = "a".repeat(63))))
        assertEquals("QWEN_WORKSPACE_INVALID", QwenSettingsValidator.validateSettings(valid.copy(workspaceId = "a".repeat(64))))
        val m = valid.model
        assertNull(QwenSettingsValidator.validateSettings(valid.copy(customVoices = mapOf(m to " "))))
        assertEquals("QWEN_VOICE_INVALID", QwenSettingsValidator.validateSettings(valid.copy(customVoices = mapOf(m to "a b"))))
        assertEquals("QWEN_VOICE_INVALID", QwenSettingsValidator.validateSettings(valid.copy(customVoices = mapOf(m to "a".repeat(81)))))
        assertNull(QwenSettingsValidator.validateSettings(valid.copy(customVoices = mapOf(m to "a".repeat(80)))))
        assertEquals("QWEN_VOICE_INVALID", QwenSettingsValidator.validateSettings(valid.copy(customVoices = mapOf("gpt" to "v1"))))
        assertEquals(
            "QWEN_VOICE_INVALID",
            QwenSettingsValidator.validateSettings(valid.copy(customVoices = mapOf(VoiceCatalog.QWEN_OMNI_PLUS_35 to "bad.voice"))),
            "a bad voice for the other model is still rejected",
        )
        assertEquals("QWEN_SILENCE_INVALID", QwenSettingsValidator.validateSettings(valid.copy(silenceDurationMs = 199)))
        assertEquals("QWEN_SILENCE_INVALID", QwenSettingsValidator.validateSettings(valid.copy(silenceDurationMs = 6001)))
        assertNull(QwenSettingsValidator.validateSettings(valid.copy(silenceDurationMs = 200)))
        assertNull(QwenSettingsValidator.validateSettings(valid.copy(silenceDurationMs = 6000)))
        assertEquals("QWEN_VAD_INVALID", QwenSettingsValidator.validateSettings(valid.copy(vadType = "none")))
        assertNull(QwenSettingsValidator.validateSettings(valid.copy(vadType = "server_vad")))
        assertEquals("QWEN_API_KEY_MISSING", QwenSettingsValidator.validate(valid, " "))
        assertEquals("QWEN_CONSENT_MISSING", QwenSettingsValidator.validate(valid.copy(consentAccepted = false), "k"))
        assertNull(QwenSettingsValidator.validate(valid, "k"))
    }

    @Test
    fun messages() {
        for (code in listOf("QWEN_API_KEY_MISSING", "QWEN_CONSENT_MISSING", "QWEN_WORKSPACE_MISSING", "QWEN_VAD_INVALID")) {
            assertTrue(!QwenSettingsValidator.message(code).isNullOrBlank(), code)
        }
        assertNull(QwenSettingsValidator.message("GEMINI_API_KEY_MISSING"))
    }

    @Test
    fun endpointUrl() {
        assertEquals(
            "wss://ws-abc123.ap-southeast-1.maas.aliyuncs.com/api-ws/v1/realtime?model=qwen3.8-omni-flash-realtime",
            QwenSettings.endpointUrl(valid),
        )
    }

    @Test
    fun toStringRedactsKeyAndWorkspace() {
        val text = QwenApiConfig(valid, "sk-secret-123", "hello").toString()
        assertFalse(text.contains("sk-secret-123"))
        assertFalse(text.contains("ws-abc123"))
        assertFalse(valid.toString().contains("ws-abc123"))
        assertTrue(text.contains("<redacted>"))
    }

    @Test
    fun configOrThrowKeepsTheCustomVoice() {
        val built = QwenSettingsValidator.configOrThrow(valid.copy(customVoices = mapOf(valid.model to CLONED)), "sk-test-key", "p")
        assertEquals(CLONED, built.settings.sessionVoice())
        assertEquals("Maia", QwenSettingsValidator.configOrThrow(valid, "sk-test-key", "p").settings.sessionVoice())
        val bad = runCatching { QwenSettingsValidator.configOrThrow(valid.copy(customVoices = mapOf(valid.model to "a b")), "k", "p") }
        assertEquals("QWEN_VOICE_INVALID", bad.exceptionOrNull()?.message)
    }

    @Test
    fun sessionVoiceIsBoundToTheSelectedModel() {
        val plus = valid.copy(model = VoiceCatalog.QWEN_OMNI_PLUS_35, customVoices = mapOf(VoiceCatalog.QWEN_OMNI_PLUS_35 to CLONED))
        assertEquals(CLONED, plus.sessionVoice())
        assertEquals("Maia", plus.copy(model = VoiceCatalog.QWEN_OMNI_FLASH).sessionVoice(), "3.5 voice must not reach 3.8")
        assertEquals("Maia", valid.copy(customVoices = mapOf(valid.model to "  ")).sessionVoice())
        assertNull(QwenSettingsValidator.validateSettings(plus))
    }

    private companion object {
        const val CLONED = "qwen-omni-vc-guanyu-voice-20250812105009984-838b"
    }

    @Test
    fun theExperimentModelIsAcceptedAndReachesTheEndpoint() {
        val s = QwenAppSettings(consentAccepted = true, model = VoiceCatalog.QWEN_OMNI_PLUS_35, workspaceId = "ws-1")
        assertEquals(null, QwenSettingsValidator.validateSettings(s))
        assertTrue(QwenSettings.endpointUrl(s).endsWith("?model=qwen3.5-omni-plus-realtime"))
        assertEquals(com.novadrive.ingress.realtime.VoiceProviderId.QWEN, VoiceCatalog.providerForModel(VoiceCatalog.QWEN_OMNI_PLUS_35))
    }

    @Test
    fun theOldQwen3OmniRealtimeIsNotOfferedItHasNoFunctionCalling() {
        val s = QwenAppSettings(consentAccepted = true, model = "qwen3-omni-flash-realtime-2025-09-15", workspaceId = "ws-1")
        assertEquals("QWEN_MODEL_INVALID", QwenSettingsValidator.validateSettings(s))
    }
}
