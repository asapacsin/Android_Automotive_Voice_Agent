package com.novadrive.app.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** P45 F1: a turn's timing summary tells a stalled stream from a missing generationComplete. */
class GeminiTurnTimingTest {
    private var now = 0L
    private val timing = GeminiTurnTiming { now }

    @Test
    fun aStalledStreamShowsAsTheLargestGap() {
        now = 1_000; timing.open()
        now = 1_100; timing.message(hasAudio = true, hasText = true, generationComplete = false)
        now = 10_500; timing.message(hasAudio = false, hasText = false, generationComplete = false)
        assertEquals("dur_ms=9500 first_audio_ms=100 first_text_ms=100 gen_ms=-1 msgs=2 max_gap_ms=9400", timing.summary())
    }

    @Test
    fun aCompletedGenerationIsTimedFromTheOpen() {
        now = 0; timing.open()
        now = 200; timing.message(hasAudio = false, hasText = false, generationComplete = false)
        now = 450; timing.message(hasAudio = false, hasText = true, generationComplete = false)
        now = 1_800; timing.message(hasAudio = false, hasText = false, generationComplete = true)
        now = 4_000
        assertEquals("dur_ms=4000 first_audio_ms=-1 first_text_ms=450 gen_ms=1800 msgs=3 max_gap_ms=1350", timing.summary())
    }

    @Test
    fun reopeningStartsAFreshTurn() {
        now = 0; timing.open()
        now = 5_000; timing.message(hasAudio = false, hasText = true, generationComplete = true)
        now = 6_000; timing.open()
        now = 6_300
        assertEquals("dur_ms=300 first_audio_ms=-1 first_text_ms=-1 gen_ms=-1 msgs=0 max_gap_ms=0", timing.summary())
    }

    @Test
    fun nothingBeforeAnOpenIsCounted() {
        timing.message(hasAudio = false, hasText = true, generationComplete = true)
        assertEquals("dur_ms=-1", timing.summary())
    }
}
