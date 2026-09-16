package com.novadrive.app.ui

import com.novadrive.ingress.realtime.VoiceUiState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AssistantUiStateMapperTest {
    @Test
    fun disconnectedMapsToIdle() {
        assertEquals(AssistantUiState.IDLE, AssistantUiStateMapper.from(VoiceUiState.DISCONNECTED))
    }

    @Test
    fun connectingMapsToProcessing() {
        assertEquals(AssistantUiState.PROCESSING, AssistantUiStateMapper.from(VoiceUiState.CONNECTING))
    }

    @Test
    fun listeningMapsToListening() {
        assertEquals(AssistantUiState.LISTENING, AssistantUiStateMapper.from(VoiceUiState.LISTENING))
    }

    @Test
    fun userSpeakingMapsToListening() {
        assertEquals(AssistantUiState.LISTENING, AssistantUiStateMapper.from(VoiceUiState.USER_SPEAKING))
    }

    @Test
    fun thinkingMapsToProcessing() {
        assertEquals(AssistantUiState.PROCESSING, AssistantUiStateMapper.from(VoiceUiState.THINKING))
    }

    @Test
    fun speakingMapsToRespondingDisplayedAsSpeaking() {
        val mapped = AssistantUiStateMapper.from(VoiceUiState.SPEAKING)
        assertEquals(AssistantUiState.RESPONDING, mapped)
        assertEquals("SPEAKING", AssistantUiStateMapper.displayLabel(mapped))
    }

    @Test
    fun reconnectingMapsToProcessing() {
        assertEquals(AssistantUiState.PROCESSING, AssistantUiStateMapper.from(VoiceUiState.RECONNECTING))
    }

    @Test
    fun errorMapsToError() {
        assertEquals(AssistantUiState.ERROR, AssistantUiStateMapper.from(VoiceUiState.ERROR))
    }
}
