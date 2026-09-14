package com.novadrive.ingress.realtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VoiceSessionStateMachineTest {
    @Test
    fun idleDoesNotStream() {
        val machine = VoiceSessionStateMachine()
        assertEquals(VoiceUiState.DISCONNECTED, machine.state)
        assertFalse(machine.streamingAudio)
    }

    @Test
    fun connectListenThinkSpeakListen() {
        val machine = VoiceSessionStateMachine()
        machine.userStartSession()
        assertEquals(VoiceUiState.CONNECTING, machine.state)
        assertFalse(machine.streamingAudio)
        machine.onSessionReady()
        assertEquals(VoiceUiState.LISTENING, machine.state)
        assertTrue(machine.streamingAudio)
        machine.onSpeechStopped()
        assertEquals(VoiceUiState.THINKING, machine.state)
        machine.onAudioDelta()
        assertEquals(VoiceUiState.SPEAKING, machine.state)
        machine.onResponseCompleted()
        assertEquals(VoiceUiState.LISTENING, machine.state)
        assertTrue(machine.streamingAudio)
    }

    @Test
    fun bargeInReturnsToListening() {
        val machine = VoiceSessionStateMachine()
        machine.userStartSession()
        machine.onSessionReady()
        machine.onAudioDelta()
        machine.onInterrupted()
        assertEquals(VoiceUiState.LISTENING, machine.state)
        assertTrue(machine.streamingAudio)
        assertEquals("Listening", machine.state.label)
    }

    @Test
    fun errorStopsStreaming() {
        val machine = VoiceSessionStateMachine()
        machine.userStartSession()
        machine.onError("BAIDU_AUTH_FAILED")
        assertEquals(VoiceUiState.ERROR, machine.state)
        assertEquals("BAIDU_AUTH_FAILED", machine.lastErrorCode)
        assertFalse(machine.streamingAudio)
        machine.userStopSession()
        assertEquals(VoiceUiState.DISCONNECTED, machine.state)
        assertNull(machine.lastErrorCode)
    }

    @Test
    fun applyCancelledResponseIsInterrupt() {
        val machine = VoiceSessionStateMachine()
        machine.userStartSession()
        machine.apply(DomainVoiceEvent.SessionReady(VoiceModels.DEFAULT, true))
        machine.apply(DomainVoiceEvent.AudioDelta("AA=="))
        machine.apply(DomainVoiceEvent.ResponseDone("cancelled", "turn_detected"))
        assertEquals(VoiceUiState.LISTENING, machine.state)
    }
}
