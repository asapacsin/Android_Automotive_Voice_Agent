package com.novadrive.ingress.realtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MockRealtimeVoiceProviderTest {
    @Test
    fun mockNeverUsesBaiduEndpointAndCanInterrupt() {
        val provider = MockRealtimeVoiceProvider()
        assertEquals("mock.realtime", provider.providerId)
        provider.connect(VoiceModels.DEFAULT)
        provider.sendAudio(ByteArray(2000))
        val events = provider.receiveEvents()
        assertTrue(events.any { it is DomainVoiceEvent.SessionReady })
        assertTrue(events.any { it is DomainVoiceEvent.AudioDelta })
        val interrupted = provider.interrupt()
        assertTrue(interrupted is DomainVoiceEvent.Interrupted)
        provider.close()
        assertTrue(provider.closed)
        assertTrue(provider.sentAudioBytes >= 2000)
        assertFalse(provider.providerId.contains("baidu"))
    }

    @Test
    fun defaultModelIsQwenFlashAndBaiduLiteNearRemainsSelectable() {
        assertEquals("qwen-audio-3.0-realtime-flash", VoiceModels.DEFAULT)
        assertEquals(VoiceProviderId.QWEN, VoiceCatalog.DEFAULT_PROVIDER)
        assertEquals("audio-mini-realtime-near", VoiceCatalog.defaultModel(VoiceProviderId.BAIDU))
        assertEquals("Lite Near", VoiceCatalog.baiduModels[VoiceModels.LITE_NEAR])
        assertEquals("Lite Far", VoiceCatalog.baiduModels[VoiceModels.LITE_FAR])
        assertEquals("Pro Near", VoiceCatalog.baiduModels[VoiceModels.PRO_NEAR])
        assertEquals("Pro Far", VoiceCatalog.baiduModels[VoiceModels.PRO_FAR])
        assertThrows<IllegalArgumentException> { VoiceModels.requireAllowed("whisper-1") }
        VoiceModels.requireAllowed(VoiceModels.PRO_NEAR)
        VoiceModels.requireAllowed(VoiceCatalog.QWEN_PLUS)
        VoiceModels.requireAllowed(VoiceCatalog.GPT_LIVE_1)
        VoiceModels.requireAllowed(VoiceCatalog.FAKE_MODEL)
    }

    @Test
    fun baiduCapabilitiesDoNotClaimFunctionCalling() {
        assertFalse(BaiduRealtimeCapabilities.CUSTOM_FUNCTION_CALLING)
        assertTrue(BaiduRealtimeCapabilities.EVIDENCE.contains("https://cloud.baidu.com/doc/SPEECH/s/nmcytnwei"))
        assertTrue(BaiduRealtimeCapabilities.EVIDENCE.contains("2026-09-04"))
        assertTrue(BaiduRealtimeCapabilities.EVIDENCE.contains("UpdateSession"))
        assertTrue(BaiduRealtimeCapabilities.EVIDENCE.contains("tool_choice"))
        assertEquals("BLOCKED_BAIDU_FUNCTION_CALLING", BaiduRealtimeCapabilities.BLOCKED_CODE)
    }
}
