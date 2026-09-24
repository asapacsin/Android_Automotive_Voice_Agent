package com.novadrive.app.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.sin

/**
 * The pre-model gate. Every case here is one of the acceptance rows for the open-mic problem:
 * impulses must not reach the model, and real speech — quiet, slow, hesitant — must.
 */
class SpeechUplinkGateTest {
    private val samplesPerFrame = PcmAudioCapture.FRAME_BYTES / 2

    /** A frame of tone at [amplitude]; a stand-in for voiced energy. */
    private fun tone(amplitude: Int, samples: Int = samplesPerFrame): ByteArray {
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val value = (amplitude * sin(2.0 * Math.PI * 220.0 * i / 16000.0)).toInt()
            out[i * 2] = (value and 0xff).toByte()
            out[i * 2 + 1] = ((value shr 8) and 0xff).toByte()
        }
        return out
    }

    private fun silence(samples: Int = samplesPerFrame) = ByteArray(samples * 2)

    private fun SpeechUplinkGate.feed(vararg frames: ByteArray): List<ByteArray> =
        frames.flatMap { offer(it).send }

    private fun SpeechUplinkGate.openWithSpeech(frames: Int = SpeechUplinkGate.MIN_ONSET_FRAMES) {
        repeat(frames) { offer(tone(6_000)) }
        assertTrue(isOpen, "expected gate open after $frames voiced frames")
    }

    @Test
    fun aSingleLoudImpulseNeverReachesTheModel() {
        val gate = SpeechUplinkGate()
        // A tap: one capture frame, very loud, then the room again.
        val sent = gate.feed(silence(), tone(20_000), silence(), silence())
        assertTrue(sent.isEmpty(), "a tap must not be uploaded at all")
        assertFalse(gate.isOpen)
    }

    @Test
    fun anImpulseIsReportedSoItCanBeCounted() {
        val gate = SpeechUplinkGate()
        gate.offer(silence())
        gate.offer(tone(20_000))
        val decision = gate.offer(silence())
        val rejection = requireNotNull(decision.rejected) { "the tap should be reported" }
        assertEquals(SpeechUplinkGate.DEFAULT_FRAME_MS, rejection.durationMs)
    }

    @Test
    fun sustainedSpeechOpensTheGateAndKeepsStreaming() {
        val gate = SpeechUplinkGate()
        gate.offer(silence())
        repeat(SpeechUplinkGate.MIN_ONSET_FRAMES - 1) {
            assertTrue(gate.offer(tone(6_000)).send.isEmpty(), "frame $it is not yet an onset")
        }
        val opened = gate.offer(tone(6_000)).send
        assertTrue(gate.isOpen)
        // Pre-roll is flushed with the onset, so the first syllable survives.
        assertTrue(opened.size >= 2, "expected pre-roll + onset, got ${opened.size}")
        assertEquals(1, gate.offer(tone(6_000)).send.size)
    }

    @Test
    fun quietSpeechStillOpensTheGate() {
        val gate = SpeechUplinkGate()
        // Quiet speech measured on this device peaks ~1300-1900 before MicInputGain lifts it.
        repeat(4) { gate.offer(silence()) }
        repeat(SpeechUplinkGate.MIN_ONSET_FRAMES - 1) { gate.offer(tone(1_400)) }
        assertTrue(gate.offer(tone(1_400)).send.isNotEmpty(), "quiet speech must not be gated out")
        assertTrue(gate.isOpen)
    }

    @Test
    fun aNaturalPauseDoesNotCloseTheGate() {
        val gate = SpeechUplinkGate()
        gate.openWithSpeech()
        // 「导航去……呃……珠海站」 — up to a second of thinking, then the rest of the sentence.
        repeat(100) { gate.offer(silence()) }
        assertTrue(gate.isOpen, "a 1 s pause must not end the upload")
        assertEquals(1, gate.offer(tone(6_000)).send.size, "the continuation streams without a new onset")
    }

    @Test
    fun theGateKeepsSendingSilenceLongerThanTheServerNeedsToEndATurn() {
        // The server ends a turn after 200 ms of silence. If the gate stopped sending before
        // that, a turn would never be committed and the driver would get no answer at all.
        val gate = SpeechUplinkGate()
        gate.openWithSpeech()
        var silentMs = 0
        while (gate.isOpen) {
            gate.offer(silence())
            silentMs += SpeechUplinkGate.DEFAULT_FRAME_MS
            if (silentMs > 5_000) break
        }
        assertTrue(silentMs >= 1_200, "hangover was only $silentMs ms; the server needs 200 ms plus margin")
    }

    @Test
    fun aClosedSegmentReportsItsShape() {
        val gate = SpeechUplinkGate()
        // 1.2 s: the length real commands measured on the device (「关闭音乐」 1.3 s, 「第二个」 0.9 s).
        repeat(120) { gate.offer(tone(6_000)) }
        var finished: SpeechUplinkGate.Segment? = null
        repeat(150) { if (finished == null) finished = gate.offer(silence()).finished }
        val segment = requireNotNull(finished)
        assertTrue(segment.durationMs >= 1_200, "expected the speech to be counted, got ${segment.durationMs} ms")
        assertTrue(segment.voicedFrames >= 110)
        assertFalse(segment.isSuspicious(), "a spoken sentence is not suspicious")
        assertFalse(segment.needsHold(), "and its reply is never delayed")
    }

    @Test
    fun aShortBurstThatDoesOpenTheGateIsMarkedSuspicious() {
        // A cough can sustain past the onset. It reaches the model — and the phantom-turn gate is
        // what stops it becoming an audible reply.
        val gate = SpeechUplinkGate()
        gate.openWithSpeech()
        var finished: SpeechUplinkGate.Segment? = null
        repeat(150) { if (finished == null) finished = gate.offer(silence()).finished }
        assertTrue(requireNotNull(finished).isSuspicious(), "a 200 ms burst must be flagged")
    }

    /**
     * Device, 2026-09-18: three taps spread over three seconds formed one 43 %-voiced segment that
     * was not held, and the assistant's 「没听清」 was spoken. Continuous speech measured 75–100 %.
     */
    @Test
    fun aLongMostlySilentStretchIsHeldForJudgement() {
        val burst = SpeechUplinkGate.Segment(durationMs = 3_000, voicedFrames = 130, peak = 20_345)
        assertTrue(burst.needsHold(), "43% voiced over 3 s must be held")
        val sentence = SpeechUplinkGate.Segment(durationMs = 3_000, voicedFrames = 260, peak = 6_000)
        assertFalse(sentence.needsHold(), "continuous speech must never be delayed")
    }

    @Test
    fun residualAudioAfterTheAssistantSpeaksCannotBuildAnOnset() {
        val gate = SpeechUplinkGate()
        gate.offer(tone(6_000))
        // The mic was gated because the assistant started talking; the tail is not an onset.
        gate.onCaptureInterrupted()
        assertTrue(gate.offer(tone(6_000)).send.isEmpty(), "the counter must restart after gating")
    }

    @Test
    fun theGateAdaptsToANoisyRoomInsteadOfUsingOneFixedNumber() {
        val quiet = SpeechUplinkGate()
        repeat(30) { quiet.offer(tone(400)) } // steady hum
        // The hum has taught the floor; a voice must now clear the hum, not the absolute minimum.
        assertTrue(quiet.voicedThreshold > SpeechUplinkGate.ABSOLUTE_RMS_FLOOR)
    }
}
