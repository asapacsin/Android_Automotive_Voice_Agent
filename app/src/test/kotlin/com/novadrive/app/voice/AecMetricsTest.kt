package com.novadrive.app.voice

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AecMetricsTest {
    @AfterEach
    fun tearDown() {
        AecMetrics.reset()
        VoiceAudioSession.aecBackend = "unavailable"
        VoiceAec.release()
    }

    @Test
    fun suppressesBargeInWhenPostAecFarBelowRaw() {
        VoiceAudioSession.aecBackend = "webrtc"
        AecMetrics.noteCapture(rawRms = 770.0, postAecRms = 151.0)
        assertFalse(AecMetrics.shouldFlushBargeIn())
    }

    @Test
    fun allowsBargeInWhenPostAecMatchesRaw() {
        VoiceAudioSession.aecBackend = "webrtc"
        AecMetrics.noteCapture(rawRms = 3645.0, postAecRms = 3293.0)
        assertTrue(AecMetrics.shouldFlushBargeIn())
    }

    @Test
    fun allowsBargeInWhenAecUnavailable() {
        AecMetrics.noteCapture(rawRms = 770.0, postAecRms = 151.0)
        assertTrue(AecMetrics.shouldFlushBargeIn())
    }
}
