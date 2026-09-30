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
        assertEquals(1, claims)
        assertTrue(tracker.active)
    }

    @Test
    fun claimFalseDropsAndAbandons() {
        claim = false
        tracker.opened("p1")
        assertEquals(Route.DROP, tracker.route { Reply.PLAY })
        assertEquals(listOf("p1"), abandoned)
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
