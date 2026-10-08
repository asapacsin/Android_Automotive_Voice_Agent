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
        assertEquals("Maia", s.voice)
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
        assertNull(QwenSettingsValidator.validateSettings(valid.copy(workspaceId = "a".repeat(64))))
        assertEquals("QWEN_VOICE_INVALID", QwenSettingsValidator.validateSettings(valid.copy(voice = "")))
        assertEquals("QWEN_VOICE_INVALID", QwenSettingsValidator.validateSettings(valid.copy(voice = "a b")))
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
}
