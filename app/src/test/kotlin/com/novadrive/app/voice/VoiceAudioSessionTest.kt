package com.novadrive.app.voice

import android.media.AudioManager
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VoiceAudioSessionTest {
    @Test
    fun sessionsMatchRequiresBothIdsAndEquality() {
        VoiceAudioSession.recordCaptureSession(42)
        VoiceAudioSession.recordPlaybackSession(AudioManager.AUDIO_SESSION_ID_GENERATE)
        assertFalse(VoiceAudioSession.sessionsMatch())
        VoiceAudioSession.recordPlaybackSession(42)
        assertTrue(VoiceAudioSession.sessionsMatch())
        VoiceAudioSession.recordPlaybackSession(43)
        assertFalse(VoiceAudioSession.sessionsMatch())
    }
}
