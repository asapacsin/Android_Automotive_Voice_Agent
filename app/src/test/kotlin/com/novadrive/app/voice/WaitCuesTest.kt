package com.novadrive.app.voice

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Owner timing 2026-10-09: visual at 3 s, one progress line at 7 s, a different delay line at 12 s. */
class WaitCuesTest {
    private fun codes(turn: WaitCueTurn, at: Long) = generateSequence { turn.due(at) }.map { it.code }.toList()

    @Test
    fun nothingIsSpokenBeforeSevenSeconds() {
        val turn = WaitCueTurn()
        assertEquals(emptyList<String>(), codes(turn, 2_999))
        assertEquals(listOf("visual"), codes(WaitCueTurn(), 3_000))
        assertEquals(listOf("visual"), codes(WaitCueTurn(), 6_999))
    }

    @Test
    fun oneProgressLineAtSevenSecondsAndADifferentLineAtTwelve() {
        val turn = WaitCueTurn()
        assertEquals(listOf("visual", "progress"), codes(turn, 7_000))
        val delay = turn.due(12_000)!!
        assertEquals("delay", delay.code)
        assertEquals(WaitCues.DELAY, delay.text)
        org.junit.jupiter.api.Assertions.assertNotEquals(WaitCues.PROGRESS, delay.text)
        assertNull(turn.due(60_000))
    }

    @Test
    fun aLateWakeUpSaysTheDelayOnce() {
        assertEquals(listOf("visual", "delay"), codes(WaitCueTurn(), 13_000))
    }

    @Test
    fun aToolCallDoesNotAddASecondStatusLine() {
        val turn = WaitCueTurn().apply { onToolCall("query_live_info") }
        assertEquals(listOf("visual", "progress", "delay"), codes(turn, 7_000) + codes(turn, 12_000))
    }

    @Test
    fun usefulSpeechStopsEveryCue() {
        val turn = WaitCueTurn().apply { replyUnderway = true }
        assertNull(turn.due(30_000))
    }

    @Test
    fun suspiciousAudioGivesNoCue() {
        val turn = WaitCueTurn().apply { suspiciousAudio = true }
        assertNull(turn.due(30_000))
    }

    @Test
    fun clockSpeaksOnTheOwnerScheduleAndStopsWhenSheSpeaks() = runTest {
        val seen = mutableListOf<String>()
        val clock = WaitCueClock(this, { testScheduler.currentTime }, onVisual = { seen += if (it) "visual" else "visual_off" }) { cue, _ ->
            seen += cue.code
        }
        clock.onSpeech(false)
        advanceTimeBy(2_999)
        runCurrent()
        assertEquals(emptyList<String>(), seen)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("visual"), seen)
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(listOf("visual", "progress"), seen)
        clock.onUsefulSpeech()
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(listOf("visual", "progress", "visual_off"), seen)
    }
}
