package com.novadrive.app

import com.novadrive.app.media.HandoffResult
import com.novadrive.app.media.MusicHandoffTool
import com.novadrive.app.media.MusicRequest
import com.novadrive.app.media.NowPlaying
import com.novadrive.app.media.NowPlayingSource
import com.novadrive.app.media.NowPlayingVerifier
import com.novadrive.app.media.PlaybackCheck
import com.novadrive.app.tools.MediaServer
import com.novadrive.app.vehicle.ClimateToolHandler
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.simulator.SimulatedVehicleControl
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** SPEC-017: `play_music` results are built only from what is actually playing (I-1). */
class MediaServerPlayMusicTest {

    private class Executor : AndroidActionExecutor {
        var bundledPlays = 0
        override fun navigate(destination: String) = AndroidActionResult.Accepted()
        override fun openApp(app: AllowedApp) = AndroidActionResult.Accepted()
        override fun playMusic(): AndroidActionResult { bundledPlays++; return AndroidActionResult.Accepted("music_playing") }
        override fun stopMusic() = AndroidActionResult.Accepted("music_stopped")
        override fun exitNavigationMode() = AndroidActionResult.Accepted()
    }

    /** Scripted session source; the real verifier decides, with a fake clock. */
    private class FakeSource(var access: Boolean, var sequence: List<NowPlaying?>) : NowPlayingSource {
        private var i = 0
        override fun hasAccess() = access
        override fun current(): NowPlaying? = sequence.getOrNull(i.coerceAtMost(sequence.size - 1)).also { i++ }
    }

    private class FakeTool(
        val source: FakeSource,
        val before: NowPlaying? = null,
        val handoff: HandoffResult = HandoffResult.Sent("org.videolan.vlc"),
    ) : MusicHandoffTool {
        var now = 0L
        var handedOff: MusicRequest? = null
        private val verifier = NowPlayingVerifier(source, { now }, { now += it })
        override fun snapshot() = if (source.access) before else null
        override fun handOff(request: MusicRequest): HandoffResult { handedOff = request; return handoff }
        override fun await(request: MusicRequest, previous: NowPlaying?): PlaybackCheck = verifier.await(request, previous)
        override val lastWaitedMs: Long get() = verifier.lastWaitedMs
    }

    private val executor = Executor()

    @BeforeEach
    fun resetHint() = MediaServer.resetHintForTest()

    private fun run(tool: MusicHandoffTool?, args: Map<String, String>): JSONObject {
        val dispatcher = AndroidToolDispatcher(
            executor, ClimateToolHandler(SimulatedVehicleControl()), noCamera(), music = tool,
        ) { null }
        val result = dispatcher.dispatch(DomainVoiceEvent.ToolCall("c1", "play_music", args))
        val text = result.deferredOutput?.let { runBlocking { it() } } ?: result.output!!
        return JSONObject(text)
    }

    private val kajiura = mapOf("title" to "oath sign", "artist" to "LiSA", "album_or_work" to "Fate/Zero", "query" to "梶浦的很燃的OP")

    @Test
    fun playingAndMatchingAnnouncesFromNowPlaying() {
        val tool = FakeTool(FakeSource(true, listOf(null, NowPlaying("oath sign", "LiSA", true, "org.videolan.vlc"))))
        val out = run(tool, kajiura)
        assertTrue(out.getBoolean("ok"))
        assertEquals("playing", out.getString("status"))
        assertTrue(out.getBoolean("matches_request"))
        assertEquals("oath sign", out.getJSONObject("now_playing").getString("title"))
        assertEquals("org.videolan.vlc", out.getJSONObject("now_playing").getString("app"))
        assertEquals("在放LiSA的《oath sign》", out.getString("announce"))
        assertEquals("Fate/Zero", tool.handedOff!!.albumOrWork)
        assertEquals(0, executor.bundledPlays)
    }

    @Test
    fun playingSomethingElseSaysItIsNotSure() {
        val tool = FakeTool(FakeSource(true, listOf(NowPlaying("to the beginning", "Kalafina", true, "com.netease.cloudmusic"))))
        val out = run(tool, kajiura)
        assertTrue(out.getBoolean("ok"))
        assertFalse(out.getBoolean("matches_request"))
        val announce = out.getString("announce")
        assertTrue(announce.contains("不确定"), announce)
        assertTrue(announce.contains("《to the beginning》"), announce)
        assertFalse(out.toString().contains("oath sign"), "the requested title is never claimed")
    }

    @Test
    fun missingArtistIsOmittedGracefully() {
        assertEquals("在放《x》", MediaServer.announce(NowPlaying("x", null, true, null), true))
    }

    @Test
    fun unverifiedNamesNoSong() {
        val tool = FakeTool(FakeSource(false, listOf(NowPlaying("oath sign", "LiSA", true, "p"))))
        val out = run(tool, kajiura)
        assertTrue(out.getBoolean("ok"))
        assertEquals("requested_unverified", out.getString("status"))
        assertFalse(out.has("now_playing"))
        assertFalse(out.has("announce"))
        listOf("oath sign", "LiSA", "Fate", "梶浦").forEach { assertFalse(out.toString().contains(it), it) }
        assertTrue(out.getString("hint").contains("通知使用权"))
        assertFalse(run(tool, kajiura).has("hint"), "the hint is shown once per process")
    }

    @Test
    fun nothingStartingIsNotPlaying() {
        val out = run(FakeTool(FakeSource(true, listOf(null))), kajiura)
        assertFalse(out.getBoolean("ok"))
        assertEquals("NOT_PLAYING", out.getString("error"))
        assertTrue(out.getString("next").contains("没放成"))
    }

    @Test
    fun theTrackAlreadyPlayingIsNotSuccess() {
        val old = NowPlaying("oath sign", "LiSA", true, "org.videolan.vlc")
        val out = run(FakeTool(FakeSource(true, listOf(old)), before = old), kajiura)
        assertFalse(out.getBoolean("ok"))
        assertEquals("NOT_PLAYING", out.getString("error"))
    }

    @Test
    fun noAppAndRejectedAndNoToolAreHonestFailures() {
        val noApp = run(FakeTool(FakeSource(true, listOf(null)), handoff = HandoffResult.NoApp), kajiura)
        assertEquals("NO_MUSIC_APP", noApp.getString("error"))
        assertTrue(noApp.getString("next").contains("没有能播放的音乐 app"))
        val rejected = run(FakeTool(FakeSource(true, listOf(null)), handoff = HandoffResult.Rejected("security")), kajiura)
        assertEquals("HANDOFF_REJECTED", rejected.getString("error"))
        val none = run(null, kajiura)
        assertFalse(none.getBoolean("ok"))
        assertEquals("MUSIC_HANDOFF_UNAVAILABLE", none.getString("error"))
        assertEquals(0, executor.bundledPlays)
    }

    @Test
    fun theLogLineCarriesNoTitle() {
        val line = NowPlayingVerifier.logLine(PlaybackCheck.Playing(NowPlaying("oath sign", "LiSA", true, "p"), true), 250)
        assertFalse(line.contains("oath"))
    }
}
