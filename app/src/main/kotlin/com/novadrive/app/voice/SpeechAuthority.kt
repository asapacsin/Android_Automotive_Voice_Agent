package com.novadrive.app.voice

import com.novadrive.app.DebugVoiceLog

/**
 * SPEC-012: the process's one [SpeechArbiter]. The playback port, the focus path, the guidance
 * listener and the microphone ask it — and only it — whether 小诺 may speak and whether the uplink
 * may reach Baidu. Inputs arrive as events from their owners (`NavigationState` for navigating,
 * the dispatcher and router for the permitted window, `NavigationGuidanceVoice` for guidance).
 *
 * The arbiter replaces `GuidanceMicGate`'s timers with its clock, so the uplink answer is read per
 * captured frame; each change is logged once (B2).
 */
object SpeechAuthority {
    @Volatile
    var arbiter: SpeechArbiter = SpeechArbiter(clock = System::currentTimeMillis)
        private set

    @Volatile private var lastUplink: SpeechArbiter.Uplink = SpeechArbiter.Uplink.OPEN
    @Volatile private var lastReply: SpeechArbiter.Reply = SpeechArbiter.Reply.PLAY

    /** Whether the microphone must not reach Baidu now (R1–R3). */
    fun uplinkClosed(): Boolean {
        val now = arbiter.uplink()
        if (now != lastUplink) {
            lastUplink = now
            val reason = if (now == SpeechArbiter.Uplink.CLOSED) "guidance" else "guidance_clear"
            DebugVoiceLog.log("speech_arbiter out=uplink decision=$now reason=$reason")
            DebugVoiceLog.log("nav_guidance_mic_gate closed=${now == SpeechArbiter.Uplink.CLOSED}")
        }
        return now == SpeechArbiter.Uplink.CLOSED
    }

    /** The decision for a new chunk of reply audio, logged when it changes. */
    fun reply(): SpeechArbiter.Reply {
        val a = arbiter
        val now = a.reply()
        if (now != lastReply) {
            lastReply = now
            val reason = when (now) {
                SpeechArbiter.Reply.DROP -> if (a.navigationMuted()) "navigation_unprompted" else "focus_lost"
                SpeechArbiter.Reply.HOLD -> if (a.workloadHeld()) "workload" else "guidance_or_focus"
                SpeechArbiter.Reply.PLAY -> "permitted"
            }
            DebugVoiceLog.log("speech_arbiter out=reply decision=$now reason=$reason")
        }
        return now
    }

    /**
     * The one playback-hold hook (D1), registered by `AndroidPlaybackPort`: true pauses the player,
     * false resumes it. Only [syncPlaybackHold] calls it, and only when the answer changes.
     */
    @Volatile var playbackHold: ((pause: Boolean) -> Unit)? = null

    /** Posts a delayed re-check (main looper on device; set by `AndroidPlaybackPort`). No-op by default. */
    @Volatile var scheduleRecheck: (delayMs: Long, task: () -> Unit) -> Unit = { _, _ -> }

    private val holdLock = Any()
    private var pauseApplied = false
    private var replyQueued = false
    private var recheckPosted = false
    private var lastBucket: String? = null

    /** R6a input: metres to the next manoeuvre, or null. Only the bucket is ever logged. */
    fun onManeuverDistance(meters: Int?) {
        val line = maneuverLogLine(meters)
        if (line != lastBucket) {
            lastBucket = line
            DebugVoiceLog.log(line)
        }
        arbiter.onManeuverDistance(meters)
        syncPlaybackHold()
    }

    /** The only maneuver log line: a fixed bucket, never the distance itself (B2, location privacy). */
    internal fun maneuverLogLine(meters: Int?): String {
        val bucket = when {
            meters == null -> "unknown"
            meters < SpeechArbiter.WORKLOAD_HOLD_DISTANCE_M -> "<150"
            else -> ">=150"
        }
        return "speech_arbiter in=maneuver bucket=$bucket"
    }

    /** A guidance/focus path paused the player itself; the next [syncPlaybackHold] may lift it. */
    fun notePaused() = synchronized(holdLock) { pauseApplied = true }

    /**
     * A non-DROP chunk: extend the window; the reply counts as started only if it plays now (D2).
     * [queued] is whether the chunk actually reached the player.
     */
    fun onReplyChunk(decision: SpeechArbiter.Reply, queued: Boolean) {
        arbiter.onReplyAudio(started = decision == SpeechArbiter.Reply.PLAY && queued)
        if (queued) synchronized(holdLock) { replyQueued = true }
    }

    /** The reply ended, was flushed, or a new one begins: nothing has started playing. */
    fun onReplyEnded() {
        arbiter.onReplyEnded()
        synchronized(holdLock) { replyQueued = false }
    }

    /**
     * D1: pause exactly when the arbiter says HOLD; the only way a held reply resumes. Decision and
     * apply are one atomic step under [holdLock], so concurrent syncs cannot apply a stale answer.
     * Lock order: holdLock → arbiter lock, holdLock → player outputLock; neither is ever reversed.
     */
    fun syncPlaybackHold() {
        var startReply = false
        var post = false
        synchronized(holdLock) {
            val pause = reply() == SpeechArbiter.Reply.HOLD
            val workload = pause && arbiter.workloadHeld()
            if (pause != pauseApplied) {
                pauseApplied = pause
                startReply = !pause && replyQueued
                runCatching { playbackHold?.invoke(pause) }
            }
            if (workload && !recheckPosted) {
                recheckPosted = true
                post = true
            }
            if (!workload) recheckPosted = false
        }
        if (startReply) arbiter.onReplyAudio(started = true)
        if (post) {
            scheduleRecheck(SpeechArbiter.WORKLOAD_HOLD_MAX_MS + RECHECK_MARGIN_MS) { syncPlaybackHold() }
        }
    }

    private const val RECHECK_MARGIN_MS = 100L

    /** Tests only: a fresh arbiter, optionally on a controlled clock. */
    fun resetForTest(clock: () -> Long = System::currentTimeMillis) {
        arbiter = SpeechArbiter(clock = clock)
        lastUplink = SpeechArbiter.Uplink.OPEN
        lastReply = SpeechArbiter.Reply.PLAY
        playbackHold = null
        scheduleRecheck = { _, _ -> }
        synchronized(holdLock) {
            pauseApplied = false
            replyQueued = false
            recheckPosted = false
        }
        lastBucket = null
    }
}
