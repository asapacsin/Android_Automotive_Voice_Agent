package com.novadrive.ingress.realtime

enum class VoiceUiState {
    DISCONNECTED,
    CONNECTING,
    LISTENING,
    USER_SPEAKING,
    THINKING,
    SPEAKING,
    RECONNECTING,
    ERROR,
    ;

    val label: String
        get() =
            when (this) {
                DISCONNECTED -> "Idle"
                CONNECTING -> "Connecting"
                LISTENING -> "Listening"
                USER_SPEAKING -> "User speaking"
                THINKING -> "Thinking or working"
                SPEAKING -> "Assistant speaking"
                RECONNECTING -> "Reconnecting"
                ERROR -> "Error"
            }
}
