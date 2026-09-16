package com.novadrive.app.ui

import com.novadrive.ingress.realtime.VoiceUiState

object AssistantUiStateMapper {
    fun from(state: VoiceUiState): AssistantUiState =
        when (state) {
            VoiceUiState.DISCONNECTED -> AssistantUiState.IDLE
            VoiceUiState.CONNECTING, VoiceUiState.RECONNECTING, VoiceUiState.THINKING ->
                AssistantUiState.PROCESSING
            VoiceUiState.LISTENING, VoiceUiState.USER_SPEAKING -> AssistantUiState.LISTENING
            VoiceUiState.SPEAKING -> AssistantUiState.RESPONDING
            VoiceUiState.ERROR -> AssistantUiState.ERROR
        }

    fun displayLabel(state: AssistantUiState): String =
        when (state) {
            AssistantUiState.IDLE -> "IDLE"
            AssistantUiState.LISTENING -> "LISTENING"
            AssistantUiState.PROCESSING -> "PROCESSING"
            AssistantUiState.RESPONDING -> "SPEAKING"
            AssistantUiState.ACTION_SUCCESS -> "SUCCESS"
            AssistantUiState.ACTION_FAILURE -> "FAILURE"
            AssistantUiState.ERROR -> "ERROR"
        }
}
