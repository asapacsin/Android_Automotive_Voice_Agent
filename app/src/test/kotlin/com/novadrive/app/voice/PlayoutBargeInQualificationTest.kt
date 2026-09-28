package com.novadrive.app.voice

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlayoutBargeInQualificationTest {
    @Test
    fun silenceDuringPlaybackDoesNotQualifyRegardlessOfBackend() {
        assertFalse(playoutBargeInQualified(recentSpeech = false))
    }

    @Test
    fun measuredSpeechQualifiesOnEveryBackend() {
        assertTrue(playoutBargeInQualified(recentSpeech = true))
    }
}
