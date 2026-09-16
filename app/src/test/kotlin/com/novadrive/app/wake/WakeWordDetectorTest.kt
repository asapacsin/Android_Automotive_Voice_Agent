package com.novadrive.app.wake

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WakeWordDetectorTest {
    // VoiceWakeuper needs the Android runtime and cannot be unit-tested on the JVM.
    // IflytekWakeWordDetector is therefore not instantiated here; this suite covers the
    // preserved WakeWordDetector interface plus JSON parsing of wake results.
    @Test
    fun fakeDetectorTransitionsIdleToListeningToIdleWithoutSdk() {
        val detector: WakeWordDetector = FakeWakeWordDetector()
        assertEquals(WakeWordState.IDLE, detector.state)
        detector.startListening()
        assertEquals(WakeWordState.LISTENING, detector.state)
        detector.writeFrame(ByteArray(1280), first = true, last = false)
        detector.stopListening()
        assertEquals(WakeWordState.IDLE, detector.state)
    }

    private class FakeWakeWordDetector : WakeWordDetector {
        override var state: WakeWordState = WakeWordState.IDLE
            private set

        override fun initialize(credentials: WakeWordCredentials, workDir: File) {
            state = WakeWordState.READY
        }

        override fun startListening() {
            state = WakeWordState.LISTENING
        }

        override fun writeFrame(pcm: ByteArray, first: Boolean, last: Boolean) = Unit

        override fun stopListening() {
            state = WakeWordState.IDLE
        }

        override fun release() {
            state = WakeWordState.IDLE
        }
    }

    @Test
    fun parseWakeResultJsonReadsIdAndScore() {
        val match = parseWakeResultJson("""{"sst":"wakeup","id":"0","score":1780,"bos":100,"eos":800}""")
        assertEquals("0", match?.id)
        assertEquals(1780, match?.score)
    }

    @Test
    fun parseWakeResultJsonRejectsEmptyAndInvalid() {
        assertNull(parseWakeResultJson(""))
        assertNull(parseWakeResultJson("{"))
        assertNull(parseWakeResultJson("""{"sst":"wakeup"}"""))
    }
}
