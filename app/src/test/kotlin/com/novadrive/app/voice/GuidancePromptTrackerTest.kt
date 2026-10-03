package com.novadrive.app.voice

import com.novadrive.app.voice.GuidancePromptTracker.Route
import com.novadrive.app.voice.SpeechArbiter.Reply
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** SPEC-018: the playback port's routing of an open prompt's audio (B2a, B3, B6). */
class GuidancePromptTrackerTest {
    private val abandoned = mutableListOf<String>()
    private val cuts = mutableListOf<String>()
    private var claim = true
    private var claims = 0
    private val tracker = GuidancePromptTracker(
        abandon = { abandoned += it },
        claim = { claims++; claim },
        cut = { cuts += it },
    )

    @Test
    fun noOpenPromptTakesTheOrdinaryPath() {
        assertEquals(Route.ORDINARY, tracker.route { Reply.PLAY })
        assertFalse(tracker.active)
    }

    @Test
    fun openPromptChunkQueuesWithoutTheListeningGate() {
        tracker.opened("p1")
        // The route never consults the listening state; HOLD still queues (the player pauses).
        assertEquals(Route.QUEUE, tracker.route { Reply.HOLD })
        assertEquals(Route.QUEUE, tracker.route { Reply.PLAY })
        assertEquals(0, claims) // R8a: queuing is not playing
        assertTrue(tracker.active)
    }

    @Test
    fun claimIsMadeOnceAtPlayout() {
        tracker.opened("p1")
        tracker.route { Reply.PLAY }
        assertTrue(tracker.startPlayout())
        tracker.route { Reply.PLAY }
        assertTrue(tracker.startPlayout())
        assertEquals(1, claims)
    }

    @Test
    fun heldChunkIsNeverClaimedAndARefusedClaimAbandons() {
        claim = false // the deadline gave the prompt to Amap while the chunk sat HOLD-paused
        tracker.opened("p1")
        assertEquals(Route.QUEUE, tracker.route { Reply.HOLD })
        assertEquals(0, claims)
        assertFalse(tracker.startPlayout())
        assertEquals(listOf("p1"), abandoned)
        assertEquals(null, tracker.playing)
        assertEquals(Route.DROP, tracker.route { if (it in abandoned) Reply.DROP else Reply.PLAY })
    }

    @Test
    fun underrunBeforeCompletedIsNotADrain() {
        tracker.opened("p1")
        tracker.route { Reply.PLAY }
        assertEquals(null, tracker.drained())
        assertTrue(tracker.active)
        tracker.completed("p1")
        assertEquals("p1", tracker.drained())
    }

    @Test
    fun resetForgetsAnOpenPrompt() {
        tracker.opened("p1")
        tracker.route { Reply.PLAY }
        tracker.reset()
        assertFalse(tracker.active)
        assertEquals(Route.ORDINARY, tracker.route { Reply.PLAY })
        assertTrue(cuts.isEmpty())
    }

    @Test
    fun arbiterDropIsNotClaimed() {
        tracker.opened("p1")
        assertEquals(Route.DROP, tracker.route { Reply.DROP })
        assertEquals(0, claims)
    }

    @Test
    fun voidedAfterStartSignalsTheCut() {
        tracker.opened("p1")
        tracker.route { Reply.PLAY }
        assertTrue(tracker.voided("p1"))
        assertEquals(listOf("p1"), cuts)
        assertFalse(tracker.active)
    }

    @Test
    fun voidedBeforeStartIsNotACut() {
        tracker.opened("p1")
        assertFalse(tracker.voided("p1"))
        assertTrue(cuts.isEmpty())
    }

    @Test
    fun completedKeepsPlayingUntilDrained() {
        tracker.opened("p1")
        tracker.route { Reply.PLAY }
        tracker.completed("p1")
        assertEquals(Route.ORDINARY, tracker.route { Reply.PLAY })
        assertTrue(tracker.active)
        assertEquals("p1", tracker.drained())
        assertFalse(tracker.active)
        assertFalse(tracker.flushed())
    }

    @Test
    fun flushWhilePlayingSignalsTheCut() {
        tracker.opened("p1")
        tracker.route { Reply.PLAY }
        assertTrue(tracker.flushed())
        assertEquals(listOf("p1"), cuts)
    }
}
