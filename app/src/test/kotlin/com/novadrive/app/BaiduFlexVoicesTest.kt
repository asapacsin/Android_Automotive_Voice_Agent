package com.novadrive.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Product voice default is a youthful sweet female Flex id, not Baidu's stock "default".
 * Catalog entries are Baidu TTS/大模型 per ids that Flex may accept as session.voice;
 * unsupported ids still fall back to "default" in [com.novadrive.app.voice.BaiduFlexClient].
 */
class BaiduFlexVoicesTest {
    @Test
    fun productDefaultIsYouthfulSweetFemaleLargeModelVoice() {
        assertEquals("4196", BaiduAppSettings.DEFAULT_VOICE)
        assertEquals("4196", BaiduFlexVoices.PREFERRED_YOUTHFUL_FEMALE)
        assertEquals(BaiduFlexVoices.PREFERRED_YOUTHFUL_FEMALE, BaiduAppSettings().voice)
    }

    @Test
    fun catalogListsCuteYoungFemaleOptionsBeforeStockDefault() {
        val ids = BaiduFlexVoices.CATALOG.map { it.id }
        assertTrue(ids.indexOf("4196") < ids.indexOf("default"))
        assertTrue(ids.contains("4103")) // 度米朵-可爱女声
        assertTrue(ids.contains("6562")) // 度雨楠-元气少女
        assertEquals("度清影-甜美女声", BaiduFlexVoices.labelFor("4196"))
    }
}
