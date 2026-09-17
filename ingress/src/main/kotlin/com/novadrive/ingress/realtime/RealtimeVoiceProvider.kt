package com.novadrive.ingress.realtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Provider-neutral realtime S2S port.
 *
 * UI and business logic consume [RealtimeEvent] / [DomainVoiceEvent] only.
 * Vendor JSON never leaves an adapter.
 */
interface RealtimeVoiceProvider {
    val providerId: String
    val capabilities: ProviderCapabilities

    val supportsCustomTools: Boolean
        get() = capabilities.customTools
    val supportsFunctionCalling: Boolean
        get() = capabilities.functionCalling
    val supportsRealtimeAudio: Boolean
        get() = capabilities.realtimeAudio
    val supportsServerVadInterrupt: Boolean
        get() = capabilities.serverVadInterrupt

    suspend fun connect(config: RealtimeSessionConfig)

    suspend fun disconnect()

    fun sendAudio(pcm16le: ByteArray)

    suspend fun commitInputAudio() {}

    suspend fun cancelAssistantResponse(): DomainVoiceEvent

    suspend fun sendText(text: String) {}

    /** Drops microphone audio queued but not yet sent (listening was just stopped). */
    fun discardPendingAudio() {}

    /** The next turn should start without the earlier conversation (after a standby). */
    fun startFreshConversation() {}

    /** Cancels a reply that is in progress even if its audio has not started yet. */
    suspend fun cancelActiveResponse(): DomainVoiceEvent = cancelAssistantResponse()

    suspend fun injectWorkResult(result: WorkInjection): DomainVoiceEvent

    fun events(): Flow<RealtimeEvent> = emptyFlow()

    /** Synchronous test/compat surface used by existing Baidu mock tests. */
    fun connect(model: String = VoiceCatalog.DEFAULT_MODEL)

    fun receiveEvents(): List<DomainVoiceEvent> = emptyList()

    fun interrupt(): DomainVoiceEvent = DomainVoiceEvent.Interrupted("unsupported")

    fun sendToolResult(result: ToolResult): DomainVoiceEvent =
        DomainVoiceEvent.ToolUnsupported("unsupported")

    fun close()
}

class VoiceProviderException(
    val code: String,
    val safeMessage: String,
    cause: Throwable? = null,
) : Exception(safeMessage, cause)

data class WorkInjection(
    val callId: String,
    val ok: Boolean,
    val output: String,
    val speakable: Boolean = true,
)

enum class ErrorClass {
    RETRYABLE,
    TERMINAL,
    AUTH,
    RATE_LIMIT,
    MALFORMED,
    CANCELLED,
}

fun classifyVoiceError(code: String): ErrorClass {
    val normalized = code.uppercase()
    return when {
        normalized.contains("CANCEL") -> ErrorClass.CANCELLED
        normalized.contains("AUTH") || normalized.contains("CREDENTIAL") -> ErrorClass.AUTH
        normalized.contains("QUOTA") || normalized.contains("RATE") -> ErrorClass.RATE_LIMIT
        normalized.contains("INVALID_MODEL") || normalized.contains("MALFORMED") -> ErrorClass.MALFORMED
        normalized.contains("DISCONNECT") ||
            normalized.contains("CONNECTION") ||
            normalized.contains("DNS") ||
            normalized.contains("TIMEOUT") ||
            normalized.contains("WS_FAILED") ||
            normalized.contains("UNAVAILABLE") -> ErrorClass.RETRYABLE
        else -> ErrorClass.TERMINAL
    }
}
