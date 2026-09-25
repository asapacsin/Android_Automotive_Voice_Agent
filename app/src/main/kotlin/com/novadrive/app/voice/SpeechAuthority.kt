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
                SpeechArbiter.Reply.HOLD -> "guidance_or_focus"
                SpeechArbiter.Reply.PLAY -> "permitted"
            }
            DebugVoiceLog.log("speech_arbiter out=reply decision=$now reason=$reason")
        }
        return now
    }

    /** Tests only: a fresh arbiter, optionally on a controlled clock. */
    fun resetForTest(clock: () -> Long = System::currentTimeMillis) {
        arbiter = SpeechArbiter(clock = clock)
        lastUplink = SpeechArbiter.Uplink.OPEN
        lastReply = SpeechArbiter.Reply.PLAY
    }
}
