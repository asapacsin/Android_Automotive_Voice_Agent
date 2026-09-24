package com.novadrive.app.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LowLatencyPlaybackBufferTest {
    /** A track whose applied size is clamped to [capacityFrames], like AudioTrack's. */
    private class FakeTrack(var capacityFrames: Int = 10_000) : LowLatencyPlaybackBuffer.Port {
        override var underrunCount = 0
        val requests = mutableListOf<Int>()
        override fun setBufferSizeInFrames(frames: Int): Int {
            requests += frames
            return minOf(frames, capacityFrames)
        }
    }

    private val rate = 24_000
    private val framesPer10ms = rate / 100

    @Test
    fun initialBufferIsAtLeastTenMs() {
        val manager = LowLatencyPlaybackBuffer(sampleRateHz = rate, platformMinBufferBytes = 100)
        val bytes = manager.initialBufferBytes()
        assertTrue(bytes >= rate * 2 / 100)
        assertEquals((bytes * 1000) / (rate * 2), manager.bufferMs)
    }

    @Test
    fun resamplerDelayMatchesWebRtcKernel() {
        assertEquals(0, VoicePlayoutDelay.resamplerDelayMs(16_000))
        assertEquals(0, VoicePlayoutDelay.resamplerDelayMs(24_000))
    }

    @Test
    fun anUnderrunWhileWritingGrowsByTenMilliseconds() {
        val track = FakeTrack()
        val manager = LowLatencyPlaybackBuffer(rate, platformMinBufferBytes = framesPer10ms * 4)
        manager.reset(track)
        assertEquals(20, manager.bufferMs)
        track.underrunCount = 1
        manager.afterWrite(track)
        assertEquals(30, manager.bufferMs)
        assertEquals(1, manager.countedUnderruns)
    }

    @Test
    fun theSizeRecordedIsWhatThePlatformApplied() {
        // 20 ms applied; a 30 ms request is clamped by the platform to 24 ms.
        val track = FakeTrack(capacityFrames = framesPer10ms * 2 + 100)
        val manager = LowLatencyPlaybackBuffer(rate, platformMinBufferBytes = framesPer10ms * 4)
        manager.reset(track)
        track.underrunCount = 1
        manager.afterWrite(track)
        assertEquals(framesPer10ms * 3, track.requests.last(), "30 ms was requested")
        assertEquals(24, manager.bufferMs, "the clamped size is recorded, not the requested one")
    }

    @Test
    fun aGrowThePlatformRefusesCapsGrowthInsteadOfRetrying() {
        val track = FakeTrack(capacityFrames = framesPer10ms * 2)
        val manager = LowLatencyPlaybackBuffer(rate, platformMinBufferBytes = framesPer10ms * 4)
        manager.reset(track)
        track.underrunCount = 1
        manager.afterWrite(track)
        val attempts = track.requests.size
        track.underrunCount = 2
        manager.afterWrite(track)
        assertEquals(attempts, track.requests.size, "no request after the platform stopped growing")
        assertEquals(20, manager.bufferMs)
    }

    @Test
    fun runningDryWithNothingToWriteDoesNotGrowTheBuffer() {
        // Between replies the app has no audio; the track underruns by design.
        val track = FakeTrack()
        val manager = LowLatencyPlaybackBuffer(rate, platformMinBufferBytes = framesPer10ms * 4)
        manager.reset(track)
        track.underrunCount = 3
        manager.onStarved(track)
        manager.afterWrite(track)
        assertEquals(20, manager.bufferMs, "idle underruns are not the buffer's fault")
        assertEquals(0, manager.countedUnderruns)
        track.underrunCount = 4
        manager.afterWrite(track)
        assertEquals(30, manager.bufferMs, "an underrun after writing resumed still counts")
    }

    @Test
    fun growthStopsAfterFiveIncreases() {
        val track = FakeTrack()
        val manager = LowLatencyPlaybackBuffer(rate, platformMinBufferBytes = framesPer10ms * 4)
        manager.reset(track)
        repeat(8) {
            track.underrunCount += 1
            manager.afterWrite(track)
        }
        assertEquals(70, manager.bufferMs)
        assertEquals(8, manager.countedUnderruns)
    }
}
