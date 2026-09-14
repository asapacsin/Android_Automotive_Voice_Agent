package com.novadrive.ingress.realtime

class VoiceSessionStateMachine {
    var state: VoiceUiState = VoiceUiState.DISCONNECTED
        private set
    var streamingAudio: Boolean = false
        private set
    var lastErrorCode: String? = null
        private set
    var connectionState: RealtimeConnectionState = RealtimeConnectionState.IDLE
        private set

    fun userStartSession() {
        if (state == VoiceUiState.DISCONNECTED || state == VoiceUiState.ERROR) {
            state = VoiceUiState.CONNECTING
            connectionState = RealtimeConnectionState.CONNECTING
            streamingAudio = false
            lastErrorCode = null
        }
    }

    fun onSessionReady() {
        if (state == VoiceUiState.CONNECTING || state == VoiceUiState.RECONNECTING) {
            state = VoiceUiState.LISTENING
            connectionState = RealtimeConnectionState.CONNECTED
            streamingAudio = true
        }
    }

    fun onSpeechStarted() {
        if (state == VoiceUiState.LISTENING ||
            state == VoiceUiState.SPEAKING ||
            state == VoiceUiState.THINKING
        ) {
            state = VoiceUiState.USER_SPEAKING
            streamingAudio = true
        }
    }

    fun onSpeechStopped() {
        if (state == VoiceUiState.LISTENING || state == VoiceUiState.USER_SPEAKING) {
            state = VoiceUiState.THINKING
            streamingAudio = true
        }
    }

    fun onAudioDelta() {
        if (state == VoiceUiState.THINKING ||
            state == VoiceUiState.SPEAKING ||
            state == VoiceUiState.LISTENING ||
            state == VoiceUiState.USER_SPEAKING
        ) {
            state = VoiceUiState.SPEAKING
            streamingAudio = true
        }
    }

    fun onResponseCompleted() {
        if (state == VoiceUiState.SPEAKING ||
            state == VoiceUiState.THINKING ||
            state == VoiceUiState.USER_SPEAKING
        ) {
            state = VoiceUiState.LISTENING
            streamingAudio = true
        }
    }

    fun onInterrupted() {
        if (state == VoiceUiState.SPEAKING ||
            state == VoiceUiState.THINKING ||
            state == VoiceUiState.USER_SPEAKING
        ) {
            state = VoiceUiState.LISTENING
            streamingAudio = true
        }
    }

    fun onReconnecting() {
        if (state != VoiceUiState.DISCONNECTED) {
            state = VoiceUiState.RECONNECTING
            connectionState = RealtimeConnectionState.RECONNECTING
            streamingAudio = false
        }
    }

    fun onError(code: String) {
        state = VoiceUiState.ERROR
        lastErrorCode = code
        streamingAudio = false
        connectionState = RealtimeConnectionState.FAILED
    }

    fun userStopSession() {
        state = VoiceUiState.DISCONNECTED
        streamingAudio = false
        lastErrorCode = null
        connectionState = RealtimeConnectionState.DISCONNECTED
    }

    fun apply(event: DomainVoiceEvent) {
        when (event) {
            is DomainVoiceEvent.SessionReady -> onSessionReady()
            DomainVoiceEvent.SpeechStarted -> onSpeechStarted()
            DomainVoiceEvent.SpeechStopped -> onSpeechStopped()
            is DomainVoiceEvent.AudioDelta -> onAudioDelta()
            is DomainVoiceEvent.ResponseDone -> {
                if (event.status == "cancelled") {
                    onInterrupted()
                } else if (event.status != "failed") {
                    onResponseCompleted()
                }
            }
            is DomainVoiceEvent.Interrupted -> onInterrupted()
            is DomainVoiceEvent.Error -> onError(event.code)
            DomainVoiceEvent.Reconnecting -> onReconnecting()
            DomainVoiceEvent.Closed -> {
                if (state != VoiceUiState.ERROR) {
                    userStopSession()
                } else {
                    streamingAudio = false
                }
            }
            else -> Unit
        }
    }

    fun isSafeWorkDeliveryPoint(): Boolean =
        state == VoiceUiState.LISTENING || state == VoiceUiState.THINKING
}
