package com.novadrive.app.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlaybackScopedVadTest {
    @Test
    fun playbackScopedThresholdPrefersNavigationThenPlayback() {
        assertEquals(BaiduFlexProtocol.DEFAULT_VAD_THRESHOLD, BaiduFlexProtocol.playbackScopedVadThreshold(false, false))
        assertEquals(BaiduFlexProtocol.PLAYBACK_VAD_THRESHOLD, BaiduFlexProtocol.playbackScopedVadThreshold(true, false))
        assertEquals(BaiduFlexProtocol.NAVIGATION_VAD_THRESHOLD, BaiduFlexProtocol.playbackScopedVadThreshold(true, true))
    }
}
