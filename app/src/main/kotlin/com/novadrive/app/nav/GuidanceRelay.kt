package com.novadrive.app.nav

import com.novadrive.app.voice.GuidanceClaims
import com.novadrive.ingress.realtime.DomainVoiceEvent

/** The realtime session, as the relay sees it. */
interface GuidanceSession {
    /** Null when a prompt may be sent now; otherwise why not (a code, never text). */
    fun blocker(): String?
    fun sendPrompt(text: String, promptId: String): Boolean
}

/** Amap's own voice: the fallback. */
fun interface GuidanceSpeaker {
    fun speak(text: String): Boolean
}

/** What the relay reads from, and tells, the speech arbiter. */
interface GuidanceArbiterView {
    fun amapSpeaking(): Boolean
    fun transientFocusLoss(): Boolean
    fun abandon(promptId: String)
}

fun interface Cancellable {
    fun cancel()
}

/**
 * SPEC-018 (ADR-014): per guidance sentence, who speaks it — 小诺 (the realtime model, verbatim)
 * or Amap's own voice. One prompt at a time, FIFO; PENDING → ASSISTANT | AMAP claimed once; the
 * deadline runs from enqueue; two consecutive deadline fallbacks start a cooldown; fidelity strikes
 * hand the rest of the navigation to Amap. Logs carry ids, codes and milliseconds only (I-8).
 */
class GuidanceRelay(
    private val clock: () -> Long,
    private val schedule: (Long, () -> Unit) -> Cancellable,
    private val session: GuidanceSession,
    private val amap: GuidanceSpeaker,
    private val arbiter: GuidanceArbiterView,
    private val enabled: () -> Boolean,
    private val deadlineMs: Long = 1_200,
    private val cooldownMs: Long = 60_000,
    private val strikeLimit: Int = 2,
    /** After COMPLETED, how long the final output transcription may still arrive (B7). */
    private val transcriptGraceMs: Long = 1_500,
    private val log: (String) -> Unit = { com.novadrive.app.DebugVoiceLog.log(it) },
) {
    private enum class State { QUEUED, PENDING, ASSISTANT }

    private class Prompt(val id: String, val text: String, val enqueuedAt: Long) {
        var state = State.QUEUED
        var deadline: Cancellable? = null
        var completed = false
        var drained = false
        var graceExpired = false
        var grace: Cancellable? = null
        val transcript = StringBuilder()
    }

    private val lock = Any()
    private val queue = ArrayDeque<Prompt>()
    private var current: Prompt? = null
    private var nextId = 0L
    private var consecutiveFallbacks = 0
    private var cooldownUntil = Long.MIN_VALUE
    private var strikes = 0

    fun onGuidanceText(text: String) {
        if (text.isBlank()) return
        synchronized(lock) {
            queue.addLast(Prompt("g${++nextId}", text, clock()))
            pump()
        }
    }

    /** The playback port is about to play the first chunk of [promptId]. */
    fun claimAssistant(promptId: String): Boolean = synchronized(lock) {
        val p = current?.takeIf { it.id == promptId && it.state == State.PENDING } ?: return@synchronized false
        p.state = State.ASSISTANT
        p.deadline?.cancel()
        p.deadline = null
        consecutiveFallbacks = 0
        log("guidance_claim prompt=${p.id} side=assistant waited_ms=${waited(p)}")
        true
    }

    /** B2a: the assistant's audio for [promptId] was cut before it completed. */
    fun onCut(promptId: String) = synchronized(lock) {
        val p = assistant(promptId) ?: return@synchronized
        if (p.completed) {
            // Its turn had completed; the audio stopped early. Treat as drained, judge as usual.
            p.drained = true
            settle(p)
            return@synchronized
        }
        release(p)
        toAmap(p, "CUT")
        pump()
    }

    /** The response to [promptId] completed. Fidelity waits for drain and the transcript grace. */
    fun onCompleted(promptId: String, transcript: String?) = synchronized(lock) {
        val p = assistant(promptId) ?: return@synchronized
        p.completed = true
        transcript?.let { p.transcript.append(it) }
        p.grace = schedule(transcriptGraceMs) { onGraceExpired(p) }
        settle(p)
    }

    /** Output transcription of [promptId] that arrived after COMPLETED (G-1e). */
    fun onLateTranscript(promptId: String, text: String) = synchronized(lock) {
        val p = assistant(promptId) ?: return@synchronized
        p.transcript.append(text)
        settle(p)
    }

    /** The player went quiet after [promptId]'s audio (B3: only now may anything else speak). */
    fun onDrained(promptId: String) = synchronized(lock) {
        val p = assistant(promptId) ?: return@synchronized
        p.drained = true
        settle(p)
    }

    private fun onGraceExpired(p: Prompt) = synchronized(lock) {
        if (current !== p) return@synchronized
        p.graceExpired = true
        settle(p)
    }

    private fun assistant(id: String): Prompt? = current?.takeIf { it.id == id && it.state == State.ASSISTANT }

    /**
     * Judge and release [p] once it completed and drained, and either a transcript is in or the
     * grace ran out. The next prompt waits for this, so no two voices overlap.
     */
    private fun settle(p: Prompt) {
        if (!p.completed || !p.drained) return
        val heard = p.transcript.toString().takeIf { it.isNotBlank() }
        if (heard == null && !p.graceExpired) return
        release(p)
        val result = GuidanceFidelity.compare(p.text, heard)
        log("guidance_fidelity prompt=${p.id} result=$result")
        when (result) {
            GuidanceFidelity.Result.MATCH -> Unit
            GuidanceFidelity.Result.UNKNOWN -> strikes++
            else -> {
                strikes++
                toAmap(p, "FIDELITY_$result")
            }
        }
        pump()
    }

    private fun release(p: Prompt) {
        p.grace?.cancel()
        p.grace = null
        if (current === p) current = null
    }

    fun onNavigationEnded() = synchronized(lock) {
        current?.let { it.deadline?.cancel(); it.grace?.cancel() }
        current = null
        queue.forEach { it.deadline?.cancel() }
        queue.clear()
        consecutiveFallbacks = 0
        cooldownUntil = Long.MIN_VALUE
        strikes = 0
    }

    private fun pump() {
        while (current == null) {
            val p = queue.removeFirstOrNull() ?: return
            val reason = amapReason(p)
            if (reason != null) {
                toAmap(p, reason)
                continue
            }
            if (!runCatching { session.sendPrompt(VERBATIM_PREFIX + p.text, p.id) }.getOrDefault(false)) {
                toAmap(p, "SEND_FAILED")
                continue
            }
            log("guidance_route prompt=${p.id} to=assistant reason=SENT waited_ms=${waited(p)}")
            p.state = State.PENDING
            current = p
            val remaining = deadlineMs - waited(p)
            p.deadline = schedule(remaining.coerceAtLeast(0)) { onDeadline(p) }
        }
    }

    private fun amapReason(p: Prompt): String? = when {
        !enabled() -> "DISABLED"
        waited(p) >= deadlineMs -> "EXPIRED"
        else -> session.blocker()
            ?: when {
                arbiter.amapSpeaking() -> "AMAP_SPEAKING"
                arbiter.transientFocusLoss() -> "FOCUS"
                clock() < cooldownUntil -> "COOLDOWN"
                strikes >= strikeLimit -> "FIDELITY_STRIKES"
                else -> null
            }
    }

    private fun onDeadline(p: Prompt) = synchronized(lock) {
        if (current !== p || p.state != State.PENDING) return@synchronized
        current = null
        p.deadline = null
        arbiter.abandon(p.id)
        log("guidance_claim prompt=${p.id} side=amap waited_ms=${waited(p)}")
        toAmap(p, "DEADLINE")
        if (++consecutiveFallbacks >= 2) {
            consecutiveFallbacks = 0
            cooldownUntil = clock() + cooldownMs
            log("guidance_cooldown ms=$cooldownMs")
        }
        pump()
    }

    private fun toAmap(p: Prompt, reason: String) {
        log("guidance_route prompt=${p.id} to=amap reason=$reason waited_ms=${waited(p)}")
        runCatching { amap.speak(p.text) }
    }

    private fun waited(p: Prompt): Long = clock() - p.enqueuedAt

    companion object {
        const val VERBATIM_PREFIX = "请一字不改地朗读下面这句导航提示，不要加任何别的话："

        /** The relay of the navigation in progress, or null (the toggle is off or nothing navigates). */
        @Volatile var active: GuidanceRelay? = null
            private set

        fun install(relay: GuidanceRelay) {
            active = relay
            GuidanceClaims.claimAssistant = { relay.claimAssistant(it) }
            GuidanceClaims.onGuidanceCut = { relay.onCut(it) }
            GuidanceClaims.onGuidanceDrained = { relay.onDrained(it) }
        }

        fun uninstall(relay: GuidanceRelay) {
            if (active !== relay) return
            active = null
            GuidanceClaims.claimAssistant = { true }
            GuidanceClaims.onGuidanceCut = {}
            GuidanceClaims.onGuidanceDrained = {}
            relay.onNavigationEnded()
        }
    }
}

/**
 * Collects the output transcription of the open guidance response (between OPENED and COMPLETED)
 * and hands it to the active relay for the fidelity check (B7). Never logs the text.
 */
object GuidanceTranscripts {
    private const val ASSISTANT_PREFIX = "小诺: "
    private var open: String? = null
    /** The last completed prompt: a final transcription after COMPLETED still belongs to it. */
    private var late: String? = null
    private val text = StringBuilder()

    fun onAppPromptTurn(promptId: String, phase: DomainVoiceEvent.AppPromptTurn.Phase) {
        val done: String? = synchronized(this) {
            when (phase) {
                DomainVoiceEvent.AppPromptTurn.Phase.OPENED -> { open = promptId; late = null; text.clear(); return }
                else -> {
                    if (open != promptId) return
                    open = null
                    late = promptId.takeIf { phase == DomainVoiceEvent.AppPromptTurn.Phase.COMPLETED }
                    text.toString().also { text.clear() }
                }
            }
        }
        if (phase == DomainVoiceEvent.AppPromptTurn.Phase.COMPLETED) {
            GuidanceRelay.active?.onCompleted(promptId, done?.takeIf { it.isNotBlank() })
        }
    }

    /** The session's transcript line; only the assistant's text of the open or last prompt is kept. */
    fun onTranscript(line: String) {
        if (!line.startsWith(ASSISTANT_PREFIX)) return
        val body = line.removePrefix(ASSISTANT_PREFIX)
        val lateId = synchronized(this) {
            if (open != null) { text.append(body); return }
            late
        } ?: return
        GuidanceRelay.active?.onLateTranscript(lateId, body)
    }
}
