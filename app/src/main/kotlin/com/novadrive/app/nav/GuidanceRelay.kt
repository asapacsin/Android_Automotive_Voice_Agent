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
    private val log: (String) -> Unit = { com.novadrive.app.DebugVoiceLog.log(it) },
) {
    private enum class State { QUEUED, PENDING, ASSISTANT }

    private class Prompt(val id: String, val text: String, val enqueuedAt: Long) {
        var state = State.QUEUED
        var deadline: Cancellable? = null
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
        val p = current?.takeIf { it.id == promptId && it.state == State.ASSISTANT } ?: return@synchronized
        current = null
        toAmap(p, "CUT")
        pump()
    }

    /** The response to [promptId] completed; [transcript] is its output transcription, if any. */
    fun onCompleted(promptId: String, transcript: String?) = synchronized(lock) {
        val p = current?.takeIf { it.id == promptId && it.state == State.ASSISTANT } ?: return@synchronized
        current = null
        val result = GuidanceFidelity.compare(p.text, transcript)
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

    fun onNavigationEnded() = synchronized(lock) {
        current?.deadline?.cancel()
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
        }

        fun uninstall(relay: GuidanceRelay) {
            if (active !== relay) return
            active = null
            GuidanceClaims.claimAssistant = { true }
            GuidanceClaims.onGuidanceCut = {}
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
    private val text = StringBuilder()

    fun onAppPromptTurn(promptId: String, phase: DomainVoiceEvent.AppPromptTurn.Phase) {
        val done: String? = synchronized(this) {
            when (phase) {
                DomainVoiceEvent.AppPromptTurn.Phase.OPENED -> { open = promptId; text.clear(); return }
                else -> {
                    if (open != promptId) return
                    open = null
                    text.toString().also { text.clear() }
                }
            }
        }
        if (phase == DomainVoiceEvent.AppPromptTurn.Phase.COMPLETED) {
            GuidanceRelay.active?.onCompleted(promptId, done?.takeIf { it.isNotBlank() })
        }
    }

    /** The session's transcript line; only the assistant's text inside an open prompt is kept. */
    fun onTranscript(line: String) = synchronized(this) {
        if (open != null && line.startsWith(ASSISTANT_PREFIX)) text.append(line.removePrefix(ASSISTANT_PREFIX))
    }
}
