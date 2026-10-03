package com.novadrive.app.voice

import com.novadrive.ingress.realtime.DomainVoiceEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The provider-neutral per-turn gate (INVARIANT I-1), driven directly with no socket: what any
 * realtime adapter gets when it feeds [DriverTurnPipeline].
 */
class DriverTurnPipelineTest {
    private class FakeHost : DriverTurnPipeline.Host {
        val emitted = mutableListOf<DomainVoiceEvent>()
        val corrections = mutableListOf<String>()
        override fun emit(event: DomainVoiceEvent) { emitted += event }
        override fun sendCorrection(text: String, callMayFollow: Boolean) { corrections += text }
        override val responseCancelledByClient: Boolean = false
        override val listeningSuspended: Boolean = false
    }

    private val host = FakeHost()
    private val goodAudio = SpeechUplinkGate.Segment(durationMs = 1_500, voicedFrames = 14, peak = 9_000)
    private val pipeline = DriverTurnPipeline(
        lastAudioSegment = { goodAudio },
        contextAwaitingAnswer = { false },
        speechEvidence = { true },
        host = host,
    )

    @AfterEach
    fun tearDown() = pipeline.onSessionEnded()

    private val claim = "已为您打开空调"
    private val audio1 = DomainVoiceEvent.AudioDelta("AAAA")
    private val subtitle = DomainVoiceEvent.AssistantTranscript(claim, true)

    /** An action request whose reply claims the action before any execution evidence. */
    private fun heldActionClaim(): DomainVoiceEvent.ToolCall {
        pipeline.beginDriverTurn(playbackOrSpeaking = false, responseInProgress = false)
        pipeline.onUserTranscript("帮我打开空调")
        pipeline.onResponseCreated()
        val call = DomainVoiceEvent.ToolCall("call_1", "control_climate", mapOf("action" to "power_on"))
        assertFalse(pipeline.filter(call), "a tool call is never held")
        pipeline.onToolCallDispatched(call)
        assertTrue(pipeline.filter(audio1), "reply audio claiming an action waits for proof")
        assertTrue(pipeline.filter(subtitle), "and so does its subtitle")
        pipeline.appendAssistantText(claim)
        assertTrue(host.emitted.isEmpty(), "nothing is heard before execution evidence")
        return call
    }

    @Test
    fun anActionClaimIsHeldUntilExecutionSucceedsThenReleasedInOrder() {
        heldActionClaim()
        pipeline.onToolResult("call_1", """{"ok":true,"tool":"control_climate"}""")
        assertEquals(listOf(audio1, subtitle), host.emitted)
        assertTrue(host.corrections.isEmpty())
    }

    @Test
    fun aFailedExecutionDoesNotReleaseTheClaim() {
        heldActionClaim()
        pipeline.onToolResult("call_1", """{"ok":false,"error":"VEHICLE_UNAVAILABLE"}""")
        assertTrue(host.emitted.isEmpty(), "ok=false is not proof (I-1)")
    }

    @Test
    fun aNewDriverTurnDropsTheHeldTurnAndSupersedesTheRunningResponse() {
        heldActionClaim()
        pipeline.beginDriverTurn(playbackOrSpeaking = false, responseInProgress = true)
        assertTrue(host.emitted.isEmpty(), "the superseded turn's held output is discarded")
        assertTrue(pipeline.filter(DomainVoiceEvent.AudioDelta("BBBB")), "the old response's remaining audio is dropped")
        assertTrue(pipeline.filter(DomainVoiceEvent.AudioDone))
        assertTrue(host.emitted.isEmpty())
        assertTrue(pipeline.takeSuperseded(), "the running response is marked superseded")
        assertFalse(pipeline.takeSuperseded(), "and the mark is taken once")
    }

    @Test
    fun toolCallsErrorsAndTranscriptsAreNeverHeld() {
        heldActionClaim()
        assertFalse(pipeline.filter(DomainVoiceEvent.ToolCall("call_2", "control_climate", mapOf("action" to "power_off"))))
        assertFalse(pipeline.filter(DomainVoiceEvent.Error("X", "y")))
        assertFalse(pipeline.filter(DomainVoiceEvent.UserTranscript("帮我打开空调", true)))
    }

    @Test
    fun anIdenticalCallIsADuplicateOncePerResponse() {
        pipeline.beginDriverTurn(playbackOrSpeaking = false, responseInProgress = false)
        pipeline.onResponseCreated()
        pipeline.clearCallsThisResponse()
        val call = DomainVoiceEvent.ToolCall("call_1", "adjust_temperature", mapOf("delta" to "-2"))
        assertFalse(pipeline.isDuplicateCall(call))
        assertTrue(pipeline.isDuplicateCall(call.copy(callId = "call_2")))
        assertFalse(pipeline.isDuplicateCall(call.copy(arguments = mapOf("delta" to "2"))), "different arguments are a different call")
        pipeline.clearCallsThisResponse()
        assertFalse(pipeline.isDuplicateCall(call), "a new response starts clean")
        assertFalse(pipeline.isDuplicateCall(DomainVoiceEvent.AudioDone), "only tool calls can be duplicates")
    }

    @Test
    fun aSupersededTurnsLateResultDoesNotReleaseTheNewTurnsClaim() {
        // D-11: the old turn's call_1 result arrives after a new driver turn began.
        heldActionClaim()
        pipeline.beginDriverTurn(playbackOrSpeaking = false, responseInProgress = false)
        pipeline.onUserTranscript("帮我打开车窗")
        pipeline.onResponseCreated()
        val call = DomainVoiceEvent.ToolCall("call_2", "control_window", mapOf("action" to "open"))
        assertFalse(pipeline.filter(call))
        pipeline.onToolCallDispatched(call)
        val audio2 = DomainVoiceEvent.AudioDelta("CCCC")
        assertTrue(pipeline.filter(audio2))
        pipeline.appendAssistantText("已为您打开车窗")
        pipeline.onToolResult("call_1", """{"ok":true,"tool":"control_climate"}""")
        assertTrue(host.emitted.isEmpty(), "a superseded turn's result is not this turn's proof")
        pipeline.onToolResult("call_2", """{"ok":true,"tool":"control_window"}""")
        assertTrue(audio2 in host.emitted, "the turn's own result still releases it")
    }

    @Test
    fun onlyAnActionRequestWaitsForALateCallBeforeItsCorrection() {
        // Owner emulator run 2026-10-03: a dropped chat reply waited the full 20 s grace twice.
        assertFalse(callMayFollow(DriverTurn.Kind.CONVERSATION))
        assertFalse(callMayFollow(DriverTurn.Kind.CAPABILITY_HELP))
        assertFalse(callMayFollow(DriverTurn.Kind.NO_TOOL_ACTION))
        for (kind in DriverTurn.Kind.values().filter { it !in setOf(DriverTurn.Kind.CONVERSATION, DriverTurn.Kind.CAPABILITY_HELP, DriverTurn.Kind.NO_TOOL_ACTION) }) {
            assertTrue(callMayFollow(kind), "$kind")
        }
    }
}
