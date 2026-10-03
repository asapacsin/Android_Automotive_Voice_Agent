package com.novadrive.app.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private fun NowPlayingVerifier.awaitBlocking(request: MusicRequest, previous: NowPlaying?) =
    kotlinx.coroutines.runBlocking { await(request, previous) }

class NowPlayingVerifierTest {
    private class FakeSource(val access: Boolean, val script: (Long) -> NowPlaying?) : NowPlayingSource {
        var t = 0L
        override fun hasAccess() = access
        override fun current() = script(t)
    }

    private val request = MusicRequest("oblivious", "Kalafina", "空之境界", null, null)

    private fun verifier(src: FakeSource) =
        NowPlayingVerifier(src, clock = { src.t }, sleep = { src.t += it }, timeoutMs = 6000, pollMs = 250)

    @Test fun noAccessIsUnverified() {
        val src = FakeSource(false) { error("must not poll") }
        assertEquals(PlaybackCheck.Unverified, verifier(src).awaitBlocking(request, null))
    }

    @Test fun newTrackPlaying() {
        val src = FakeSource(true) { t -> if (t >= 1000) NowPlaying("oblivious", "Kalafina", true, "com.netease.cloudmusic") else null }
        val v = verifier(src)
        val r = v.awaitBlocking(request, null)
        assertTrue(r is PlaybackCheck.Playing && r.matchesRequest)
        assertEquals(1000, v.lastWaitedMs)
    }

    @Test fun previousTrackIgnored() {
        val prev = NowPlaying("old song", "someone", true, "com.tencent.qqmusic")
        val src = FakeSource(true) { t -> if (t < 2000) prev else NowPlaying("oblivious", "Kalafina", true, "com.tencent.qqmusic") }
        val r = verifier(src).awaitBlocking(request, prev)
        assertTrue(r is PlaybackCheck.Playing && r.nowPlaying.title == "oblivious")
    }

    @Test fun previousOnlyTimesOut() {
        val prev = NowPlaying("old song", "someone", true, "com.tencent.qqmusic")
        val src = FakeSource(true) { prev }
        val v = verifier(src)
        assertEquals(PlaybackCheck.NotPlaying, v.awaitBlocking(request, prev))
        assertTrue(v.lastWaitedMs >= 6000)
    }

    @Test fun pausedDoesNotCount() {
        val src = FakeSource(true) { NowPlaying("oblivious", "Kalafina", false, "x") }
        assertEquals(PlaybackCheck.NotPlaying, verifier(src).awaitBlocking(request, null))
    }

    @Test fun mismatchReported() {
        val src = FakeSource(true) { NowPlaying("Lilium", "Kumiko Noma", true, "org.videolan.vlc") }
        val r = verifier(src).awaitBlocking(request, null)
        assertTrue(r is PlaybackCheck.Playing)
        assertFalse((r as PlaybackCheck.Playing).matchesRequest)
    }

    @Test fun logLineHasNoContent() {
        val line = NowPlayingVerifier.logLine(PlaybackCheck.Playing(NowPlaying("oblivious", "Kalafina", true, "p"), true), 750)
        assertEquals("music_verify result=playing match=true waited_ms=750", line)
    }
}
