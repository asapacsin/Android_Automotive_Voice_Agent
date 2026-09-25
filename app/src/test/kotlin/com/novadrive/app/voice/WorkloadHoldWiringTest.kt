package com.novadrive.app.voice

import android.media.AudioManager
import com.novadrive.app.NavigationState
import com.novadrive.app.nav.amap.NavigationTraceListener
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** SPEC-012 step 4 (A5 wired): the workload hold reaches the player through one hook. */
class WorkloadHoldWiringTest {
    private var now = 1_000_000L
    private val holds = mutableListOf<Boolean>()
    private val rechecks = mutableListOf<Pair<Long, () -> Unit>>()
    private val chunk = byteArrayOf(1, 2, 3, 4)

    private fun port(): AndroidPlaybackPort {
        val port = AndroidPlaybackPort(PcmAudioPlayer { }) { true }
        SpeechAuthority.playbackHold = { holds += it }
        SpeechAuthority.scheduleRecheck = { d, t -> rechecks += d to t }
        return port
    }

    @BeforeEach
    fun setup() {
        SpeechAuthority.resetForTest { now }
        NavigationState.reset()
        NavigationState.begin()
        SpeechAuthority.arbiter.onDriverRequest()
    }

    @AfterEach
    fun teardown() {
        NavigationState.reset()
        SpeechAuthority.resetForTest()
    }

    @Test
    fun heldReplyPausesThenResumesWhenTheManoeuvreIsPassed() {
        val port = port()
        port.beginReply(1)
        NavigationState.onManeuverDistance(120)
        port.enqueue(chunk, 1)
        assertEquals(listOf(true), holds)
        NavigationState.onManeuverDistance(90)
        assertEquals(listOf(true), holds, "still in the zone")
        NavigationState.onManeuverDistance(400)
        assertEquals(listOf(true, false), holds)
        // Now started: re-entering the next zone must not cut it.
        NavigationState.onManeuverDistance(100)
        assertEquals(listOf(true, false), holds)
    }

    @Test
    fun theEightSecondCapReleasesThroughTheDelayedRecheck() {
        val port = port()
        NavigationState.onManeuverDistance(120)
        port.enqueue(chunk, 0)
        assertEquals(listOf(true), holds)
        assertEquals(1, rechecks.size, "one delayed re-check when the hold begins")
        assertTrue(rechecks[0].first >= SpeechArbiter.WORKLOAD_HOLD_MAX_MS)
        port.enqueue(chunk, 0)
        assertEquals(1, rechecks.size, "not re-posted while the same hold lasts")
        now += rechecks[0].first
        rechecks[0].second()
        assertEquals(listOf(true, false), holds)
    }

    @Test
    fun guidanceEndInsideAWorkloadHoldDoesNotResume() {
        val port = port()
        NavigationState.onManeuverDistance(120)
        port.enqueue(chunk, 0)
        // The guidance listener's two halves: start pauses directly, end asks for a sync.
        SpeechAuthority.arbiter.onGuidanceSpeaking(true)
        SpeechAuthority.notePaused()
        SpeechAuthority.arbiter.onGuidanceSpeaking(false)
        SpeechAuthority.syncPlaybackHold()
        assertFalse(holds.contains(false), "guidance end must not resume through the workload hold")
    }

    @Test
    fun guidanceEndOutsideAWorkloadHoldStillResumes() {
        port()
        SpeechAuthority.arbiter.onGuidanceSpeaking(true)
        SpeechAuthority.notePaused()
        SpeechAuthority.arbiter.onGuidanceSpeaking(false)
        SpeechAuthority.syncPlaybackHold()
        assertEquals(listOf(false), holds)
    }

    @Test
    fun focusResumeInsideAWorkloadHoldDoesNotResume() {
        val port = port()
        NavigationState.onManeuverDistance(120)
        port.enqueue(chunk, 0)
        port.applyFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        port.applyFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertFalse(holds.contains(false))
    }

    @Test
    fun focusResumeOutsideAWorkloadHoldResumes() {
        val port = port()
        port.applyFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        port.applyFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(listOf(false), holds)
    }

    @Test
    fun aReplyAlreadyPlayingIsNotPausedOnEnteringTheZone() {
        val port = port()
        port.enqueue(chunk, 0)
        NavigationState.onManeuverDistance(120)
        port.enqueue(chunk, 0)
        assertTrue(holds.isEmpty())
        assertEquals(SpeechArbiter.Reply.PLAY, SpeechAuthority.reply())
    }

    @Test
    fun beginReplyResetsStarted() {
        val port = port()
        port.enqueue(chunk, 0)
        port.beginReply(1)
        SpeechAuthority.arbiter.onDriverRequest()
        NavigationState.onManeuverDistance(120)
        port.enqueue(chunk, 1)
        assertEquals(listOf(true), holds)
    }

    @Test
    fun concurrentSyncsEndOnTheCurrentAnswer() {
        val applied = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)
        port()
        SpeechAuthority.playbackHold = { applied.set(it) }
        repeat(50) { round ->
            val hold = round % 2 == 0
            NavigationState.onManeuverDistance(if (hold) 120 else 400)
            val threads = List(4) { Thread { repeat(200) { SpeechAuthority.syncPlaybackHold() } } }
            threads.forEach { it.start() }
            threads.forEach { it.join() }
            val expected = SpeechAuthority.reply() == SpeechArbiter.Reply.HOLD
            assertEquals(expected, applied.get(), "round $round")
        }
    }

    @Test
    fun maneuverLogLinesCarryOnlyTheBucket() {
        val lines = listOf(0, 37, 149, 150, 151, 999, 12_345).map { SpeechAuthority.maneuverLogLine(it) } +
            SpeechAuthority.maneuverLogLine(null)
        assertEquals(
            setOf(
                "speech_arbiter in=maneuver bucket=<150",
                "speech_arbiter in=maneuver bucket=>=150",
                "speech_arbiter in=maneuver bucket=unknown",
            ),
            lines.toSet(),
        )
        for (d in listOf(37, 999, 12_345)) {
            assertFalse(SpeechAuthority.maneuverLogLine(d).contains(d.toString()))
        }
    }

    @Test
    fun navigationTraceListenerForwardsNullForNullInfo() {
        port()
        NavigationState.onManeuverDistance(120)
        SpeechAuthority.syncPlaybackHold()
        NavigationTraceListener(onRouteReady = {}, onNavigationEnded = {}).onNaviInfoUpdate(null)
        assertEquals(SpeechArbiter.Reply.PLAY, SpeechAuthority.reply(), "unknown distance lifts the zone")
        assertTrue(SpeechAuthority.maneuverLogLine(null).endsWith("unknown"))
    }
}
