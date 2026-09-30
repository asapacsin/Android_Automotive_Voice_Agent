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
    /** Amap's voice is speaking now (its listener, or the relay's own [bracket]). */
    fun amapSpeaking(): Boolean
    fun transientFocusLoss(): Boolean
    fun abandon(promptId: String)
    /** G-1b+: the relay's own fallback `playTTS` starts (true) or is taken as ended (false). */
    fun bracket(speaking: Boolean)
}

fun interface Cancellable {
    fun cancel()
}

/**
 * SPEC-018 (ADR-014): per guidance sentence, who speaks it — 小诺 (the realtime model, verbatim)
 * or Amap's own voice. One prompt at a time, FIFO; PENDING → ASSISTANT | AMAP claimed once; the
 * deadline runs from enqueue; two consecutive deadline fallbacks start a cooldown; fidelity strikes
 * hand the rest of the navigation to Amap. Every VOIDED and every session stop reach it (R1).
 * Amap never speaks over itself (G-1b+): a fallback waits for Amap's end, and the relay brackets
 * its own `playTTS` until the listener's end or a length-based estimate. Side effects (Amap, the
 * bracket) run outside the lock. Logs carry ids, codes and milliseconds only (I-8).
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
        var completed = false
        var drained = false
        var transcript: String? = null
    }

    private val lock = Any()
    private val queue = ArrayDeque<Prompt>()
    private var current: Prompt? = null
    private var nextId = 0L
    private var consecutiveFallbacks = 0
    private var cooldownUntil = Long.MIN_VALUE
    private var strikes = 0
    /** Sentences Amap must speak, in order, each only once Amap is quiet (G-1b+). */
    private val amapBacklog = ArrayDeque<String>()
    private var bracketOpen = false
    private var bracketTimer: Cancellable? = null
    private val effects = ArrayList<() -> Unit>()

    /**
     * Runs [block] under the lock, then the queued side effects outside it. Every entry is expected
     * on one thread (main on device; `install`'s `post` moves player callbacks there), so effects
     * keep their order; a debuggable build logs a violation.
     */
    private fun <T> locked(block: () -> T): T {
        checkMainLooper()
        val result = synchronized(lock) { block() }
        while (true) {
            val effect = synchronized(lock) { effects.removeFirstOrNull() } ?: break
            runCatching { effect() }
        }
        return result
    }

    fun onGuidanceText(text: String) {
        if (text.isBlank()) return
        locked {
            queue.addLast(Prompt("g${++nextId}", text, clock()))
            pump()
        }
    }

    /** The playback port is about to play the first chunk of [promptId]. */
    fun claimAssistant(promptId: String): Boolean = locked {
        val p = current?.takeIf { it.id == promptId && it.state == State.PENDING } ?: return@locked false
        p.state = State.ASSISTANT
        p.deadline?.cancel()
        p.deadline = null
        consecutiveFallbacks = 0
        log("guidance_claim prompt=${p.id} side=assistant waited_ms=${waited(p)}")
        true
    }

    /** B2a: the assistant's audio for [promptId] was cut before it completed. */
    fun onCut(promptId: String) = locked {
        val p = assistant(promptId) ?: return@locked
        if (p.completed) {
            // Its turn had completed; the audio stopped early. Treat as drained, judge as usual.
            p.drained = true
            settle(p)
            return@locked
        }
        release(p)
        toAmap(p, "CUT")
        pump()
    }

    /** The response to [promptId] completed, with its typed output transcription (B7). */
    fun onCompleted(promptId: String, transcript: String?) = locked {
        // PENDING too: the claim is made when queued audio becomes audible, so COMPLETED can arrive
        // first. Record it without claiming; the deadline stays armed (Amap still takes it then).
        val p = current?.takeIf { it.id == promptId && (it.state == State.PENDING || it.state == State.ASSISTANT) }
            ?: return@locked
        p.completed = true
        p.transcript = transcript
        settle(p)
    }

    /** R1: the prompt's turn was voided. PENDING → abandon; ASSISTANT → Amap re-speaks (B2a). */
    fun onVoided(promptId: String) = locked {
        val p = current?.takeIf { it.id == promptId } ?: return@locked
        interrupt(p, "VOIDED")
    }

    /** R1: the session stopped; an open prompt is spoken by Amap, and the queue moves on. */
    fun onSessionStopped() = locked {
        current?.let { interrupt(it, "SESSION_STOPPED") }
    }

    private fun interrupt(p: Prompt, reason: String) {
        release(p)
        if (p.state == State.PENDING) {
            p.deadline?.cancel()
            p.deadline = null
            arbiter.abandon(p.id)
        }
        toAmap(p, reason)
        pump()
    }

    /** The player went quiet after [promptId]'s audio (B3: only now may anything else speak). */
    fun onDrained(promptId: String) = locked {
        val p = assistant(promptId) ?: return@locked
        p.drained = true
        settle(p)
    }

    /** Amap's voice started or ended (its listener); an end closes the relay's bracket. */
    fun onAmapSpeaking(speaking: Boolean) = locked {
        if (speaking) return@locked
        closeBracket()
        speakAmapIfQuiet()
        pump()
    }

    private fun onBracketEstimate() = locked {
        if (!bracketOpen) return@locked
        closeBracket()
        effects += { arbiter.bracket(false) }
        speakAmapIfQuiet()
        pump()
    }

    private fun closeBracket() {
        bracketOpen = false
        bracketTimer?.cancel()
        bracketTimer = null
    }

    private fun assistant(id: String): Prompt? = current?.takeIf { it.id == id && it.state == State.ASSISTANT }

    /** Judge and release [p] at max(COMPLETED, drained); the next prompt waits for this (no overlap). */
    private fun settle(p: Prompt) {
        if (p.state != State.ASSISTANT || !p.completed || !p.drained) return
        release(p)
        val result = GuidanceFidelity.compare(p.text, p.transcript?.takeIf { it.isNotBlank() })
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
        if (current === p) current = null
    }

    fun onNavigationEnded() = locked {
        current?.deadline?.cancel()
        current = null
        queue.forEach { it.deadline?.cancel() }
        queue.clear()
        amapBacklog.clear()
        closeBracket()
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
                bracketOpen || amapBacklog.isNotEmpty() || arbiter.amapSpeaking() -> "AMAP_SPEAKING"
                arbiter.transientFocusLoss() -> "FOCUS"
                clock() < cooldownUntil -> "COOLDOWN"
                strikes >= strikeLimit -> "FIDELITY_STRIKES"
                else -> null
            }
    }

    private fun onDeadline(p: Prompt) = locked {
        if (current !== p || p.state != State.PENDING) return@locked
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
        amapBacklog.addLast(p.text)
        speakAmapIfQuiet()
    }

    /** G-1b+: never `playTTS` while Amap speaks; bracket our own until its end or the estimate. */
    private fun speakAmapIfQuiet() {
        if (bracketOpen || arbiter.amapSpeaking()) return
        val text = amapBacklog.removeFirstOrNull() ?: return
        bracketOpen = true
        val timer = schedule(maxOf(BRACKET_MIN_MS, BRACKET_MS_PER_CHAR * text.length)) { onBracketEstimate() }
        bracketTimer = timer
        effects += {
            arbiter.bracket(true)
            val accepted = runCatching { amap.speak(text) }.getOrDefault(false)
            // Refused (the speaker logs `nav_guidance_fallback_tts accepted=false`): nothing is
            // speaking, so close this bracket now rather than hold it for the estimate.
            if (!accepted) onSpeakRefused(timer)
        }
    }

    private fun onSpeakRefused(timer: Cancellable) = locked {
        if (!bracketOpen || bracketTimer !== timer) return@locked
        closeBracket()
        effects += { arbiter.bracket(false) }
        speakAmapIfQuiet()
        pump()
    }

    private fun checkMainLooper() {
        if (!com.novadrive.app.DebugVoiceLog.isEnabled) return // debuggable builds only; off in JVM tests
        val onMain = runCatching { android.os.Looper.myLooper() === android.os.Looper.getMainLooper() }.getOrDefault(true)
        if (!onMain) com.novadrive.app.DebugVoiceLog.log("guidance_relay_off_main_looper")
    }

    private fun waited(p: Prompt): Long = clock() - p.enqueuedAt

    companion object {
        const val VERBATIM_PREFIX = "请一字不改地朗读下面这句导航提示，不要加任何别的话："
        const val BRACKET_MS_PER_CHAR = 300L
        const val BRACKET_MIN_MS = 1_500L

        /** The relay of the navigation in progress, or null (the toggle is off or nothing navigates). */
        @Volatile var active: GuidanceRelay? = null
            private set

        /** R7: told when [active] changes, so the lifecycle re-reads whether guidance keeps SLEEP connected. */
        @Volatile var onActiveChanged: () -> Unit = {}

        /** [post] moves player-thread callbacks to the relay's thread (main on device). */
        fun install(relay: GuidanceRelay, post: (() -> Unit) -> Unit = { it() }) {
            active = relay
            GuidanceClaims.claimAssistant = { relay.claimAssistant(it) }
            GuidanceClaims.onGuidanceCut = { id -> post { relay.onCut(id) } }
            GuidanceClaims.onGuidanceDrained = { id -> post { relay.onDrained(id) } }
            GuidanceClaims.onSessionStopped = {
                GuidanceTranscripts.reset()
                post { relay.onSessionStopped() }
            }
            onActiveChanged()
        }

        fun uninstall(relay: GuidanceRelay) {
            if (active !== relay) return
            active = null
            GuidanceClaims.claimAssistant = { true }
            GuidanceClaims.onGuidanceCut = {}
            GuidanceClaims.onGuidanceDrained = {}
            GuidanceClaims.onSessionStopped = {}
            GuidanceTranscripts.reset()
            relay.onNavigationEnded()
            onActiveChanged()
        }
    }
}

/**
 * Collects the typed output transcription of the open guidance response
 * (`onAppPromptTranscript`, never a UI line) and forwards every phase to the active relay: the
 * full text at COMPLETED (B7), VOIDED as such (R1). Never logs the text.
 */
object GuidanceTranscripts {
    private var open: String? = null
    private val text = StringBuilder()

    fun onAppPromptTurn(promptId: String, phase: DomainVoiceEvent.AppPromptTurn.Phase) {
        val done: String? = synchronized(this) {
            when (phase) {
                DomainVoiceEvent.AppPromptTurn.Phase.OPENED -> { open = promptId; text.clear(); return }
                else -> {
                    val mine = open == promptId
                    if (mine) open = null
                    if (mine) text.toString().also { text.clear() } else null
                }
            }
        }
        val relay = GuidanceRelay.active ?: return
        if (phase == DomainVoiceEvent.AppPromptTurn.Phase.COMPLETED) {
            relay.onCompleted(promptId, done?.takeIf { it.isNotBlank() })
        } else {
            relay.onVoided(promptId)
        }
    }

    /** The typed transcript of a GUIDANCE turn; only the open prompt's text is kept. */
    fun onAppPromptTranscript(promptId: String, fragment: String) = synchronized(this) {
        if (open == promptId) text.append(fragment)
    }

    /** R2: the session stopped. */
    fun reset() = synchronized(this) {
        open = null
        text.clear()
    }
}
