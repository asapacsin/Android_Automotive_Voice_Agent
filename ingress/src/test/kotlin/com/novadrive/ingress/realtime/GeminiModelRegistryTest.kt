package com.novadrive.ingress.realtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GeminiModelRegistryTest {
    @Test
    fun fastModelIsDeclaredFirstAndSelectable() {
        assertEquals(VoiceCatalog.GEMINI_LIVE_FAST, VoiceCatalog.geminiLiveModels.keys.first())
        assertTrue(VoiceCatalog.GEMINI_LIVE_EXTENDED in VoiceCatalog.geminiLiveModels)
        assertEquals(VoiceProviderId.GEMINI_LIVE, VoiceCatalog.providerForModel(VoiceCatalog.GEMINI_LIVE_FAST))
        assertEquals(VoiceCatalog.GEMINI_LIVE_FAST, VoiceCatalog.requireAllowed(VoiceProviderId.GEMINI_LIVE, VoiceCatalog.GEMINI_LIVE_FAST))
        assertEquals("gemini-3.8-live", VoiceCatalog.defaultModel(VoiceProviderId.GEMINI_LIVE))
        assertEquals(VoiceCatalog.GEMINI_LIVE_DEFAULT, VoiceCatalog.defaultModel(VoiceProviderId.GEMINI_LIVE))
        assertFalse(VoiceCatalog.geminiAcceptsThinkingLevel(VoiceCatalog.GEMINI_LIVE_DEFAULT))
    }

    @Test
    fun thinkingLevelTraitIsDeclaredPerModel() {
        assertTrue(VoiceCatalog.geminiAcceptsThinkingLevel(VoiceCatalog.GEMINI_LIVE_EXTENDED))
        assertFalse(VoiceCatalog.geminiAcceptsThinkingLevel(VoiceCatalog.GEMINI_LIVE_FAST))
        assertFalse(VoiceCatalog.geminiAcceptsThinkingLevel("unknown-model"))
        assertFalse(VoiceCatalog.geminiAcceptsThinkingLevel(""))
    }
}
