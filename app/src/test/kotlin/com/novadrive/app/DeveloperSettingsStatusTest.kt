package com.novadrive.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeveloperSettingsStatusTest {
    @Test
    fun readySessionShowsCheckmark() {
        val lines = DeveloperSettingsStatus.lines(null, amapKey = true, wakeAppId = true, visionKey = true)
        assertEquals("语音会话：通义千问 Omni · Maia  ✓ 就绪", lines[0])
        assertEquals(listOf("高德 Web Key  ✓", "唤醒词 APPID  ✓", "看图 Key  ✓"), lines.drop(1))
    }

    @Test
    fun missingKeysShowCrossAndVisionIsOptional() {
        val lines = DeveloperSettingsStatus.lines(null, amapKey = false, wakeAppId = false, visionKey = false)
        assertEquals(listOf("高德 Web Key  ✗", "唤醒词 APPID  ✗", "看图 Key  可选"), lines.drop(1))
    }

    @Test
    fun qwenProblemShowsItsMessage() {
        val line = DeveloperSettingsStatus.sessionLine("QWEN_API_KEY_MISSING")
        assertTrue(line.contains("✗"))
        assertTrue(line.contains(QwenSettingsValidator.message("QWEN_API_KEY_MISSING")!!))
        assertTrue(!line.contains("就绪"))
    }

    @Test
    fun unknownCodeIsShownVerbatim() {
        assertTrue(DeveloperSettingsStatus.sessionLine("SOMETHING_ELSE").endsWith("✗ SOMETHING_ELSE"))
    }
}
