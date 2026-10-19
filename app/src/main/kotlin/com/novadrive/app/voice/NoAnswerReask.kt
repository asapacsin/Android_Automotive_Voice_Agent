package com.novadrive.app.voice

import com.novadrive.app.DebugVoiceLog
import com.novadrive.ingress.realtime.DomainVoiceEvent

/**
 * P45 Unit 2 wiring for [EmptyResponseRetryPolicy], kept out of the two clients so they only call
 * it. The decisions stay in the policy; this file maps each client's state and wire onto it.
 */

/** The silent Gemini re-ask (clientContent, turnComplete:true; ai.google.dev/api/live). */
internal const val GEMINI_REASK_TEXT = "请回应我刚才说的话。"

/** Why a re-ask may not go out now, or null when it may. Quiet listening is the policy's own check. */
internal fun noAnswerBlock(
    suspended: Boolean,
    guidance: Boolean,
    segment: SpeechUplinkGate.Segment?,
    busy: Boolean,
): String? = when {
    suspended -> "suspended"
    guidance -> "guidance"
    segment?.isSuspicious() == true -> "suspicious"
    busy -> "busy"
    else -> null
}

/** One OpenAI-realtime server event, already translated by the client (no wire words here, ADR-009). */
internal fun EmptyResponseRetryPolicy.onRealtimeEvent(started: Boolean, stopped: Boolean, created: Boolean, progress: Boolean): Boolean {
    when {
        started -> onSpeechStarted()
        stopped -> onEndOfSpeech()
        created -> return takeVoided().also { onResponseCreated() } // true: cancel this response (Q5)
        progress -> onOutput()
    }
    return false
}

/** An OpenAI-realtime message went out: a model-bound turn (not audio, not a cancel) clears a void (Q5, fail-open). */
internal fun EmptyResponseRetryPolicy.onSent(dialect: RealtimeDialect<*>, message: String) {
    if (!dialect.isAudioAppend(message) && message != dialect.responseCancel()) onClientTurnSent()
}

/** One Gemini server message: an onset opens a new utterance (also a server-only one, F19), and so on. */
internal fun EmptyResponseRetryPolicy.onGeminiMessage(m: GeminiLiveProtocol.ServerMessage) {
    if (m.voiceActivity == GeminiLiveProtocol.ACTIVITY_START) onSpeechStarted()
    if (m.inputTranscription != null) onHeard()
    if (m.audio.isNotEmpty() || m.outputTranscription != null || m.toolCalls.isNotEmpty()) onOutput()
    if (m.voiceActivity == GeminiLiveProtocol.ACTIVITY_END) onEndOfSpeech()
}

/** Gemini closed a turn with no turn open (P45 F3): logged, and re-asked at once when transcribed. */
internal fun EmptyResponseRetryPolicy.onGeminiEmptyClose(status: String, transcribed: Boolean) {
    DebugVoiceLog.log("gemini_turn_empty status=$status transcribed=$transcribed")
    if (transcribed) fire(EmptyResponseRetryPolicy.EMPTY_CLOSE)
}

/** A call for a given-up turn, or for the turn of a voided re-ask, is answered as not executed (a rejected call). */
internal fun EmptyResponseRetryPolicy.lateCall(call: DomainVoiceEvent.ToolCall?): DomainVoiceEvent.ToolCall? =
    call?.let {
        when {
            gaveUp -> RealtimeToolCatalog.rejectedCall(it.callId, it.name, "REASK_GAVE_UP")
            turnVoided -> RealtimeToolCatalog.rejectedCall(it.callId, it.name, "REASK_VOIDED")
            else -> it
        }
    }
