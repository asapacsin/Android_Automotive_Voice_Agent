package com.novadrive.app.voice

import com.novadrive.app.voice.SpeechArbiter.Uplink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The `GuidanceMicGateTest` cases not already in `SpeechArbiterPairTest`, migrated to the arbiter (SPEC-012 step 3). */
class SpeechArbiterUplinkTest {
    private var now = 0L
    private val arbiter = SpeechArbiter(clock = { now })

    @Test
    fun closesAtOnceWhenGuidanceStarts() {
        arbiter.onGuidanceSpeaking(true)
        assertEquals(Uplink.CLOSED, arbiter.uplink())
    }

    @Test
    fun reopensOnlyAfterTheTail() {
        arbiter.onGuidanceSpeaking(true)
        arbiter.onGuidanceSpeaking(false)
        now += 499
        assertEquals(Uplink.CLOSED, arbiter.uplink(), "the echo of the last word must not be sent")
        now += 2
        assertEquals(Uplink.OPEN, arbiter.uplink())
    }

    @Test
    fun anEndWithoutAStartChangesNothing() {
        arbiter.onGuidanceSpeaking(false)
        assertEquals(Uplink.OPEN, arbiter.uplink())
        now += 100
        assertEquals(Uplink.OPEN, arbiter.uplink())
    }

    @Test
    fun resetOpensImmediately() {
        arbiter.onGuidanceSpeaking(true)
        arbiter.reset()
        assertEquals(Uplink.OPEN, arbiter.uplink())
    }
}
