package com.novadrive.app.voice

import com.novadrive.contracts.CapabilityCatalog
import com.novadrive.contracts.CapabilityIds
import com.novadrive.contracts.ProductCapabilities

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
class DriverTurn(
    val epoch: Long,
    /**
     * SPEC-014 clause release (owner decision 2026-10-09): the provider streams its reply's words
     * ahead of their audio ([com.novadrive.ingress.realtime.ProviderCapabilities.streamedReplyText]).
     * A chat reply ([HoldReason.UNCLASSIFIED_CLAIM]) is then released clause by clause, each only
     * once the words up to it pass the same check the response end applies. False: unchanged.
     */
    private val clauseRelease: Boolean = false,
) {

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

        /** A request with no tool at all (音量, 天窗 …). No proof is possible, ever. */
        NO_TOOL_ACTION,

        /**
         * A question about the world right now. Weather and route traffic can be answered from a
         * successful `query_live_info` of that kind this turn (SPEC-011 B3); news and prices never.
         */
        REALTIME_INFO,

        /** Chat, a question, a choice from a list. Its truth does not depend on execution. */
        CONVERSATION,

        /** 「你能做什么」 / capability inventory — reply must name supported groups, not claim action. */
        CAPABILITY_HELP,
    }

    /** Why the reply is being held. Reported in logs so a future agent can see the mechanism. */
    enum class HoldReason {
        NONE,
        PHANTOM_AUDIO,
        NO_TOOL_REQUEST,
        AWAITING_EXECUTION_PROOF,
        UNCLASSIFIED_CLAIM,
        CAPABILITY_HELP,

        /**
         * The turn began as speech over the assistant's own playback that did not qualify as a
         * barge-in (Astra P4): most likely the cabin echo of the reply. Nothing from it is heard
         * until the turn is confirmed by the driver's own words, a tool call or execution proof.
         */
        ECHO_CANDIDATE,

        /**
         * A tool this turn called has not returned its result yet, so nothing the model says now can
         * be grounded in it. Measured 2026-09-28 (demo 08:37): 「看看前面有什么」 called
         * describe_camera_view; a response created before the vision result said
         * 「抱歉，摄像头暂时无法使用。」, and seconds later the look succeeded. Takes precedence over
         * [proven]: that turn had been "proven" by an older set_speech_output result.
         */
        AWAITING_TOOL_RESULT,
    }

    sealed interface Verdict {
        /** Play and show what was held. */
        data class Release(val reason: String) : Verdict

        /**
         * Never play or show it; [correction] is sent to the model when non-null. [detail] is a
         * log-safe diagnostic (which predicate, which of our own vocabulary words) - never content.
         */
        data class Drop(val reason: String, val correction: String? = null, val detail: String? = null) : Verdict

        /** Keep holding: the turn is not finished. */
        data object Wait : Verdict

        /**
         * SPEC-014: play what was held up to [checkedChars] characters of checked words, and keep
         * holding the rest. The pipeline bounds the audio at [CLAUSE_AUDIO_MS_PER_CHAR] per
         * character, slower than any voice speaks, so audio never runs past the checked words.
         */
        data class ReleaseClauses(val checkedChars: Int) : Verdict

        /**
         * SPEC-014: the words so far would make the response end drop the reply. Nothing more is
         * released early; the rest waits for the end-of-response verdict. [predicate] is log-safe.
         */
        data class CloseClauseGate(val predicate: String) : Verdict
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

    /**
     * Speech started over playback without post-AEC speech evidence. Cleared once a transcript
     * that is not the assistant's own recent words arrives.
     */
    var echoCandidate: Boolean = false
        private set

    /** Call ids this turn dispatched whose result has not been delivered yet, with the tool name. */
    private val awaitingResults = linkedMapOf<String, String>()

    /** Every call id this turn dispatched, settled or not: a result for any other id is foreign (D-11). */
    private val registeredCalls = mutableSetOf<String>()

    /** Results for calls this turn never dispatched; ignored, counted for the diagnostic line (D-11). */
    var foreignResults: Int = 0
        private set

    /** Tools that were still running when the current response started. */
    private var awaitedAtResponseStart: Set<String> = emptySet()

    /** A tool of this turn is still running (e.g. a vision request of several seconds). */
    val awaitingToolResult: Boolean get() = awaitingResults.isNotEmpty()

    /** A tool result with `ok=true` came back. This, and only this, is proof. */
    var proven: Boolean = false
        private set

    /**
     * SPEC-017: the latest `play_music` result this turn did not read back a playing track
     * (requested_unverified or ok=false). Its ok=true proves the hand-off, not what is playing, so
     * a reply claiming a song is playing is held and dropped (I-1).
     */
    private var musicUnconfirmed = false

    /**
     * Where the reply stood when the unconfirmed result arrived. On Gemini the reply is spoken in
     * the same response as the call, so only the words after the result are judged against it.
     */
    private var musicResultAt = 0

    /** `query_live_info` kinds that returned `ok=true` this turn: the only source for REALTIME_INFO. */
    private val liveInfoKinds = mutableSetOf<String>()

    /** The last failure reason delivered for this turn, if any. */
    var lastFailure: String? = null
        private set

    /** A tool result with `ok=false` came back: the action was attempted and did not happen. */
    var executionFailed: Boolean = false
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
        awaitedAtResponseStart = awaitingResults.values.toSet()
        preCallHold = null
        midCallFailed = false
        holdUntilEnd = false
        midCallFailure = null
        replacedHold = null
        replySoFar = ""
        musicResultAt = 0
        replyBeforeResult = null
        midResponseTools.clear()
        wordsBeforeCall = 0
        clauseGateClosed = false
        clauseCheckedChars = 0
        holdReason = decideHold(contextAwaitingAnswer)
        return holdReason
    }

    /**
     * The provider heard speech while the assistant's reply was playing. [qualified] is the
     * app's own time-scoped post-AEC evidence; without it this turn is only a candidate, and the
     * no-claim release path must not make a reply to the cabin's echo audible.
     */
    fun onSpeechDuringPlayback(qualified: Boolean) {
        if (!accepts()) return
        if (!qualified) echoCandidate = true
    }

    private fun decideHold(contextAwaitingAnswer: Boolean): HoldReason {
        lastDecideContext = contextAwaitingAnswer
        // Before proof: proof of an earlier action says nothing about a result still being made.
        if (awaitingToolResult) return HoldReason.AWAITING_TOOL_RESULT
        if (musicUnconfirmed) return HoldReason.AWAITING_EXECUTION_PROOF
        // Proof of *some* action is not a source for the weather: only a live-info result of the
        // kind the driver asked about is (SPEC-011 B3).
        if (proven && (kind != Kind.REALTIME_INFO || answeredFromSource())) return HoldReason.NONE
        if (echoCandidate && !toolCalled) return HoldReason.ECHO_CANDIDATE
        if (kind == Kind.CAPABILITY_HELP) return HoldReason.CAPABILITY_HELP
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
            Kind.CAPABILITY_HELP -> HoldReason.CAPABILITY_HELP
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
    fun onUserTranscript(text: String, echoOf: String = "", classify: (String) -> Kind): HoldReason {
        if (!accepts()) {
            rejectedEvents++
            return holdReason
        }
        if (!PhantomTurnGate.isMeaningfulTranscript(text)) return holdReason
        if (echoCandidate) {
            // The recogniser transcribing the assistant's own reply back is the echo, not a driver.
            if (isEchoOf(text, echoOf)) return holdReason
            echoCandidate = false
            if (holdReason == HoldReason.ECHO_CANDIDATE) holdReason = HoldReason.NONE
        }
        userSpoke = true
        requestText = text
        if (kind == Kind.UNKNOWN) kind = classify(text)
        // A turn that was only held because its audio looked doubtful is now known to be real.
        if (holdReason == HoldReason.PHANTOM_AUDIO) holdReason = HoldReason.NONE
        // The transcript is what classifies the turn, and it usually arrives after the response
        // has started - so a hold taken in ignorance is re-decided now that the kind is known.
            // UNCLASSIFIED_CLAIM is exactly such a hold: leaving it in place would keep treating a
            // known CAPABILITY_HELP (or ACTION) as unclassified.
            if (holdReason == HoldReason.UNCLASSIFIED_CLAIM) holdReason = HoldReason.NONE
        if (holdReason == HoldReason.NONE && phase == Phase.RESPONDING) {
            holdReason = decideHold(contextAwaitingAnswer = false)
        }
        return holdReason
    }

    fun onToolCall(callId: String? = null, name: String? = null) {
        if (!accepts()) {
            rejectedEvents++
            return
        }
        toolCalled = true
        if (callId != null) {
            registeredCalls += callId
            awaitingResults[callId] = name.orEmpty()
            // D-10(a): a call registered after this response's hold was decided. Nothing said from
            // here on can be grounded in its result, so the response waits for it like one that
            // started with the call awaited; the hold it replaced is re-decided when the result is in.
            if (phase == Phase.RESPONDING && holdReason != HoldReason.AWAITING_TOOL_RESULT) {
                replacedHold = holdReason
                if (preCallHold == null) {
                    preCallHold = holdReason
                    wordsBeforeCall = replySoFar.length
                }
                holdReason = HoldReason.AWAITING_TOOL_RESULT
            }
            if (replacedHold != null) midResponseTools += name.orEmpty()
        }
    }

    /** The hold a mid-response call replaced (D-10(a)); null when no such call is pending. */
    private var replacedHold: HoldReason? = null

    /** `contextAwaitingAnswer` used by the most recent [decideHold], reused to restore a replaced hold. */
    private var lastDecideContext = false

    /**
     * The hold in force when this response's first mid-response call arrived (D-10(a)). Kept until
     * the response ends: the words said before the call are judged by its end-of-response rule.
     */
    private var preCallHold: HoldReason? = null

    /** A mid-response call of this response returned ok=false; [midCallFailure] is the first reason. */
    private var midCallFailed = false

    /** A mid-response result left something the response end may still drop: nothing is released before it. */
    private var holdUntilEnd = false
    private var midCallFailure: String? = null

    /** The assistant's wording so far in this response, as last passed to [onAssistantText]. */
    private var replySoFar = ""

    /**
     * Length of the (untrimmed) wording already said when the first mid-response call arrived.
     * Those words are judged at the response end by [preCallHold]'s own rule; what follows the
     * call is judged against the call's result.
     */
    private var wordsBeforeCall = 0

    private fun wordsSinceCall(text: String): String =
        if (text.length >= wordsBeforeCall) text.substring(wordsBeforeCall).trim() else text.trim()

    /**
     * The words said before a mid-response call, judged by the hold in force when they were said,
     * with the proof available now. No correction: the call is already running, and a nudge would
     * ask for the action again.
     */
    private fun judgeWordsBeforeCall(rawReply: String): Verdict? {
        val hold = preCallHold ?: return null
        val pre = rawReply.take(wordsBeforeCall).trim()
        if (pre.isEmpty()) return null
        val unproven = when (hold) {
            HoldReason.AWAITING_EXECUTION_PROOF -> !proven && ActionClaimGuard.claimsDone(pre)
            HoldReason.CAPABILITY_HELP -> !ActionClaimGuard.answersCapabilityHelp(pre)
            HoldReason.UNCLASSIFIED_CLAIM -> !proven && ActionClaimGuard.carActionClaimMatch(pre) != null
            else -> false
        }
        return if (unproven) Verdict.Drop("claim_before_call_unproven") else null
    }

    /** Tools called mid-response (D-10(a)) in this response. */
    private val midResponseTools = mutableSetOf<String>()

    /** Words stating an outcome were said before a mid-response call's result: never heard. */
    private var replyBeforeResult: Verdict.Drop? = null

    /**
     * The last result awaited by a mid-response call arrived (D-10(a)). Words said before it that
     * state an outcome are invented and the response is dropped at its end; otherwise the kind's
     * own hold is restored, now seeing [proven] / [executionFailed].
     */
    private fun settleMidResponseCall(): Verdict {
        replacedHold = null
        val words = wordsSinceCall(replySoFar)
        if (words.isNotEmpty()) {
            val claims = ActionClaimGuard.claimsDone(words) || ActionClaimGuard.carActionClaim(words) != null
            replyBeforeResult = when {
                // Nothing about the picture exists until the look returns.
                CAMERA_TOOL in midResponseTools -> Verdict.Drop("reply_before_tool_result")
                // A failure announced for an action that then succeeded.
                !midCallFailed && ActionClaimGuard.refuses(words) -> Verdict.Drop("reply_before_tool_result")
                // A done-claim while a call failed: the driver hears the failure (a2). Proof of
                // another call does not override it, whatever order the results came in.
                midCallFailed && claims -> Verdict.Drop(
                    "unproven_action_claim",
                    ActionClaimGuard.reportFailure(midCallFailure ?: "操作失败"),
                )
                // A done-claim proved by ok=true is true (D-7's designed release).
                else -> null
            }
            if (replyBeforeResult != null) return Verdict.Wait
        }
        holdReason = decideHold(lastDecideContext)
        // Only a response whose every mid-response result succeeded, and whose words before the
        // call pass, may be released now. Otherwise the response-end check may still drop it, and
        // a drop after release would come after the driver heard the claim (review RC1).
        if (midCallFailed || judgeWordsBeforeCall(replySoFar) != null) {
            holdUntilEnd = true
            if (holdReason == HoldReason.NONE) holdReason = HoldReason.AWAITING_TOOL_RESULT
            return Verdict.Wait
        }
        return when {
            holdReason != HoldReason.NONE -> Verdict.Wait
            proven -> Verdict.Release("execution_proved")
            else -> Verdict.Release("mid_call_result")
        }
    }

    /**
     * A tool result was delivered to the model. `ok=true` is the only thing in this system that
     * proves an external action happened; a failure explicitly does **not** release a claim.
     */
    fun onExecutionResult(
        ok: Boolean,
        failure: String?,
        liveInfoKind: String? = null,
        callId: String? = null,
        /** null: not a play_music result; true: status=playing read back; false: not confirmed. */
        musicConfirmed: Boolean? = null,
    ): Verdict {
        if (!accepts()) {
            rejectedEvents++
            return Verdict.Wait
        }
        if (musicConfirmed != null && (callId == null || callId in registeredCalls)) {
            musicUnconfirmed = !musicConfirmed
            if (musicUnconfirmed) musicResultAt = if (phase == Phase.RESPONDING) replySoFar.length else 0
        }
        // D-11 / I-1: only a result for a call this turn dispatched can prove or fail its action.
        // A superseded turn's late result is ignored; a null id keeps the legacy path.
        if (callId != null && callId !in registeredCalls) {
            foreignResults++
            return Verdict.Wait
        }
        if (callId != null) awaitingResults.remove(callId)
        if (holdReason == HoldReason.AWAITING_TOOL_RESULT && (replacedHold != null || holdUntilEnd)) {
            if (!ok) {
                lastFailure = failure
                executionFailed = true
                if (!midCallFailed) midCallFailure = failure
                midCallFailed = true
            } else {
                proven = true
                if (liveInfoKind != null) liveInfoKinds += liveInfoKind
            }
            return if (awaitingToolResult || replyBeforeResult != null) Verdict.Wait else settleMidResponseCall()
        }
        // A response created before this result cannot have used it; it is judged when it ends.
        if (holdReason == HoldReason.AWAITING_TOOL_RESULT) {
            if (!ok) {
                lastFailure = failure
                executionFailed = true
            } else {
                proven = true
                if (liveInfoKind != null) liveInfoKinds += liveInfoKind
            }
            return Verdict.Wait
        }
        if (ok && liveInfoKind != null) liveInfoKinds += liveInfoKind
        if (!ok) {
            lastFailure = failure
            executionFailed = true
            return Verdict.Wait
        }
        proven = true
        // An unconfirmed hand-off proves nothing about what is playing: judged at response end.
        if (musicUnconfirmed) return Verdict.Wait
        if (holdReason == HoldReason.AWAITING_EXECUTION_PROOF || holdReason == HoldReason.PHANTOM_AUDIO ||
            holdReason == HoldReason.ECHO_CANDIDATE
        ) {
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
        replySoFar = text
        return when (holdReason) {
            HoldReason.NONE -> Verdict.Wait
            HoldReason.PHANTOM_AUDIO ->
                if (!PhantomTurnGate.isContentlessReply(text)) {
                    // D-10(b): real content proves the turn is not a phantom, not that the rest of
                    // the reply claims nothing. It is judged at the end like any unclassified reply.
                    holdReason = HoldReason.UNCLASSIFIED_CLAIM
                    Verdict.Wait
                } else {
                    Verdict.Wait
                }
            // SPEC-014: chat, and an ability answer (each clause claim-free; whether the list is
            // complete is still the response end's verdict, which nudges for the rest).
            HoldReason.UNCLASSIFIED_CLAIM, HoldReason.CAPABILITY_HELP ->
                if (toolCalled || executionFailed || musicUnconfirmed) Verdict.Wait else clauseVerdict(text, ::chatClausePredicate)
            // A live-info question with no answer from its source: the end drops only invented
            // data (realtimeVerdict), and that is judged clause by clause the same way.
            HoldReason.NO_TOOL_REQUEST ->
                if (kind == Kind.REALTIME_INFO) clauseVerdict(text, ::realtimeClausePredicate) else Verdict.Wait
            // SPEC-017: the hand-off was proved but nothing read back what is playing. The end
            // drops only 「正在放《X》」, so the reply is released clause by clause until one says so.
            HoldReason.AWAITING_EXECUTION_PROOF ->
                if (musicUnconfirmed && proven && !executionFailed) clauseVerdict(text, ::musicClausePredicate) else Verdict.Wait
            // These can only be settled once the response is complete.
            HoldReason.ECHO_CANDIDATE,
            HoldReason.AWAITING_TOOL_RESULT,
            -> Verdict.Wait
        }
    }

    /** SPEC-014: the first clause that failed the check closed early release for this response. */
    private var clauseGateClosed = false

    /** Characters of this response's words already checked clean and released. */
    private var clauseCheckedChars = 0

    /**
     * SPEC-014 behaviour 3-4. The words up to the last complete clause are judged by the
     * [HoldReason.UNCLASSIFIED_CLAIM] end rule (a car-action or done claim, a repair request).
     * Clean: release up to them. Not clean: close the gate, and the response end decides as
     * before. Anything a tool, a proof or a failure touched waits for the end.
     */
    private fun clauseVerdict(text: String, endRule: (String) -> String?): Verdict {
        if (!clauseRelease || clauseGateClosed || awaitingToolResult || preCallHold != null) return Verdict.Wait
        val end = text.indexOfLast { it in CLAUSE_ENDS } + 1
        if (end <= clauseCheckedChars) return Verdict.Wait
        // A first release of 「嗯，」 is 450 ms of audio, then the player waits for the next clause
        // end; emulator 2026-10-09 measured 285-464 ms underruns exactly so. Start with enough.
        if (clauseCheckedChars == 0 && end < MIN_FIRST_CLAUSE_CHARS) return Verdict.Wait
        val predicate = endRule(text.substring(0, end))
        if (predicate != null) {
            clauseGateClosed = true
            return Verdict.CloseClauseGate(predicate)
        }
        clauseCheckedChars = end
        return Verdict.ReleaseClauses(end)
    }

    /**
     * Exactly the [HoldReason.UNCLASSIFIED_CLAIM] end rule's predicates (its done_claim is inside
     * carActionClaimMatch): a stricter check only closes the gate on replies the end then releases,
     * and the pause while the rest waits is heard (emulator 2026-10-09: a 0.33 s hole).
     */
    private fun chatClausePredicate(checked: String): String? =
        ActionClaimGuard.carActionClaimMatch(checked)?.predicate
            ?: "repair".takeIf { PhantomTurnGate.asksToRepeat(checked) }

    /** The end rule's unconfirmed_music_claim: only the words after the unconfirmed result count. */
    private fun musicClausePredicate(checked: String): String? =
        "unconfirmed_music_claim".takeIf {
            ActionClaimGuard.claimsMediaPlaying(checked.drop(musicResultAt.coerceAtMost(checked.length)))
        }

    /** The [realtimeVerdict] drop rule: live data stated with no successful lookup behind it. */
    private fun realtimeClausePredicate(checked: String): String? =
        if (answeredFromSource()) null
        else "fabricated_realtime_info".takeIf { ActionClaimGuard.fabricatesRealtimeInfo(checked) }

    /** A response finished. Returns what to do with anything held. */
    fun onResponseDone(assistantText: String, hadToolCallInResponse: Boolean): Verdict {
        if (!accepts()) {
            rejectedEvents++
            return Verdict.Wait
        }
        if (hadToolCallInResponse) toolCalled = true
        val reply = assistantText.trim()
        // D-10(a). Words before a mid-response call are judged by the hold they were said under;
        // words after it by the call's result. A pending result makes any stated outcome invented;
        // a failed call makes any done-claim a failure to report, before any tool_called release.
        val midCallPending = replacedHold != null && holdReason == HoldReason.AWAITING_TOOL_RESULT
        val postCall = if (preCallHold != null) wordsSinceCall(assistantText) else ""
        val postClaims = postCall.isNotEmpty() &&
            (ActionClaimGuard.claimsDone(postCall) || ActionClaimGuard.carActionClaim(postCall) != null)
        val preCallVerdict = judgeWordsBeforeCall(assistantText)
        val verdict = (
            if (midCallFailed && (postClaims || preCallVerdict != null)) {
                // A failed call is reported whichever side of the call the claim was said on.
                Verdict.Drop("unproven_action_claim", ActionClaimGuard.reportFailure(midCallFailure ?: "操作失败"))
            } else {
                null
            }
            ) ?: preCallVerdict ?: replyBeforeResult ?: when {
            midCallPending && postCall.isNotEmpty() && speaksBeforeResult(postCall) ->
                Verdict.Drop("reply_before_tool_result")
            else -> null
        } ?: when (holdReason) {
            HoldReason.NONE -> Verdict.Release("not_held")

            // No correction: the pending result's own delivery asks the model for the real answer.
            HoldReason.AWAITING_TOOL_RESULT -> when {
                hadToolCallInResponse || reply.isEmpty() -> Verdict.Release("tool_called")
                speaksBeforeResult(reply) -> Verdict.Drop("reply_before_tool_result")
                else -> Verdict.Release("no_claim_before_result")
            }

            // Nothing confirmed a driver: no words of their own, no tool, no proof. A reply that
            // claims nothing is exactly what used to be released here and heard as a self-loop.
            HoldReason.ECHO_CANDIDATE -> when {
                toolCalled || hadToolCallInResponse -> Verdict.Release("tool_called")
                else -> Verdict.Drop("unconfirmed_echo_candidate")
            }

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

            HoldReason.NO_TOOL_REQUEST -> when {
                kind == Kind.REALTIME_INFO -> realtimeVerdict(reply, hadToolCallInResponse)
                // A tool was found after all; it is no longer a no-tool request.
                toolCalled || hadToolCallInResponse -> Verdict.Release("tool_called")
                // ActionClaimGuard's own nudge already covers the correction.
                ActionClaimGuard.claimsDone(reply) -> Verdict.Drop("false_claim_unsupported")
                else -> Verdict.Release("honest_refusal")
            }

            HoldReason.CAPABILITY_HELP -> when {
                ActionClaimGuard.answersCapabilityHelp(reply) ->
                    Verdict.Release("capability_help")
                else ->
                    Verdict.Drop("help_incomplete", ActionClaimGuard.nudgeFor(requestText))
            }

            // The driver said something the app could not classify - usually because the
            // transcript is wrong. The reply is judged on its own terms: if it describes acting on
            // something in this car and nothing ran, the driver never hears it. The correction is
            // ActionClaimGuard's, so there is exactly one.
            HoldReason.UNCLASSIFIED_CLAIM -> when {
                toolCalled || hadToolCallInResponse -> Verdict.Release("tool_called")
                proven -> Verdict.Release("execution_proved")
                else -> ActionClaimGuard.carActionClaimMatch(reply)
                    ?.let { Verdict.Drop("unverified_claim_${it.predicate}", detail = it.describe()) }
                    ?: repairForHeardDriver(reply)
                    ?: Verdict.Release("no_claim_made")
            }

            HoldReason.AWAITING_EXECUTION_PROOF -> {
                when {
                    // SPEC-017: 「正在放《X》」 after a hand-off nothing confirmed is never heard.
                    // Regardless of a call in this response: only the words after the result count.
                    musicUnconfirmed &&
                        ActionClaimGuard.claimsMediaPlaying(assistantText.drop(musicResultAt.coerceAtMost(assistantText.length))) ->
                        Verdict.Drop(
                            "unconfirmed_music_claim",
                            if (executionFailed && !proven) {
                                ActionClaimGuard.reportFailure(lastFailure ?: "操作失败")
                            } else {
                                ActionClaimGuard.MUSIC_NOT_CONFIRMED
                            },
                        )
                    // Proof arrived while this response was in flight.
                    proven -> Verdict.Release("execution_proved")
                    // The model asserted an action that nothing has proved. This is the case that
                    // used to be corrected *after* the driver heard it.
                    // The tool already ran and failed: asking the model to perform it again is
                    // a retry nobody asked for. The driver must hear that it did not work.
                    // Found 2026-09-24 (HVAC_MODEL_IGNORES_ERROR): the nudge re-requested the
                    // action and the failure was never reported.
                    ActionClaimGuard.claimsDone(reply) && executionFailed ->
                        Verdict.Drop("unproven_action_claim", ActionClaimGuard.reportFailure(lastFailure ?: "操作失败"))
                    ActionClaimGuard.claimsDone(reply) ->
                        Verdict.Drop("unproven_action_claim", ActionClaimGuard.nudgeFor(requestText))
                    // A question, a refusal, a request to choose: its truth needs no execution.
                    else -> Verdict.Release("no_claim_made")
                }
            }
        }
        if (verdict !is Verdict.Wait) holdReason = HoldReason.NONE
        replacedHold = null
        replyBeforeResult = null
        midResponseTools.clear()
        wordsBeforeCall = 0
        preCallHold = null
        midCallFailed = false
        holdUntilEnd = false
        midCallFailure = null
        replySoFar = ""
        if (phase == Phase.RESPONDING) phase = Phase.SETTLED
        return verdict
    }

    /**
     * SPEC-011 B3. A reply about the weather or traffic is released when a lookup of that kind
     * succeeded this turn. An earlier tool call is not enough: a lookup that *failed* is followed by
     * a response of its own, and that response inventing a forecast is exactly the fabrication this
     * guards against. News and prices have no source, so [answeredFromSource] is never true for them.
     */
    private fun realtimeVerdict(reply: String, hadToolCallInResponse: Boolean): Verdict = when {
        hadToolCallInResponse -> Verdict.Release("tool_called")
        answeredFromSource() -> Verdict.Release("live_info_result")
        ActionClaimGuard.fabricatesRealtimeInfo(reply) -> Verdict.Drop(
            "fabricated_realtime_info",
            ActionClaimGuard.realtimeInfoCorrection(requestText, lookupFailed = executionFailed),
        )
        else -> Verdict.Release("honest_refusal")
    }

    /**
     * The model answered a chat turn with 「没听清，再说一遍」 although the recogniser heard a whole
     * sentence. Measured 2026-09-28 08:33 (owner demo): 「你办公室也不怎么吵。」 and a 30-character
     * sentence about the office were each answered 「我没听清，再说一遍。」 - the speech-to-speech
     * model judges the *audio*, and in a noisy cabin (or after its own 「没听清」 replies earlier in the
     * same conversation) it gives up on speech its own transcriber got. The driver is asked to
     * repeat something that was understood.
     *
     * Once per utterance the repair is dropped unheard and the model is given the transcript to
     * answer. If it still cannot make sense of it, its second repair is released as usual.
     */
    /**
     * A reply made while a result is still coming that states an outcome - a failure
     * (「摄像头暂时无法使用」), a completion, an action - is invented. While the camera is being
     * looked at, *anything* said is: there is no picture yet to describe or to fail on.
     */
    private fun speaksBeforeResult(reply: String): Boolean =
        CAMERA_TOOL in awaitedAtResponseStart ||
            ActionClaimGuard.refuses(reply) ||
            ActionClaimGuard.claimsDone(reply) ||
            ActionClaimGuard.carActionClaim(reply) != null

    private fun repairForHeardDriver(reply: String): Verdict? {
        if (repairRetried || kind != Kind.CONVERSATION || !userSpoke) return null
        if (!PhantomTurnGate.asksToRepeat(reply)) return null
        if (requestText.count { it.isLetterOrDigit() } < MIN_HEARD_SENTENCE_CHARS) return null
        repairRetried = true
        return Verdict.Drop("repair_for_heard_speech", ActionClaimGuard.answerHeardTranscript(requestText))
    }

    /** A repair of this utterance was already replaced by an answer to its transcript. */
    private var repairRetried = false

    private fun answeredFromSource(): Boolean =
        ActionClaimGuard.liveInfoKindFor(requestText)?.let { it in liveInfoKinds } == true

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

    /** SPEC-014: the held output from the front while [take] allows it, in order; the rest stays held. */
    fun takeHeldWhile(take: (Any) -> Boolean): List<Any> {
        val pending = mutableListOf<Any>()
        while (held.isNotEmpty() && take(held.first())) pending += held.removeAt(0)
        return pending
    }

    private fun accepts(): Boolean = phase != Phase.CANCELLED

    override fun toString(): String =
        "DriverTurn(epoch=$epoch phase=$phase kind=$kind userSpoke=$userSpoke toolCalled=$toolCalled " +
            "proven=$proven hold=$holdReason held=${held.size} awaiting=${awaitingResults.size})"

    companion object {
        /** The vision tool: nothing about the picture exists until its result does. */
        private const val CAMERA_TOOL = "describe_camera_view"

        /**
         * Whether [transcript] is the assistant's own [reply] heard back. Compares word characters
         * only, and only against what this app just said: never a list of driver phrases.
         */
        fun isEchoOf(transcript: String, reply: String): Boolean {
            val heard = transcript.filter { it.isLetterOrDigit() }
            val said = reply.filter { it.isLetterOrDigit() }
            if (heard.length < 2 || said.isEmpty()) return false
            if (said.contains(heard)) return true
            val bigrams = heard.windowed(2)
            return bigrams.count { said.contains(it) } >= bigrams.size * ECHO_BIGRAM_SHARE
        }

        /**
         * Word characters a transcript needs before 「没听清」 to it is second-guessed. 「你办公室也不怎
         * 么吵」 has nine; 「这个」「就这个」 (two, three) are fragments a repair is exactly right for.
         */
        const val MIN_HEARD_SENTENCE_CHARS = 5

        /** Share of the heard bigrams that must appear in the reply; recognition is not verbatim. */
        private const val ECHO_BIGRAM_SHARE = 0.6

        /** A clause ends at one of these (SPEC-014). A comma counts: a claim split there is judged by its prefix. */
        private val CLAUSE_ENDS = setOf('。', '！', '？', '，', '；', '…', '~', '～', '.', '!', '?', ',', ';', '\n')

        /** The first early release covers at least this many checked characters (900 ms of audio). */
        private const val MIN_FIRST_CLAUSE_CHARS = 6

        /**
         * SPEC-014 behaviour 3: audio released per checked character. Mandarin TTS runs about
         * 4-5 characters a second (200-250 ms each), so 150 ms keeps the audio behind the words.
         */
        const val CLAUSE_AUDIO_MS_PER_CHAR = 150

        /** Classifies a transcript into what its reply's truth depends on. */
        fun classify(
            text: String,
            catalog: CapabilityCatalog = ProductCapabilities,
        ): Kind = when {
            UtteranceIntentResolver.product().resolve(text)?.capabilityId ==
                CapabilityIds.SPEECH_CAPABILITY_HELP -> Kind.CAPABILITY_HELP
            ActionClaimGuard.isRealtimeInfoRequest(text) -> Kind.REALTIME_INFO
            ActionClaimGuard.isUnsupportedRequest(text, catalog) -> Kind.NO_TOOL_ACTION
            ActionClaimGuard.isControlRequest(text, catalog) ||
                ActionClaimGuard.isCameraQuestion(text) ||
                // 「有点热」 names no action, but it is a request for one: measured on device
                // 2026-09-19 answering it with a claim and no call was released unheld.
                ContextResolver.isImplicitComfortRequest(text) -> Kind.ACTION
            else -> Kind.CONVERSATION
        }
    }
}
