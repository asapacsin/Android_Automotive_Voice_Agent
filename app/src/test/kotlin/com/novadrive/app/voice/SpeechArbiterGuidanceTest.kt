package com.novadrive.app.voice

import com.novadrive.app.voice.SpeechArbiter.Focus
import com.novadrive.app.voice.SpeechArbiter.Reply
import com.novadrive.app.voice.SpeechArbiter.Uplink
import com.novadrive.ingress.realtime.DomainVoiceEvent.AppPromptTurn.Phase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** SPEC-018: assistant guidance in the arbiter. */
class SpeechArbiterGuidanceTest {
    private var now = 1_000_000L
    private val arbiter = SpeechArbiter(clock = { now })

    @Test
    fun guidancePlaysOutsideTheP1Window() {
        arbiter.onNavigating(true)
        now += SpeechArbiter.WINDOW_MS + 1
        assertEquals(Reply.DROP, arbiter.reply())
        assertEquals(Reply.PLAY, arbiter.guidanceChunk("p1"))
    }

    @Test
    fun guidancePlaysInsideTheWorkloadZone() {
        arbiter.onNavigating(true)
        arbiter.onDriverRequest()
        arbiter.onManeuverDistance(40)
        assertEquals(Reply.HOLD, arbiter.reply())
        assertEquals(Reply.PLAY, arbiter.guidanceChunk("p1"))
    }

    @Test
    fun guidanceHoldsUnderAmapGuidanceAndTransientLoss() {
        arbiter.onGuidanceSpeaking(true)
        assertEquals(Reply.HOLD, arbiter.guidanceChunk("p1"))
        arbiter.onGuidanceSpeaking(false)
        assertEquals(Reply.PLAY, arbiter.guidanceChunk("p1"))
        arbiter.onFocus(Focus.TRANSIENT_LOSS)
        assertEquals(Reply.HOLD, arbiter.guidanceChunk("p1"))
        assertTrue(arbiter.guidanceHeld())
    }

    @Test
    fun guidanceDropsAfterAbandonAndOnPermanentLoss() {
        arbiter.abandon("p1")
        assertEquals(Reply.DROP, arbiter.guidanceChunk("p1"))
        assertEquals(Reply.PLAY, arbiter.guidanceChunk("p2"))
        arbiter.onFocus(Focus.PERMANENT_LOSS)
        assertEquals(Reply.DROP, arbiter.guidanceChunk("p2"))
    }

    @Test
    fun abandonedIdsAreBounded() {
        for (i in 0..SpeechArbiter.ABANDONED_KEPT) arbiter.abandon("p$i")
        assertEquals(Reply.PLAY, arbiter.guidanceChunk("p0"))
        assertEquals(Reply.DROP, arbiter.guidanceChunk("p${SpeechArbiter.ABANDONED_KEPT}"))
    }

    @Test
    fun ordinaryReplyHoldsWhileGuidanceIsOpenOrPlaying() {
        assertEquals(Reply.PLAY, arbiter.reply())
        arbiter.onAssistantGuidance("p1", Phase.OPENED)
        assertEquals(Reply.HOLD, arbiter.reply())
        assertTrue(arbiter.assistantGuidanceActive())
        arbiter.onGuidancePlayout(true)
        arbiter.onAssistantGuidance("other", Phase.COMPLETED)
        assertEquals(Reply.HOLD, arbiter.reply())
        arbiter.onAssistantGuidance("p1", Phase.COMPLETED)
        assertEquals(Reply.HOLD, arbiter.reply()) // still playing
        arbiter.onGuidancePlayout(false)
        assertEquals(Reply.PLAY, arbiter.reply())
        assertFalse(arbiter.assistantGuidanceActive())
    }

    @Test
    fun voidedClosesThePrompt() {
        arbiter.onAssistantGuidance("p1", Phase.OPENED)
        arbiter.onAssistantGuidance("p1", Phase.VOIDED)
        assertEquals(Reply.PLAY, arbiter.reply())
    }

    @Test
    fun uplinkClosedWhileGuidancePlaysAndForTheTail() {
        arbiter.onGuidancePlayout(true)
        assertEquals(Uplink.CLOSED, arbiter.uplink())
        arbiter.onGuidancePlayout(false)
        assertEquals(Uplink.CLOSED, arbiter.uplink())
        now += SpeechArbiter.TAIL_MS
        assertEquals(Uplink.OPEN, arbiter.uplink())
    }

    @Test
    fun uplinkReopensAtTheCap() {
        arbiter.onGuidancePlayout(true)
        now += SpeechArbiter.MAX_CLOSED_MS - 1
        assertEquals(Uplink.CLOSED, arbiter.uplink())
        now += 1
        assertEquals(Uplink.OPEN, arbiter.uplink())
    }

    @Test
    fun amapEndingDoesNotReopenWhileAssistantGuidancePlays() {
        arbiter.onGuidanceSpeaking(true)
        arbiter.onGuidancePlayout(true)
        arbiter.onGuidanceSpeaking(false)
        now += SpeechArbiter.TAIL_MS * 4
        assertEquals(Uplink.CLOSED, arbiter.uplink())
    }

    @Test
    fun driverUtteranceProtectsTheUplinkFromAssistantGuidance() {
        arbiter.onDriverSpeaking(true)
        arbiter.onGuidancePlayout(true)
        assertEquals(Uplink.OPEN, arbiter.uplink())
        assertTrue(arbiter.uplinkProtected())
        arbiter.onDriverSpeaking(false)
        assertEquals(Uplink.CLOSED, arbiter.uplink())
    }
}
