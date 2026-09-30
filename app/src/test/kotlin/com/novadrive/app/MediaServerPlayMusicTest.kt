package com.novadrive.app

import com.novadrive.app.media.HandoffResult
import com.novadrive.app.media.MusicHandoffTool
import com.novadrive.app.media.MusicRequest
import com.novadrive.app.media.NowPlaying
import com.novadrive.app.media.NowPlayingSource
import com.novadrive.app.media.NowPlayingVerifier
import com.novadrive.app.media.PauseResult
import com.novadrive.app.media.PlaybackCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
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
        var bundledStops = 0
        override fun navigate(destination: String) = AndroidActionResult.Accepted()
        override fun openApp(app: AllowedApp) = AndroidActionResult.Accepted()
        override fun playMusic(): AndroidActionResult { bundledPlays++; return AndroidActionResult.Accepted("music_playing") }
        override fun stopMusic(): AndroidActionResult { bundledStops++; return AndroidActionResult.Accepted("music_stopped") }
        override fun exitNavigationMode() = AndroidActionResult.Accepted()
    }

    /** Scripted session source; the real verifier decides, with a fake clock. */
    private class FakeSource(var access: Boolean, var sequence: List<NowPlaying?>) : NowPlayingSource {
        private var i = 0
        override fun hasAccess() = access
        override fun current(): NowPlaying? = sequence.getOrNull(i.coerceAtMost(sequence.size - 1)).also { i++ }
    }

    private open class FakeTool(
        val source: FakeSource,
        val before: NowPlaying? = null,
        val handoff: HandoffResult = HandoffResult.Sent("org.videolan.vlc"),
        val pause: PauseResult = PauseResult.NothingPlaying,
        val stopsWithin: Boolean = true,
        override val handedOff: Boolean = false,
    ) : MusicHandoffTool {
        var now = 0L
        var lastRequest: MusicRequest? = null
        var pauses = 0
        private val verifier = NowPlayingVerifier(source, { now }, { now += it })
        override fun snapshot() = if (source.access) before else null
        override fun handOff(request: MusicRequest): HandoffResult { lastRequest = request; return handoff }
        override suspend fun await(request: MusicRequest, previous: NowPlaying?): PlaybackCheck = verifier.await(request, previous)
        override val lastWaitedMs: Long get() = verifier.lastWaitedMs
        override fun pauseActive(): PauseResult { pauses++; return pause }
        override suspend fun awaitStopped() = stopsWithin
    }

    private val executor = Executor()

    @BeforeEach
    fun resetHint() = MediaServer.resetHintForTest()

    private fun dispatcher(tool: MusicHandoffTool?) = AndroidToolDispatcher(
        executor, ClimateToolHandler(SimulatedVehicleControl()), noCamera(), music = tool,
    ) { null }

    private fun run(tool: MusicHandoffTool?, args: Map<String, String>, name: String = "play_music"): JSONObject {
        val result = dispatcher(tool).dispatch(DomainVoiceEvent.ToolCall("c1", name, args))
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
        assertEquals("Fate/Zero", tool.lastRequest!!.albumOrWork)
        assertEquals(0, executor.bundledPlays)
        assertEquals(1, executor.bundledStops, "the bundled track is paused before the hand-off")
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

    // ---- R1: the readback never runs on the event loop -----------------------------------------

    private class GatedTool(val gate: CompletableDeferred<Unit>) :
        FakeTool(FakeSource(true, listOf(null))) {
        @Volatile var awaitThread: String? = null
        @Volatile var cancelled = false
        override suspend fun await(request: MusicRequest, previous: NowPlaying?): PlaybackCheck {
            awaitThread = Thread.currentThread().name
            try {
                gate.await()
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
            return PlaybackCheck.Unverified
        }
    }

    private fun mainDispatcher() = Executors.newSingleThreadExecutor { Thread(it, "fake-main") }.asCoroutineDispatcher()

    @Test
    fun theReadbackLeavesTheMainDispatcherFree() {
        val main = mainDispatcher()
        val tool = GatedTool(CompletableDeferred())
        val deferred = dispatcher(tool).dispatch(DomainVoiceEvent.ToolCall("c1", "play_music", kajiura)).deferredOutput!!
        runBlocking {
            val job = kotlinx.coroutines.CoroutineScope(main).async { deferred() }
            // The event loop still runs other work while the readback waits.
            kotlinx.coroutines.withTimeout(2000) { kotlinx.coroutines.withContext(main) { "free" } }
            tool.gate.complete(Unit)
            val out = JSONObject(job.await())
            assertEquals("requested_unverified", out.getString("status"))
        }
        assertTrue(tool.awaitThread != null && tool.awaitThread != "fake-main", "await ran on ${tool.awaitThread}")
        main.close()
    }

    @Test
    fun theReadbackIsCancellable() {
        val main = mainDispatcher()
        val tool = GatedTool(CompletableDeferred())
        val deferred = dispatcher(tool).dispatch(DomainVoiceEvent.ToolCall("c1", "play_music", kajiura)).deferredOutput!!
        runBlocking {
            val job = kotlinx.coroutines.CoroutineScope(main).launch { deferred() }
            kotlinx.coroutines.withTimeout(2000) { while (tool.awaitThread == null) kotlinx.coroutines.delay(10) }
            job.cancel()
            job.join()
        }
        assertTrue(tool.cancelled)
        main.close()
    }

    // ---- R3: stop after a hand-off ---------------------------------------------------------------

    @Test
    fun stopReportsStoppedOnlyWhenTheReadbackShowsNothingPlaying() {
        val paused = FakeTool(FakeSource(true, listOf(null)), pause = PauseResult.PauseSent, stopsWithin = true)
        val ok = run(paused, mapOf("action" to "stop"), "control_music")
        assertTrue(ok.getBoolean("ok"))
        assertEquals("music_stopped", ok.getString("status"))
        assertEquals(1, paused.pauses)

        val still = FakeTool(FakeSource(true, listOf(null)), pause = PauseResult.PauseSent, stopsWithin = false)
        val fail = run(still, mapOf("action" to "stop"), "control_music")
        assertFalse(fail.getBoolean("ok"))
        assertEquals("MUSIC_STILL_PLAYING", fail.getString("error"))
        assertTrue(fail.getString("next").contains("还没停"))
    }

    @Test
    fun stopWithoutListenerAccessAfterAHandOffDoesNotClaimTheAppStopped() {
        val tool = FakeTool(FakeSource(false, listOf(null)), pause = PauseResult.NoAccess, handedOff = true)
        val out = run(tool, mapOf("action" to "stop"), "control_music")
        assertTrue(out.getBoolean("ok"))
        assertEquals(MediaServer.STOP_UNVERIFIED, out.getString("status"))
        assertTrue(executor.bundledStops >= 1)
        val never = run(FakeTool(FakeSource(false, listOf(null)), pause = PauseResult.NoAccess), mapOf("action" to "stop"), "control_music")
        assertEquals("music_stopped", never.getString("status"), "no hand-off: only the bundled track could be playing")
    }
}
