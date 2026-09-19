package com.novadrive.app.voice

/**
 * Everything known about **one thing the driver said**, and the single authority on whether the
 * assistant's reply to it may be heard yet.
 *
 * ## Why this exists
 *
 * Two measured failures, both caused by per-turn facts living in independent flags:
 *
 * - 2026-09-18: the facts were reset per *response* rather than per *utterance*. One command
 *   produces several responses (the tool call, then the spoken result), so the second response
 *   looked like a turn nobody had spoken and 「音乐已开始播放。」 — the confirmation of a real action —
 *   was silenced.
 * - 2026-09-18: 「开始导航」 was answered 「导航已开始。」 *before* the tool ran. A guard corrected it
 *   afterwards, so the driver heard the claim, then heard it again once it was true.
 *
 * Independent booleans allowed combinations that mean nothing (`proven` without `toolCalled`,
 * `holding` after `cancelled`) and made ordering bugs invisible. Here the phase and the facts are
 * one object with one owner, transitions are explicit, and an event carrying a stale epoch cannot
 * touch a newer turn.
 *
 * ## The invariant it enforces
 *
 * **Only deterministic execution evidence may establish that an external action occurred.** The
 * model may *propose* an action; it may not *assert* that one happened. So when the driver asked
 * for an action, the reply's audio and subtitle wait until a tool result proves success — and in
 * the normal flow that proof already exists by the time the model speaks, so nothing is delayed.
 *
 * Never held, whatever the phase: the driver's transcript, the tool call itself, execution, and
 * any error. Only output whose truth depends on execution proof waits for it.
 *
 * Pure Kotlin; no Android, no clock, no I/O. Externally synchronised by [BaiduFlexClient].
 */
class DriverTurn(val epoch: Long) {

    enum class Phase {
        /** The driver is speaking, or has stopped and no response has started. */
        LISTENING,

        /** The model is producing one or more responses to this utterance. */
        RESPONDING,

        /** A response finished and nothing is held. The turn may still receive more responses. */
        SETTLED,

        /** Superseded by a newer turn, or the socket went away. Nothing more may be released. */
        CANCELLED,
    }

    /** What the driver asked for, which decides what their reply's truth depends on. */
    enum class Kind {
        UNKNOWN,

        /** A request this product can execute. Its confirmation needs execution proof. */
        ACTION,

        /** A request with no tool at all (音量, 车窗 …). No proof is possible, ever. */
        NO_TOOL_ACTION,

        /** A question about the world right now, which this car has no source for. */
        REALTIME_INFO,

        /** Chat, a question, a choice from a list. Its truth does not depend on execution. */
        CONVERSATION,
    }

    /** Why the reply is being held. Reported in logs so a future agent can see the mechanism. */
    enum class HoldReason { NONE, PHANTOM_AUDIO, NO_TOOL_REQUEST, AWAITING_EXECUTION_PROOF, UNCLASSIFIED_CLAIM }

    sealed interface Verdict {
        /** Play and show what was held. */
        data class Release(val reason: String) : Verdict

        /** Never play or show it; [correction] is sent to the model when non-null. */
        data class Drop(val reason: String, val correction: String? = null) : Verdict

        /** Keep holding: the turn is not finished. */
        data object Wait : Verdict
    }

    var phase: Phase = Phase.LISTENING
        private set

    var kind: Kind = Kind.UNKNOWN
        private set

    /** The provider transcribed a real request during this turn. */
    var userSpoke: Boolean = false
        private set

    /** A tool call was dispatched for this turn. Not proof — a call can fail. */
    var toolCalled: Boolean = false
        private set

    /** A tool result with `ok=true` came back. This, and only this, is proof. */
    var proven: Boolean = false
        private set

    /** The last failure reason delivered for this turn, if any. */
    var lastFailure: String? = null
        private set

    /** What the uplink gate measured about the audio that caused this turn. */
    var audio: SpeechUplinkGate.Segment? = null
        private set

    var holdReason: HoldReason = HoldReason.NONE
        private set

    /** Text the driver said, kept only to build a correction that can survive a reset. */
    var requestText: String = ""
        private set

    private val held = mutableListOf<Any>()

    /** Rejected transitions, for the diagnostic counter; an illegal move is never silent. */
    var rejectedEvents: Int = 0
        private set

    val isHolding: Boolean get() = holdReason != HoldReason.NONE

    val heldCount: Int get() = held.size

    // ---- transitions -------------------------------------------------------

    /** A response started. Decides whether this turn's output must wait, and why. */
    fun onResponseStarted(
        segment: SpeechUplinkGate.Segment?,
        contextAwaitingAnswer: Boolean,
    ): HoldReason {
        if (!accepts()) return HoldReason.NONE
        phase = Phase.RESPONDING
        if (audio == null) audio = segment
        holdReason = decideHold(contextAwaitingAnswer)
        return holdReason
    }

    private fun decideHold(contextAwaitingAnswer: Boolean): HoldReason {
        if (proven) return HoldReason.NONE
        // An action claim always needs proof. Measured on device 2026-09-18: with a route list on
        // screen, `contextAwaitingAnswer` exempted the whole turn, and a second 「导航已开始。」 was
        // spoken for a navigation that had not started. What is on screen says nothing about
        // whether an action happened; it only means a *prompt* to the driver is wanted.
        if (kind == Kind.ACTION) return HoldReason.AWAITING_EXECUTION_PROOF
        // The same exemption, one layer down: a prompt on screen means a *question* to the driver
        // is wanted, and says nothing about whether an action happened. A reply that claims one is
        // still false with a picker open, so an unclassified turn is judged on its own terms too.
        // A genuine prompt (「请在屏幕上选择」) claims nothing and is released at response end.
        if (kind == Kind.CONVERSATION || kind == Kind.UNKNOWN) {
            val doubtful = audio?.needsHold() == true && !userSpoke && !toolCalled
            return when {
                // Doubtful audio with a prompt on screen is the one case that must not wait: the
                // driver is mid-choice and a repair (「没听清，再说一遍。」) has to reach them.
                doubtful && contextAwaitingAnswer -> HoldReason.NONE
                doubtful -> HoldReason.PHANTOM_AUDIO
                else -> HoldReason.UNCLASSIFIED_CLAIM
            }
        }
        if (contextAwaitingAnswer) return HoldReason.NONE
        return when (kind) {
            // No tool exists, so proof never can. Hold until the wording is known to be honest.
            Kind.NO_TOOL_ACTION, Kind.REALTIME_INFO -> HoldReason.NO_TOOL_REQUEST
            // Handled above: an action claim needs proof whatever is on screen.
            Kind.ACTION -> HoldReason.AWAITING_EXECUTION_PROOF
            // Both handled above, whatever is on screen. Chat needs no execution proof, but its
            // classification came from a transcript and a transcript can be wrong: on 2026-09-19
            // 「返屋企啦」 arrived as 「发诺克拉。」, was classified as conversation, and the reply
            // announced a navigation that never happened. Measured cost of the wait: 93-515 ms,
            // and nothing at all when the turn already called a tool.
            Kind.CONVERSATION, Kind.UNKNOWN -> HoldReason.UNCLASSIFIED_CLAIM
        }
    }

    /**
     * The provider transcribed the driver. This is what classifies the turn, and it usually
     * arrives *after* the response has started — so the hold is re-evaluated here.
     */
    fun onUserTranscript(text: String, classify: (String) -> Kind): HoldReason {
        if (!accepts()) {
            rejectedEvents++
            return holdReason
        }
        if (!PhantomTurnGate.isMeaningfulTranscript(text)) return holdReason
        userSpoke = true
        requestText = text
        if (kind == Kind.UNKNOWN) kind = classify(text)
        // A turn that was only held because its audio looked doubtful is now known to be real.
        if (holdReason == HoldReason.PHANTOM_AUDIO) holdReason = HoldReason.NONE
        // The transcript is what classifies the turn, and it usually arrives after the response
        // has started - so a hold taken in ignorance is re-decided now that the kind is known.
        // UNCLASSIFIED_CLAIM is exactly such a hold: leaving it in place would keep treating a
        // known ACTION as unclassified, and an action claim released by execution proof mid-
        // response would instead wait for the response to end.
        if (holdReason == HoldReason.UNCLASSIFIED_CLAIM) holdReason = HoldReason.NONE
        if (holdReason == HoldReason.NONE && phase == Phase.RESPONDING) {
            holdReason = decideHold(contextAwaitingAnswer = false)
        }
        return holdReason
    }

    fun onToolCall() {
        if (!accepts()) {
            rejectedEvents++
            return
        }
        toolCalled = true
    }

    /**
     * A tool result was delivered to the model. `ok=true` is the only thing in this system that
     * proves an external action happened; a failure explicitly does **not** release a claim.
     */
    fun onExecutionResult(ok: Boolean, failure: String?): Verdict {
        if (!accepts()) {
            rejectedEvents++
            return Verdict.Wait
        }
        if (!ok) {
            lastFailure = failure
            return Verdict.Wait
        }
        proven = true
        if (holdReason == HoldReason.AWAITING_EXECUTION_PROOF || holdReason == HoldReason.PHANTOM_AUDIO) {
            holdReason = HoldReason.NONE
            return Verdict.Release("execution_proved")
        }
        return Verdict.Wait
    }

    /**
     * The assistant's completed wording for this response. A reply that carries real content is
     * released early; one that claims an action is kept waiting for proof.
     */
    fun onAssistantText(text: String): Verdict {
        if (!accepts()) {
            rejectedEvents++
            return Verdict.Wait
        }
        return when (holdReason) {
            HoldReason.NONE -> Verdict.Wait
            HoldReason.PHANTOM_AUDIO ->
                if (!PhantomTurnGate.isContentlessReply(text)) {
                    holdReason = HoldReason.NONE
                    Verdict.Release("real_reply")
                } else {
                    Verdict.Wait
                }
            // These can only be settled once the response is complete.
            HoldReason.NO_TOOL_REQUEST,
            HoldReason.AWAITING_EXECUTION_PROOF,
            HoldReason.UNCLASSIFIED_CLAIM,
            -> Verdict.Wait
        }
    }

    /** A response finished. Returns what to do with anything held. */
    fun onResponseDone(assistantText: String, hadToolCallInResponse: Boolean): Verdict {
        if (!accepts()) {
            rejectedEvents++
            return Verdict.Wait
        }
        if (hadToolCallInResponse) toolCalled = true
        val reply = assistantText.trim()
        val verdict = when (holdReason) {
            HoldReason.NONE -> Verdict.Release("not_held")

            HoldReason.PHANTOM_AUDIO -> {
                val judgement = PhantomTurnGate.judge(
                    PhantomTurnGate.Turn(
                        hadToolCall = hadToolCallInResponse || toolCalled,
                        contextAwaitingAnswer = false,
                        audio = audio,
                        assistantText = reply,
                        hadUserTranscript = userSpoke,
                    ),
                )
                when (judgement) {
                    is PhantomTurnGate.Verdict.Drop -> Verdict.Drop(judgement.reason)
                    PhantomTurnGate.Verdict.Speak -> Verdict.Release("genuine_turn")
                }
            }

            HoldReason.NO_TOOL_REQUEST -> {
                if (toolCalled || hadToolCallInResponse) {
                    // A tool was found after all; it is no longer a no-tool request.
                    Verdict.Release("tool_called")
                } else {
                    val fabricated = if (kind == Kind.REALTIME_INFO) {
                        ActionClaimGuard.fabricatesRealtimeInfo(reply)
                    } else {
                        ActionClaimGuard.claimsDone(reply)
                    }
                    if (fabricated) {
                        val why = if (kind == Kind.REALTIME_INFO) {
                            "fabricated_realtime_info"
                        } else {
                            "false_claim_unsupported"
                        }
                        val correction = if (kind == Kind.REALTIME_INFO) {
                            ActionClaimGuard.realtimeInfoCorrection(requestText)
                        } else {
                            null // ActionClaimGuard's own nudge already covers this case.
                        }
                        Verdict.Drop(why, correction)
                    } else {
                        Verdict.Release("honest_refusal")
                    }
                }
            }

            // The driver said something the app could not classify - usually because the
            // transcript is wrong. The reply is judged on its own terms: if it describes acting on
            // something in this car and nothing ran, the driver never hears it. The correction is
            // ActionClaimGuard's, so there is exactly one.
            HoldReason.UNCLASSIFIED_CLAIM -> when {
                toolCalled || hadToolCallInResponse -> Verdict.Release("tool_called")
                proven -> Verdict.Release("execution_proved")
                ActionClaimGuard.claimsDone(reply) || ActionClaimGuard.describesCarAction(reply) ->
                    Verdict.Drop("unverified_claim")
                else -> Verdict.Release("no_claim_made")
            }

            HoldReason.AWAITING_EXECUTION_PROOF -> {
                when {
                    // Proof arrived while this response was in flight.
                    proven -> Verdict.Release("execution_proved")
                    // The model asserted an action that nothing has proved. This is the case that
                    // used to be corrected *after* the driver heard it.
                    ActionClaimGuard.claimsDone(reply) ->
                        Verdict.Drop("unproven_action_claim", ActionClaimGuard.nudgeFor(requestText))
                    // A question, a refusal, a request to choose: its truth needs no execution.
                    else -> Verdict.Release("no_claim_made")
                }
            }
        }
        if (verdict !is Verdict.Wait) holdReason = HoldReason.NONE
        if (phase == Phase.RESPONDING) phase = Phase.SETTLED
        return verdict
    }

    /** The hold budget was exceeded. A real reply is never lost to a stuck gate. */
    fun onHoldBudgetExceeded(): Verdict {
        if (!accepts()) return Verdict.Wait
        holdReason = HoldReason.NONE
        return Verdict.Release("hold_budget_exceeded")
    }

    fun cancel(reason: String): Verdict {
        if (phase == Phase.CANCELLED) return Verdict.Wait
        phase = Phase.CANCELLED
        val wasHolding = isHolding
        holdReason = HoldReason.NONE
        // Nothing can be played after the turn is gone; held output is discarded, not emitted late.
        return if (wasHolding || held.isNotEmpty()) Verdict.Drop("cancelled_$reason") else Verdict.Wait
    }

    // ---- held output -------------------------------------------------------

    fun hold(event: Any) {
        held += event
    }

    fun takeHeld(): List<Any> {
        val pending = held.toList()
        held.clear()
        return pending
    }

    private fun accepts(): Boolean = phase != Phase.CANCELLED

    override fun toString(): String =
        "DriverTurn(epoch=$epoch phase=$phase kind=$kind userSpoke=$userSpoke toolCalled=$toolCalled " +
            "proven=$proven hold=$holdReason held=${held.size})"

    companion object {
        /** Classifies a transcript into what its reply's truth depends on. */
        fun classify(text: String): Kind = when {
            ActionClaimGuard.isRealtimeInfoRequest(text) -> Kind.REALTIME_INFO
            ActionClaimGuard.isUnsupportedRequest(text) -> Kind.NO_TOOL_ACTION
            ActionClaimGuard.isControlRequest(text) ||
                ActionClaimGuard.isCameraQuestion(text) ||
                // 「有点热」 names no action, but it is a request for one: measured on device
                // 2026-09-19 answering it with a claim and no call was released unheld.
                ContextResolver.isImplicitComfortRequest(text) -> Kind.ACTION
            else -> Kind.CONVERSATION
        }
    }
}
