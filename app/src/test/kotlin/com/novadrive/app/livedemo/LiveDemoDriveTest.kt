package com.novadrive.app.livedemo

import com.novadrive.app.DebugVoiceLog
import com.novadrive.app.NavigationState
import com.novadrive.app.voice.SpeechArbiter
import com.novadrive.app.voice.SpeechAuthority
import com.novadrive.ingress.realtime.VoiceUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The drive scene's wiring, offline: the harness's player, microphone and Amap-guidance adapters
 * against the app's own SpeechAuthority / SpeechArbiter, and HengqinWorld's guidance and manoeuvre
 * feed. No network, no model: the live run (LiveDemoRun, NOVA_LIVE_DEMO=1) is separate.
 */
class LiveDemoDriveTest {
    @TempDir lateinit var dir: File
    private lateinit var scope: CoroutineScope
    private lateinit var timeline: LiveDemoTimeline
    private lateinit var playback: LiveDemoTimeline.Playback
    private lateinit var rules: DriveSpeechRules
    private val logged = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        DebugVoiceLog.sink = { synchronized(logged) { logged += it } }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        timeline = LiveDemoTimeline(dir, dir).apply { start() }
        playback = timeline.Playback(RATE)
        rules = DriveSpeechRules(playback, scope)
        NavigationState.reset()
    }

    @AfterEach
    fun tearDown() {
        NavigationState.reset()
        rules.close()
        scope.cancel()
        DebugVoiceLog.sink = null
    }

    private fun chunk(seconds: Double = 0.2) = ByteArray((seconds * RATE).toInt() * 2)

    /** (a) R1: a reply arriving while Amap speaks is held, not dropped, and plays when the prompt ends. */
    @Test
    fun replyDuringGuidanceIsHeldThenPlays() {
        NavigationState.begin()
        rules.onUserFinalTranscript("今天温度怎么样啊。")
        rules.guidanceListener(true)
        rules.port.enqueue(chunk(), 0)
        assertTrue(timeline.replyHeld, "held behind the prompt")
        assertFalse(playback.playing, "nothing audible while Amap speaks")
        Thread.sleep(150)
        val promptEnd = timeline.now()
        rules.guidanceListener(false)
        assertFalse(timeline.replyHeld)
        assertTrue(playback.playing, "the held reply plays once the prompt ended")
        val hold = timeline.holds.getJSONObject(0)
        assertTrue(hold.getBoolean("played"))
        assertTrue(hold.getDouble("end") >= promptEnd)
        assertTrue(logged.any { it == "speech_arbiter out=reply decision=HOLD reason=guidance_or_focus" }, logged.toString())
    }

    /** (a) A reply already playing when a prompt starts is paused; its unheard rest plays afterwards. */
    @Test
    fun replyPlayingWhenGuidanceStartsIsPausedAndResumed() {
        NavigationState.begin()
        rules.onUserFinalTranscript("今天温度怎么样啊。")
        rules.port.enqueue(chunk(1.0), 0)
        assertTrue(playback.playing)
        Thread.sleep(100)
        rules.guidanceListener(true)
        assertFalse(playback.playing)
        assertTrue(timeline.replyHeld)
        rules.guidanceListener(false)
        assertTrue(playback.playing)
        assertTrue(playback.queuedFrames > RATE / 2, "the rest of the reply was kept, not cut")
    }

    /** (b) R1-R3: the microphone sends nothing while the prompt plays and for the 500 ms tail. */
    @Test
    fun micIsClosedWhileGuidancePlays() {
        assertFalse(rules.uplinkClosed())
        rules.guidanceListener(true)
        assertTrue(rules.uplinkClosed())
        rules.guidanceListener(false)
        assertTrue(rules.uplinkClosed(), "the tail")
        Thread.sleep(SpeechArbiter.TAIL_MS + 100)
        assertFalse(rules.uplinkClosed())
    }

    /** (b) R0: a prompt that starts mid-utterance does not cut the driver off; it closes when he stops. */
    @Test
    fun driverAlreadySpeakingKeepsTheMicOpen() {
        rules.onUiState(VoiceUiState.USER_SPEAKING)
        rules.guidanceListener(true)
        assertFalse(rules.uplinkClosed(), "R0: the utterance in progress keeps the uplink")
        rules.onUiState(VoiceUiState.THINKING)
        assertTrue(rules.uplinkClosed(), "closed once the driver stopped")
        rules.guidanceListener(false)
    }

    /** (c) P1: while navigating, a reply nobody asked for is dropped; one right after a request plays. */
    @Test
    fun unpromptedReplyIsDroppedWhileNavigating() {
        NavigationState.begin()
        rules.port.enqueue(chunk(), 0)
        assertFalse(playback.playing)
        assertFalse(timeline.replyHeld)
        assertEquals(0L, timeline.replyBytes, "dropped, never queued")
        assertTrue(logged.contains("reply_audio_not_played reason=navigation_unprompted"), logged.toString())

        rules.onUserFinalTranscript("前面堵不堵")
        rules.port.enqueue(chunk(), 0)
        assertTrue(playback.playing, "the answer to the driver's request plays")
    }

    /** (c) Noise is not a request: it does not open the window. */
    @Test
    fun noiseDoesNotOpenTheWindow() {
        NavigationState.begin()
        rules.onUserFinalTranscript("嗯")
        rules.port.enqueue(chunk(), 0)
        assertFalse(playback.playing)
    }

    /** (d) The prompts come in order at their metres, and the manoeuvre distance reaches NavigationState. */
    @Test
    fun worldEmitsPromptsAndFeedsTheManeuverDistance() {
        val world = HengqinWorld().apply { speedKmh = 0.0 }
        try {
            val emitted = mutableListOf<Pair<String, Int>>()
            val distances = mutableListOf<Int?>()
            world.onPrompt = { key -> emitted += key to world.travelledMeters }
            world.onManeuverDistance = { m ->
                distances += m
                NavigationState.onManeuverDistance(m)
            }
            val routed = CountDownLatch(1)
            world.attachRouteCallbacks(onSuccess = { routed.countDown() }, onFailure = {})
            assertTrue(world.calculateDriveRoute(22.1420, 113.5173, "横琴镇", 0))
            assertTrue(routed.await(5, TimeUnit.SECONDS))
            NavigationState.begin()
            assertTrue(world.startNavigation(emulator = true))
            assertEquals(listOf("g_start" to 0), emitted)

            fun driveTo(meters: Int) {
                while (world.navigating && world.travelledMeters < meters) world.advance(2.0)
            }
            driveTo(800)
            assertEquals(1_150 - world.travelledMeters, distances.last())
            rules.onUserFinalTranscript("前面堵不堵")
            assertEquals(SpeechArbiter.Reply.PLAY, SpeechAuthority.reply(), "far from a turn")
            driveTo(1_060)
            assertTrue(distances.last()!! < SpeechArbiter.WORKLOAD_HOLD_DISTANCE_M)
            assertEquals(SpeechArbiter.Reply.HOLD, SpeechAuthority.reply(), "R6a: the distance reached the arbiter")
            assertTrue(SpeechAuthority.arbiter.workloadHeld())
            driveTo(1_160)
            assertEquals(SpeechArbiter.Reply.PLAY, SpeechAuthority.reply(), "the turn was passed")
            driveTo(3_000)

            assertTrue(world.arrived)
            val expected = HengqinWorld.promptsFor(2_600)
            assertEquals(expected.map { it.key }, emitted.map { it.first })
            assertEquals(
                listOf("g_start", "g_m1_300", "g_m1_50", "g_m2_300", "g_m2_50", "g_m3_300", "g_m3_50", "g_m4_300", "g_m4_50", "g_arrive"),
                emitted.map { it.first },
            )
            expected.zip(emitted).forEach { (p, e) ->
                assertTrue(e.second in p.atMeters..p.atMeters + 2, "${p.key} at ${e.second} m, due at ${p.atMeters} m")
            }
            assertEquals(null, distances.last(), "navigation ended")
        } finally {
            world.close()
        }
    }

    /** The drive's lines are placed so that two start 0.5-2 s before a prompt (40 km/h). */
    @Test
    fun driveLinesCollideWithGuidanceAsDesigned() {
        val prompts = HengqinWorld.promptsFor(2_600)
        val mps = 40.0 / 3.6
        val early = LiveDemoRun.DRIVE_LINES.count { (m, _) ->
            prompts.any { p -> (p.atMeters - m) / mps in 0.5..2.0 }
        }
        assertTrue(early >= 2, "lines starting 0.5-2 s before a prompt: $early")
    }

    private companion object {
        const val RATE = 24_000
    }
}
