package com.novadrive.app

import com.novadrive.app.voice.AzureSpeechConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AzureSpeechSettingsTest {
    private val v = AzureSpeechSettingsValidator
    private val ok = AzureSpeechSettings(enabled = true, region = "eastasia")

    @Test
    fun `valid regions and voices pass`() {
        assertNull(v.validateSettings(ok))
        assertNull(v.validateSettings(ok.copy(region = "chinaeast2")))
        assertNull(v.validateSettings(ok.copy(region = " EastAsia ")))
        assertNull(v.validateSettings(ok.copy(voice = "zh-CN-XiaoyiNeural")))
        assertNull(v.validateSettings(ok.copy(voice = "zh-CN-Xiaoyi:DragonHDFlashLatestNeural")))
    }

    @Test
    fun `invalid region and voice give codes`() {
        assertEquals("AZURE_REGION_INVALID", v.validateSettings(ok.copy(region = "east asia")))
        assertEquals("AZURE_REGION_INVALID", v.validateSettings(ok.copy(region = "")))
        assertEquals("AZURE_VOICE_INVALID", v.validateSettings(ok.copy(voice = "xiaoyi")))
        assertEquals("AZURE_VOICE_INVALID", v.validateSettings(ok.copy(voice = "")))
    }

    @Test
    fun `configProblem respects enabled and key`() {
        assertNull(v.configProblem(AzureSpeechSettings(), keyPresent = false))
        assertNull(v.configProblem(ok.copy(enabled = false, region = "bad region"), keyPresent = false))
        assertEquals("AZURE_KEY_MISSING", v.configProblem(ok, keyPresent = false))
        assertEquals("AZURE_REGION_INVALID", v.configProblem(ok.copy(region = ""), keyPresent = true))
        assertNull(v.configProblem(ok, keyPresent = true))
    }

    @Test
    fun `configOrNull builds trimmed config or throws code`() {
        assertNull(v.configOrNull(AzureSpeechSettings(), "k"))
        val c = v.configOrNull(ok.copy(region = " EastAsia ", voice = " zh-CN-XiaoyiNeural "), " secret-k ")
        assertNotNull(c)
        assertEquals("secret-k", c!!.key)
        assertEquals("eastasia", c.region)
        assertEquals("zh-CN-XiaoyiNeural", c.voice)
        val missing = assertThrows(IllegalArgumentException::class.java) { v.configOrNull(ok, null) }
        assertEquals("AZURE_KEY_MISSING", missing.message)
        val blank = assertThrows(IllegalArgumentException::class.java) { v.configOrNull(ok, "  ") }
        assertEquals("AZURE_KEY_MISSING", blank.message)
        val region = assertThrows(IllegalArgumentException::class.java) { v.configOrNull(ok.copy(region = "x"), "k") }
        assertEquals("AZURE_REGION_INVALID", region.message)
    }

    @Test
    fun `screen messages`() {
        assertNotNull(v.screenMessage("AZURE_KEY_MISSING"))
        assertNotNull(v.screenMessage("AZURE_REGION_INVALID"))
        assertNotNull(v.screenMessage("AZURE_VOICE_INVALID"))
        assertNull(v.screenMessage("GEMINI_KEY_MISSING"))
        assertNull(v.screenMessage("VOICE"))
    }

    @Test
    fun `config hides key and picks host`() {
        val c = AzureSpeechConfig(key = "super-secret-123", region = "eastasia")
        assertFalse(c.toString().contains("super-secret-123"))
        assertEquals("zh-CN-XiaoyiNeural", c.voice)
        assertEquals("eastasia.tts.speech.microsoft.com", c.host)
        assertEquals("chinaeast2.tts.speech.azure.cn", c.copy(region = "chinaeast2").host)
    }
}
