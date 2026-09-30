package com.novadrive.app.voice

import com.novadrive.app.DebugVoiceLog
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.DomainVoiceEvent.AppPromptTurn.Phase

/**
 * SPEC-018 correlation for [GeminiLiveClient]: an app prompt arms "next opened turn =
 * GUIDANCE(promptId)"; that turn is not a driver turn. One prompt armed or open at a time. Called
 * under the client's lock; [emit] only tryEmits. Logs ids and reasons only, never the text.
 */
internal class GeminiPromptTurn(private val emit: (DomainVoiceEvent) -> Unit) {
    private var armed: String? = null
    private var voided = false

    /** The open turn is the response to this prompt (a GUIDANCE turn). */
    var open: String? = null
        private set

    val busy: Boolean get() = armed != null || open != null

    /** Calls rejected inside a GUIDANCE turn; their results carry no execution evidence. */
    val callIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    fun arm(promptId: String) { armed = promptId }

    fun disarm() { armed = null }

    /** A turn opens: the armed prompt's id if it answers it, else null (an armed one is voided). */
    fun takeArmed(driverSpoke: Boolean): String? {
        val id = armed ?: return null
        if (driverSpoke) { void("driver_transcript"); return null }
        armed = null
        return id
    }

    /** The GUIDANCE turn for [promptId] opened; OPENED precedes its first audio. */
    fun markOpen(promptId: String) {
        open = promptId
        voided = false
        emit(DomainVoiceEvent.AppPromptTurn(promptId, Phase.OPENED))
    }

    /** Voids an armed or open prompt (interrupted, connection loss, driver onset). */
    fun void(reason: String) {
        armed?.let { id ->
            armed = null
            DebugVoiceLog.log("gemini_prompt_voided id=$id reason=$reason opened=false")
            emit(DomainVoiceEvent.AppPromptTurn(id, Phase.VOIDED))
        }
        val id = open
        if (id != null && !voided) {
            voided = true
            DebugVoiceLog.log("gemini_prompt_voided id=$id reason=$reason opened=true")
            emit(DomainVoiceEvent.AppPromptTurn(id, Phase.VOIDED))
        }
    }

    /** The GUIDANCE turn ended: COMPLETED unless it was voided. */
    fun complete() {
        val id = open ?: return
        if (!voided) emit(DomainVoiceEvent.AppPromptTurn(id, Phase.COMPLETED))
        clearOpen()
    }

    fun clearOpen() { open = null; voided = false }

    /** SPEC-018 B5: a call in a GUIDANCE turn is rejected, never executed. */
    fun reject(call: DomainVoiceEvent.ToolCall): DomainVoiceEvent.ToolCall {
        callIds += call.callId
        DebugVoiceLog.log("gemini_tool_call id=${call.callId} tool=${call.name} args=rejected reason=$NOT_A_DRIVER_TURN")
        return RealtimeToolCatalog.rejectedCall(call.callId, call.name, NOT_A_DRIVER_TURN)
    }

    companion object {
        const val NOT_A_DRIVER_TURN = "NOT_A_DRIVER_TURN"
    }
}
