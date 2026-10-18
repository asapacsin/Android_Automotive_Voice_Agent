package com.novadrive.app.voice

import com.novadrive.app.DebugVoiceLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * P45 Unit 1: what the server sent around a lost or stalled Gemini turn, for the debug log only.
 * Field names, counts, booleans and milliseconds - never text, a transcript or audio (I-8). It
 * makes no decision and sends nothing; [GeminiLiveClient] calls it under its own lock.
 */
internal class GeminiTurnDiagnostics(
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /** An open turn with no server message for this long logs `gemini_turn_quiet` once. */
    @Volatile var quietMs: Long = 5_000

    @Volatile private var lastMessageAt = 0L
    @Volatile private var turnMessages = 0
    @Volatile private var quietJob: Job? = null
    @Volatile private var turnGenerationDone = false
    @Volatile private var turnCalls = 0
    private var activityEndAt: Long? = null
    /** The message being handled: when it opens a turn, its own call and generationComplete count. */
    private var opening: GeminiLiveProtocol.ServerMessage? = null

    /** A new connection or a dropped one: no utterance is pending a first transcript. */
    fun reset() {
        activityEndAt = null
    }

    fun message(m: GeminiLiveProtocol.ServerMessage, turnOpen: Boolean) {
        val now = nowMs()
        opening = m
        if (m.thoughtParts > 0) DebugVoiceLog.log("gemini_thought_dropped parts=${m.thoughtParts}")
        m.voiceActivity?.let { DebugVoiceLog.log("gemini_voice_activity type=$it") }
        if (m.voiceActivity == GeminiLiveProtocol.ACTIVITY_START && activityEndAt != null) {
            DebugVoiceLog.log("gemini_first_transcript ms=none")
            activityEndAt = null
        }
        if (m.voiceActivity == GeminiLiveProtocol.ACTIVITY_END) activityEndAt = now
        if (m.inputTranscription != null) {
            activityEndAt?.let { DebugVoiceLog.log("gemini_first_transcript ms=${now - it}") }
            activityEndAt = null
        }
        val meta = buildList {
            m.waitingForInput?.let { add("waiting_for_input=$it") }
            if (m.textParts > 0) add("text_parts=${m.textParts}")
            if (m.interimInput) add("interim_input=true")
            if (m.responseTokens.isNotEmpty() && (m.turnComplete || m.generationComplete)) add("response_tokens=" + m.responseTokens.entries.joinToString(",") { "${it.key}:${it.value}" })
        }
        if (meta.isNotEmpty()) DebugVoiceLog.log("gemini_server_meta " + meta.joinToString(" "))
        // A message with audio or a call opens the turn itself: that is not content without a turn.
        if (!turnOpen && m.contentFields.isNotEmpty() && m.audio.isEmpty() && m.toolCalls.isEmpty()) {
            DebugVoiceLog.log("gemini_content_no_turn kind=${m.contentFields.joinToString("+")} audio=${m.audio.size} text_parts=${m.textParts}")
        }
        if (turnOpen) {
            if (m.generationComplete) turnGenerationDone = true
            turnCalls += m.toolCalls.size
            turnMessages++
            lastMessageAt = now
            armQuiet()
        }
    }

    fun turnOpened() {
        turnMessages = 1
        turnGenerationDone = opening?.generationComplete == true
        turnCalls = opening?.toolCalls?.size ?: 0
        lastMessageAt = nowMs()
        armQuiet()
    }

    fun turnClosed() {
        quietJob?.cancel()
        quietJob = null
    }

    private fun armQuiet() {
        quietJob?.cancel()
        val wait = quietMs
        quietJob = scope.launch {
            delay(wait)
            DebugVoiceLog.log("gemini_turn_quiet ms=${nowMs() - lastMessageAt} msgs=$turnMessages gen=$turnGenerationDone calls=$turnCalls")
        }
    }
}
