package com.novadrive.app.voice

import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.VoiceProviderException
import okhttp3.Request
import okhttp3.Response

/**
 * The wire and vendor seam of [OpenAiRealtimeClient] (SPEC-021): everything that differs between
 * two providers speaking the OpenAI-Realtime event family, and nothing else.
 *
 * Contract:
 * - A dialect owns endpoint, auth, config validation, the outbound message strings, inbound event
 *   parsing, error-code classification, its log prefix and its exception codes. It never owns turn
 *   handling, gating, reset or retry policy; those live in [OpenAiRealtimeClient] for every dialect.
 * - Methods are pure or take explicit inputs. The only state a dialect may keep is per-session
 *   vendor state set in [onSessionOpening] (Baidu's requested/confirmed voice), so one dialect
 *   instance belongs to exactly one client.
 * - The client calls the hooks at fixed points, on the socket's callback thread or the connecting
 *   coroutine, exactly where the vendor-specific code used to sit; a dialect must not block.
 */
interface RealtimeDialect<C : Any> {
    /** Prefix of every client log line, e.g. `flex` gives `flex_event`. */
    val logPrefix: String

    /** VAD threshold before the first session is opened. */
    val defaultVadThreshold: Double

    /**
     * True when the VAD threshold depends on navigation: the client then logs navigation changes
     * as `vad_threshold_deferred`. It never resends session.update mid-session (measured
     * 2026-09-16: Baidu rejected a threshold change while input audio was in progress and the
     * session went down); the threshold applies at the next connect.
     */
    val tracksNavigationVad: Boolean

    /**
     * True when this vendor's conversation must be kept short with [ConversationResetPolicy]
     * (measured on Baidu Flex 2026-09-17: it degrades from about the third tool turn). False: one
     * conversation for the session, so no reconnect per tool command and the context survives.
     */
    val resetsConversation: Boolean get() = true

    /** Validates [config] and builds the WebSocket request (URL and auth); may fetch a token. */
    suspend fun buildRequest(config: C): Request

    /** A new session is about to open on [config]: reset per-session vendor state. */
    fun onSessionOpening(config: C)

    /** The persona instructions held in [config], before context and style are added. */
    fun instructions(config: C): String?

    /** The VAD threshold to configure at the next session.update. */
    fun vadThreshold(navigating: Boolean): Double

    /** The session.update sent after session.created. */
    fun sessionUpdate(instructions: String, vadThreshold: Double): String

    /**
     * An error arrived before the session was ready. Returns a replacement session.update to send
     * instead of failing (once), or null to fail. [instructions] is only evaluated when used.
     */
    fun recoverSessionError(instructions: () -> String, vadThreshold: Double): String?

    /** A session.updated event arrived (raw text). */
    fun onSessionUpdated(text: String)

    fun newCallAssembler(): RealtimeCallAssembler
    fun audioAppend(base64Audio: String): String

    /** True when an outbound [message] is microphone audio (dropped from the held queue on sleep). */
    fun isAudioAppend(message: String): Boolean
    fun responseCancel(): String
    fun responseCreate(): String

    /**
     * One response whose only job is to speak [text], or null when this provider has no such
     * request. The client plays the audio as a wait cue and does not judge it as the reply.
     */
    fun progressResponse(text: String): String? = null
    fun functionCallOutput(callId: String, output: String): String

    /**
     * A user text turn, or null when this dialect cannot send one now: the client then logs
     * `<prefix>_text_unsupported` once per session and sends nothing, not even response.create.
     */
    fun userTextMessage(text: String): String?
    fun parseCommonEvent(text: String, speaking: Boolean): List<DomainVoiceEvent>

    /** A loggable code for an `error` event; never the message. */
    fun errorCode(text: String): String
    fun isResponseAlreadyActive(text: String): Boolean

    fun readyTimeout(): VoiceProviderException
    fun notConnected(): VoiceProviderException
    fun connectionClosed(status: Int): VoiceProviderException
    fun sessionFailed(): VoiceProviderException
    fun protocolError(cause: Throwable): VoiceProviderException
    fun mapFailure(failure: Throwable, response: Response?): VoiceProviderException
}

/** Stateful assembler of streamed function calls; emits a ToolCall only at the done event. */
interface RealtimeCallAssembler {
    fun consume(text: String): List<DomainVoiceEvent>
    fun clear()
}
