package com.novadrive.ingress.realtime

/**
 * Provider-neutral realtime events. Vendor-specific JSON never leaves an adapter.
 */
sealed interface DomainVoiceEvent {
    data class SessionReady(val model: String, val interruptResponse: Boolean) : DomainVoiceEvent
    data object SpeechStarted : DomainVoiceEvent
    data object SpeechStopped : DomainVoiceEvent
    data class UserTranscript(val text: String, val final: Boolean) : DomainVoiceEvent
    data class AssistantTranscript(val text: String, val final: Boolean) : DomainVoiceEvent
    data class AudioDelta(val pcm16leBase64: String) : DomainVoiceEvent
    data object AudioDone : DomainVoiceEvent
    data class ResponseDone(val status: String, val reason: String? = null) : DomainVoiceEvent
    data class Interrupted(val reason: String) : DomainVoiceEvent
    data class Error(val code: String, val message: String) : DomainVoiceEvent
    data object Closed : DomainVoiceEvent
    data class ToolCall(
        val callId: String,
        val name: String,
        val arguments: Map<String, String>,
    ) : DomainVoiceEvent
    data class ToolUnsupported(val reason: String) : DomainVoiceEvent
    data class WorkProgress(val workId: String, val message: String) : DomainVoiceEvent
    data class WorkResult(val workId: String, val output: String) : DomainVoiceEvent
    data class WorkFailed(val workId: String, val message: String) : DomainVoiceEvent
    data object Reconnecting : DomainVoiceEvent
}

/**
 * Normalized session event with a clock timestamp. UI still reads [DomainVoiceEvent],
 * never vendor payloads.
 */
data class RealtimeEvent(
    val atMs: Long,
    val payload: DomainVoiceEvent,
)

data class ToolResult(
    val callId: String,
    val ok: Boolean,
    val output: String,
)
