package com.novadrive.app.voice

import com.novadrive.app.DebugVoiceLog
import com.novadrive.app.LiveInfoTool
import com.novadrive.evaluation.EventType
import com.novadrive.evaluation.Telemetry
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.ResponseOutcome
import org.json.JSONObject

/**
 * The per-driver-turn output gate, provider-neutral (ADR-010).
 *
 * One object owns everything known about the driver's current utterance and decides whether the
 * reply may be heard yet: DriverTurn. The rule it enforces is docs/INVARIANTS.md I-1 — only
 * deterministic execution evidence may establish that an external action occurred. Reply audio and
 * its subtitle wait for that evidence; the transcript, the tool call, the execution and any error
 * never wait for anything.
 *
 * A realtime provider adapter feeds this pipeline with neutral events and keeps its own wire
 * mechanics; it never re-implements the gate.
 */
class DriverTurnPipeline(
    /** Shape of the audio that caused the current turn, measured by [SpeechUplinkGate]. */
    private val lastAudioSegment: () -> SpeechUplinkGate.Segment?,
    /** True when something on screen is waiting for the driver's answer; such turns are never held. */
    private val contextAwaitingAnswer: () -> Boolean,
    /** Time-scoped post-AEC speech evidence for speech over playback (Astra P4). */
    private val speechEvidence: () -> Boolean,
    private val host: Host,
) {
    /**
     * The adapter's side. Its callbacks are invoked while this pipeline's monitor is held: they must
     * not block, and must not take a lock that another thread may hold while calling into the
     * pipeline (emit only tryEmits; a deferred send only schedules).
     */
    interface Host {
        fun emit(event: DomainVoiceEvent)

        /** Send a corrective text turn to the model. */
        fun sendCorrection(text: String)

        /** This client cancelled the response in progress; its held output is not shown (P40). */
        val responseCancelledByClient: Boolean

        /** Listening is suspended; the client sends no turns of its own. */
        val listeningSuspended: Boolean
    }

    @Volatile
    private var turn: DriverTurn = DriverTurn(0)

    private val turnEpoch = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * Cross-turn context, owned here because the driver turn is owned here. Installed so the tool
     * dispatcher and [VoiceContextHints] read this record rather than each keeping their own.
     */
    private val driverContext = DriverContext().also { DriverContext.install(it) }

    /** What the assistant last said, so its own words heard back are not taken for a driver. */
    @Volatile private var lastSpokenReply = ""

    /**
     * The response in progress belongs to a driver turn that was superseded (the driver spoke over
     * it while it was held). Its remaining audio and subtitle are discarded, and its response.done
     * is not judged against the new turn. Measured 2026-09-28 (owner demo, 08:32:41, 08:32:57,
     * 08:33:12, 08:36:31): after `TURN_DROP cancelled_superseded replyChars=0` the rest of the
     * cancelled reply (「我没听清，再说一遍。」, 「这个偏好只能用于路线，不能」) was still emitted,
     * because the gate only asked whether the *new* turn was holding.
     */
    @Volatile private var supersededResponse = false

    /** A correction was already sent for the response being finished; see [afterResponse]. */
    @Volatile private var correctionSentThisResponse = false

    /** Replies that claim an action without a tool call get one corrective follow-up. */
    private val actionGuard = ActionClaimGuard()
    private val assistantText = StringBuffer()

    /**
     * Calls already emitted in the current response, by name and arguments. Found by the
     * simulation benchmark (2026-09-17): the same call emitted twice in one response was executed
     * twice — harmless for 「播放」, wrong for 「调高一点」.
     */
    private val callsThisResponse = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** A new connection: the guard starts clean. */
    fun resetGuard() = actionGuard.reset()

    /** S2 of SPEC-006: a referent does not survive the session that produced it. */
    fun onSessionEnded() {
        driverContext.onSessionEnded()
        // Only our own record: a throwaway client's disconnect must not wipe a live session's
        // context (the releaseNavigatingListenerIfOwned hazard).
        if (DriverContext.currentOrNull() === driverContext) DriverContext.clear()
    }

    /**
     * A new **driver** turn, not a new response. One command produces several responses — the tool
     * call, then the spoken result — and the transcript belongs to the utterance, not the response.
     */
    @Synchronized
    fun beginDriverTurn(playbackOrSpeaking: Boolean, responseInProgress: Boolean) {
        val previous = turn
        if (previous.isHolding || previous.heldCount > 0) {
            // A superseded turn's output is discarded: it can no longer be true or timely, and
            // nothing it produced may be referred to by the utterance that replaced it (S5).
            applyVerdict(previous, previous.cancel("superseded"))
            driverContext.cancel(previous.epoch)
            // ...including what the response still streams after this point.
            if (responseInProgress) supersededResponse = true
        }
        turn = DriverTurn(turnEpoch.incrementAndGet())
        driverContext.onSpeechStarted(turn.epoch)
        if (playbackOrSpeaking) turn.onSpeechDuringPlayback(qualified = speechEvidence())
    }

    /** A response started: its output buffer is fresh and it is not (yet) superseded. */
    fun onResponseCreated() {
        supersededResponse = false
        assistantText.setLength(0)
        startResponse()
    }

    @Synchronized
    private fun startResponse() {
        val reason = turn.onResponseStarted(lastAudioSegment(), contextAwaitingAnswer())
        if (reason != DriverTurn.HoldReason.NONE) {
            DebugVoiceLog.log(
                "TURN_HOLD epoch=${turn.epoch} reason=$reason kind=${turn.kind} " +
                    "durationMs=${turn.audio?.durationMs ?: -1}",
            )
        }
    }

    /** Duplicate detection resets with each response. */
    fun clearCallsThisResponse() = callsThisResponse.clear()

    /** The driver's recognised words for the current turn. */
    fun onUserTranscript(text: String) {
        actionGuard.onUserTranscript(text)
        judgeUserTranscript(text)
    }

    @Synchronized
    private fun judgeUserTranscript(text: String) {
        val before = turn.isHolding
        val reason = turn.onUserTranscript(text, echoOf = lastSpokenReply) { DriverTurn.classify(it) }
        if (turn.userSpoke) driverContext.onDriverUtterance(text, turn.epoch)
        if (before && reason == DriverTurn.HoldReason.NONE) {
            applyVerdict(turn, DriverTurn.Verdict.Release("user_spoke"))
        } else if (!before && reason != DriverTurn.HoldReason.NONE) {
            DebugVoiceLog.log("TURN_HOLD epoch=${turn.epoch} reason=$reason kind=${turn.kind}")
        }
    }

    /** A finished chunk of the assistant's reply text. */
    fun appendAssistantText(chunk: String) {
        assistantText.append(chunk)
        onAssistantText(assistantText.toString())
    }

    /** True when an identical call was already emitted in this response (the caller answers it). */
    fun isDuplicateCall(event: DomainVoiceEvent): Boolean {
        if (event !is DomainVoiceEvent.ToolCall || event.arguments.containsKey("_validation_error")) return false
        val signature = event.name + "|" + event.arguments.toSortedMap()
        return !callsThisResponse.add(signature)
    }

    /**
     * A tool result reached the model. The one place execution evidence enters the gate.
     * INVARIANT I-1: a reply that claims an action happened is released only once a result with
     * ok=true has arrived.
     */
    fun onToolResult(callId: String, output: String) {
        actionGuard.onToolResult(output)
        onExecutionResult(callId, output)
    }

    /**
     * Execution evidence. This is the only path by which an external action can be shown to have
     * happened; a `ok=false` result explicitly does not release a claim.
     */
    @Synchronized
    private fun onExecutionResult(callId: String, output: String) {
        val ok = toolResultProvesExecution(output)
        val failure = if (ok) null else Regex("\"error\":\"([^\"]+)\"").find(output)?.groupValues?.get(1)
        applyVerdict(turn, turn.onExecutionResult(ok, failure, liveInfoKindOf(output), callId, musicConfirmedOf(output)))
    }

    /** SPEC-017: for a play_music result, whether it read back a playing track; else null. */
    private fun musicConfirmedOf(output: String): Boolean? =
        if (!output.contains("\"tool\":\"play_music\"")) null
        else output.contains("\"ok\":true") && output.contains("\"status\":\"playing\"")

    /** The `kind` of a `query_live_info` result, or null for any other tool (SPEC-011 B3). */
    private fun liveInfoKindOf(output: String): String? =
        if (!output.contains("\"tool\":\"${LiveInfoTool.TOOL}\"")) null
        else runCatching { JSONObject(output).optString("kind").ifEmpty { null } }.getOrNull()

    @Synchronized
    private fun onAssistantText(text: String) {
        applyVerdict(turn, turn.onAssistantText(text))
    }

    @Synchronized
    fun onToolCallDispatched(call: DomainVoiceEvent.ToolCall) {
        turn.onToolCall(call.callId, call.name)
    }

    /** Whether the finished response was superseded; clears the mark. */
    fun takeSuperseded(): Boolean {
        val superseded = supersededResponse
        supersededResponse = false
        return superseded
    }

    /** The verdict on a finished (not superseded) response. */
    fun settleResponse(outcome: ResponseOutcome) = finishResponse(hadToolCall = outcome.requestedTool)

    @Synchronized
    private fun finishResponse(hadToolCall: Boolean) {
        applyVerdict(turn, turn.onResponseDone(assistantText.toString(), hadToolCall))
    }

    /** After the provider's own response.done handling: the action-claim follow-up, if any. */
    fun afterResponse(outcome: ResponseOutcome, superseded: Boolean) {
        val spoken = assistantText.toString()
        assistantText.setLength(0)
        if (superseded) {
            DebugVoiceLog.log("flex_superseded_response_done tool=${outcome.requestedTool}")
            // Its tool calls still happened; its words were never heard and need no correction.
            if (outcome.requestedTool) actionGuard.onResponseDone(outcome, spoken)
            return
        }
        if (spoken.isNotBlank()) lastSpokenReply = spoken
        // DriverTurn may already have corrected this response when it dropped the reply. Two
        // corrections mean the model is told the same thing twice: measured on device
        // 2026-09-19, 「算了」 produced two identical follow-ups and `exit_navigation_mode` ran
        // twice. It is idempotent and nothing broke; `adjust_temperature{-2}` twice is -4 degC
        // and would have, were it not for the dispatcher's own duplicate guard. One owner.
        val alreadyCorrected = correctionSentThisResponse
        correctionSentThisResponse = false
        val cancelledByUs = host.responseCancelledByClient
        actionGuard.onResponseDone(outcome, spoken)?.takeIf { !host.listeningSuspended && !alreadyCorrected && !cancelledByUs }?.let { nudge ->
            // Which follow-up, not just that there was one: "we did not catch that" and
            // "which control did you mean" are different product behaviours, and a suite that
            // cannot tell them apart passes S16 either way.
            val kind = when (nudge) {
                ActionClaimGuard.CLARIFY_REFERENT -> "clarify"
                ActionClaimGuard.UNVERIFIED_ACTION_CLAIM -> "unheard"
                ActionClaimGuard.NAVIGATION_NOT_STARTED -> "navigation_not_started"
                else -> "perform"
            }
            DebugVoiceLog.log("flex_action_claim_unverified follow_up=true kind=$kind")
            Telemetry.record(EventType.GUARD_FOLLOW_UP)
            host.sendCorrection(nudge)
        }
    }

    /** True when the event is consumed by the gate (dropped or held) and must not be emitted now. */
    fun filter(event: DomainVoiceEvent): Boolean = dropSupersededOutput(event) || holdOrEmit(event)

    /**
     * Fail-safe after response.done: finishResponse clears the buffer on either verdict, so this
     * only releases output when the provider's parse returned early.
     */
    @Synchronized
    fun releaseFallback() {
        applyVerdict(turn, DriverTurn.Verdict.Release("response_done_fallback"))
    }

    /** Reply audio and subtitle still arriving for a superseded turn; see [supersededResponse]. */
    private fun dropSupersededOutput(event: DomainVoiceEvent): Boolean =
        supersededResponse && (
            event is DomainVoiceEvent.AudioDelta ||
                event is DomainVoiceEvent.AudioDone ||
                event is DomainVoiceEvent.AssistantTranscript
            )

    /** Held output is audio and its subtitle only — never a tool call, an error or a transcript. */
    @Synchronized
    private fun holdOrEmit(event: DomainVoiceEvent): Boolean {
        if (!turn.isHolding) return false
        val holdable = event is DomainVoiceEvent.AudioDelta ||
            event is DomainVoiceEvent.AudioDone ||
            event is DomainVoiceEvent.AssistantTranscript
        if (!holdable) return false
        turn.hold(event)
        if (turn.heldCount > MAX_HELD_AUDIO_EVENTS) {
            applyVerdict(turn, turn.onHoldBudgetExceeded())
        }
        return true
    }

    private fun applyVerdict(target: DriverTurn, verdict: DriverTurn.Verdict) {
        when (verdict) {
            DriverTurn.Verdict.Wait -> Unit
            is DriverTurn.Verdict.Release -> {
                val pending = target.takeHeld()
                // A reply this client cancelled (a local pick answered the driver) is not shown
                // either: 「导航启动中，请说目的地。」 appeared as a subtitle during guidance (P40).
                if (host.responseCancelledByClient && pending.isNotEmpty()) {
                    DebugVoiceLog.log("TURN_DROP epoch=${target.epoch} reason=client_cancelled events=${pending.size}")
                } else if (pending.isNotEmpty()) {
                    DebugVoiceLog.log(
                        "TURN_RELEASE epoch=${target.epoch} reason=${verdict.reason} events=${pending.size}",
                    )
                    pending.forEach { host.emit(it as DomainVoiceEvent) }
                }
            }
            is DriverTurn.Verdict.Drop -> {
                val dropped = target.takeHeld().size
                DebugVoiceLog.log(
                    "TURN_DROP epoch=${target.epoch} reason=${verdict.reason} kind=${target.kind} " +
                        "proven=${target.proven} events=$dropped replyChars=${assistantText.length}" +
                        (verdict.detail?.let { " $it" } ?: ""),
                )
                Telemetry.record(EventType.AUDIO_STOPPED, detail = "turn_dropped_${verdict.reason}")
                // In standby the client sends no turns of its own, and a reply we cancelled
                // answered an utterance the app handled itself (P40): nothing to correct.
                verdict.correction?.takeIf { !host.listeningSuspended && !host.responseCancelledByClient }?.let {
                    correctionSentThisResponse = true
                    host.sendCorrection(it)
                }
            }
        }
    }

    /** The connection went away: held output can never be played, so it is discarded. */
    @Synchronized
    fun dropHeld(reason: String) {
        applyVerdict(turn, turn.cancel(reason))
    }

    private companion object {
        /** ~6 s of held reply at typical delta sizes: a ceiling, not an expected value. */
        const val MAX_HELD_AUDIO_EVENTS = 120
    }
}

/**
 * Whether a tool result proves something ran: top-level ok=true, or a `run_scenario` result
 * with status=partial and at least one step ok (SPEC-015 B8). A nested step's ok alone, as in
 * an all-failed scenario, proves nothing.
 */
internal fun toolResultProvesExecution(output: String): Boolean {
    val json = runCatching { JSONObject(output) }.getOrNull() ?: return output.contains("\"ok\":true")
    if (json.optBoolean("ok")) return true
    if (json.optString("tool") != "run_scenario" || json.optString("status") != "partial") return false
    val steps = json.optJSONArray("steps") ?: return false
    return (0 until steps.length()).any { steps.optJSONObject(it)?.optBoolean("ok") == true }
}
