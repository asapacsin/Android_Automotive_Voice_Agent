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
    /**
     * The words of the reply audio, chunk by chunk as the provider transcribes its own speech
     * (ADR-016). Emitted only when an external assistant voice speaks instead of the provider's
     * audio; it is held, released and dropped exactly like [AudioDelta]. Never logged with text.
     */
    data class SpeechText(val text: String) : DomainVoiceEvent
    /** A new model reply started on the server (`response.created`). */
    data object ResponseStarted : DomainVoiceEvent
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
    /**
     * The provider withdrew tool calls it had issued (the driver moved on). Work not yet executed
     * is not executed; an action that already ran is never "undone" by this (I-1).
     */
    data class ToolCallCancelled(val callIds: List<String>) : DomainVoiceEvent
    /**
     * The provider's own view of whether it still waits on tool work. Information only: whether
     * work is pending is decided by [WorkCoordinator], never by this.
     */
    data class ProviderWorkState(val pending: Boolean) : DomainVoiceEvent
    /**
     * The response to an app prompt sent with [RealtimeVoiceProvider.sendPrompt] (SPEC-018).
     * OPENED precedes that response's first [AudioDelta] in stream order; COMPLETED is its turn
     * end; VOIDED means it was interrupted, cut by a connection loss, or pre-empted by a driver
     * onset (or never opened) before COMPLETED.
     */
    data class AppPromptTurn(val promptId: String, val phase: Phase) : DomainVoiceEvent {
        enum class Phase { OPENED, COMPLETED, VOIDED }
    }
    /**
     * One output-transcription chunk of a GUIDANCE turn (SPEC-018), as received, from its OPENED
     * until its turn end (also after generationComplete); every one precedes that turn's
     * COMPLETED. A GUIDANCE turn emits no [AssistantTranscript]. Never logged with text.
     */
    data class AppPromptTranscript(val promptId: String, val text: String) : DomainVoiceEvent
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
