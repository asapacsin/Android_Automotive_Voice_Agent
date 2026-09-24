package com.novadrive.app.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.sin

/**
 * Astra P4: speech over the assistant's playback is a barge-in only with time-scoped post-AEC
 * evidence, and a turn that began without it cannot become audible through the no-claim release.
 */
class BargeInEvidenceTest {
    private val samplesPerFrame = PcmAudioCapture.FRAME_BYTES / 2
    private val frameMs = SpeechUplinkGate.DEFAULT_FRAME_MS

    private fun tone(amplitude: Int): ByteArray {
        val out = ByteArray(samplesPerFrame * 2)
        for (i in 0 until samplesPerFrame) {
            val value = (amplitude * sin(2.0 * Math.PI * 220.0 * i / 16000.0)).toInt()
            out[i * 2] = (value and 0xff).toByte()
            out[i * 2 + 1] = ((value shr 8) and 0xff).toByte()
        }
        return out
    }

    private fun silence() = ByteArray(samplesPerFrame * 2)

    // ---- the gate's evidence ----

    @Test
    fun sustainedSpeechNowIsEvidence() {
        val gate = SpeechUplinkGate()
        repeat(SpeechUplinkGate.MIN_ONSET_FRAMES) { gate.offer(tone(6_000)) }
        assertTrue(gate.hasRecentSpeech())
    }

    @Test
    fun anEchoBurstThatEndedIsNoLongerEvidenceThoughTheGateIsStillOpen() {
        val gate = SpeechUplinkGate()
        repeat(SpeechUplinkGate.MIN_ONSET_FRAMES) { gate.offer(tone(6_000)) }
        repeat(SpeechUplinkGate.EVIDENCE_WINDOW_MS / frameMs) { gate.offer(silence()) }
        assertTrue(gate.isOpen, "the hangover keeps the gate open for 1.2 s")
        assertFalse(gate.hasRecentSpeech(), "but nobody has spoken for the whole evidence window")
    }

    @Test
    fun aBurstShorterThanAnOnsetIsNotEvidence() {
        val gate = SpeechUplinkGate()
        repeat(SpeechUplinkGate.MIN_ONSET_FRAMES - 1) { gate.offer(tone(6_000)) }
        assertFalse(gate.hasRecentSpeech())
    }

    @Test
    fun gatingTheMicrophoneClearsTheEvidence() {
        val gate = SpeechUplinkGate()
        repeat(SpeechUplinkGate.MIN_ONSET_FRAMES) { gate.offer(tone(6_000)) }
        gate.onCaptureInterrupted()
        assertFalse(gate.hasRecentSpeech(), "residual audio before the gating must not count")
    }

    // ---- the turn ----

    private val reply = "好的，已经为你把温度调到二十二度。"
    private val shortAudio = SpeechUplinkGate.Segment(durationMs = 1_500, voicedFrames = 140, peak = 9_000)

    private fun echoCandidate(): DriverTurn =
        DriverTurn(epoch = 1).also {
            it.onSpeechDuringPlayback(qualified = false)
            assertEquals(DriverTurn.HoldReason.ECHO_CANDIDATE, it.onResponseStarted(shortAudio, false))
            it.hold("audio")
        }

    @Test
    fun anUnconfirmedCandidateIsDroppedEvenWhenItsReplyClaimsNothing() {
        val t = echoCandidate()
        assertEquals(DriverTurn.Verdict.Wait, t.onAssistantText("嗯，好的。"))
        val verdict = t.onResponseDone("嗯，好的。", hadToolCallInResponse = false)
        assertEquals(DriverTurn.Verdict.Drop("unconfirmed_echo_candidate"), verdict)
    }

    @Test
    fun theAssistantsOwnWordsHeardBackDoNotConfirmTheTurn() {
        val t = echoCandidate()
        assertEquals(DriverTurn.HoldReason.ECHO_CANDIDATE, t.onUserTranscript("已经为你把温度调到二十二度", echoOf = reply) { DriverTurn.Kind.ACTION })
        assertFalse(t.userSpoke, "an echo is not the driver speaking")
        assertTrue(t.onResponseDone("好的。", hadToolCallInResponse = false) is DriverTurn.Verdict.Drop)
    }

    @Test
    fun theDriversOwnWordsConfirmTheTurnAndItIsJudgedNormally() {
        // Quiet double-talk: no acoustic evidence, but the recogniser heard a real request.
        val t = echoCandidate()
        val reason = t.onUserTranscript("帮我打开音乐", echoOf = reply) { DriverTurn.Kind.ACTION }
        assertFalse(t.echoCandidate)
        assertEquals(DriverTurn.HoldReason.AWAITING_EXECUTION_PROOF, reason, "an action still waits for proof")
    }

    @Test
    fun aToolCallOrProofConfirmsTheTurn() {
        val called = echoCandidate()
        called.onToolCall()
        assertTrue(called.onResponseDone("", hadToolCallInResponse = true) is DriverTurn.Verdict.Release)

        val proved = echoCandidate()
        assertTrue(proved.onExecutionResult(ok = true, failure = null) is DriverTurn.Verdict.Release)
    }

    @Test
    fun speechWithEvidenceOrWithoutPlaybackIsNotACandidate() {
        val qualified = DriverTurn(epoch = 1).also { it.onSpeechDuringPlayback(qualified = true) }
        assertFalse(qualified.echoCandidate)
        assertTrue(qualified.onResponseStarted(shortAudio, false) != DriverTurn.HoldReason.ECHO_CANDIDATE)
    }

    @Test
    fun echoMatchingComparesWordsOnlyAgainstWhatWasJustSaid() {
        assertTrue(DriverTurn.isEchoOf("已经为你把温度调到二十二度。", reply))
        assertTrue(DriverTurn.isEchoOf("温度调到二十二", reply), "a fragment of the reply")
        assertTrue(DriverTurn.isEchoOf("已经为你把温度掉到二十二度", reply), "recognition is not verbatim")
        assertFalse(DriverTurn.isEchoOf("帮我打开音乐", reply))
        assertFalse(DriverTurn.isEchoOf("帮我打开音乐", ""), "nothing said, nothing echoed")
        assertFalse(DriverTurn.isEchoOf("好", reply), "one character is no evidence either way")
    }

    // ---- wiring ----

    @Test
    fun theClientMarksSpeechOverPlaybackWithTheAppsOwnEvidence() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val client = File(root, "app/src/main/kotlin/com/novadrive/app/voice/BaiduFlexClient.kt").readText()
        assertTrue(client.contains("turn.onSpeechDuringPlayback(qualified = speechEvidence())"))
        assertTrue(client.contains("echoOf = lastSpokenReply"))
    }
}
