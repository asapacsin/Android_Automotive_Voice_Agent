package com.novadrive.app

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import com.novadrive.app.ui.AssistantOverlayView
import com.novadrive.ingress.realtime.VoiceUiState

/**
 * Debug-only probe for B-035 (`TRANSCRIPT-FADE-EMU-001`): the real [AssistantOverlayView] with its
 * main-thread fade poll, without the map, the session or the network. That is enough to run on a
 * software-only emulator, where the full app starves the system.
 *
 * `adb shell am start -n com.novadrive.app/.TranscriptFadeProbeActivity --el speaking_ms 5000 --el held_ms 15000`
 * writes one exchange, keeps the turn state SPEAKING for `speaking_ms` and holds the bubble (as
 * audible playout or a waiting question would) for `held_ms`. The bubble must clear about
 * [com.novadrive.app.ui.TRANSCRIPT_FADE_MS] after the later of the two (log `transcript_bubble_faded`).
 */
class TranscriptFadeProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugVoiceLog.init(this)
        val speakingMs = intent.getLongExtra("speaking_ms", 0L)
        val heldMs = intent.getLongExtra("held_ms", 0L)
        val overlay = AssistantOverlayView(this)
        val start = SystemClock.uptimeMillis()
        overlay.transcriptHeld = { SystemClock.uptimeMillis() - start < heldMs }
        setContentView(overlay)
        overlay.bindState(if (speakingMs > 0) VoiceUiState.SPEAKING else VoiceUiState.LISTENING, null)
        DebugVoiceLog.log("transcript_probe start speaking_ms=$speakingMs held_ms=$heldMs")
        overlay.appendTranscript("你: 你能做什么?")
        overlay.appendTranscript("小诺: 我能帮你导航、放音乐、调空调。")
        if (speakingMs > 0) {
            overlay.postDelayed({
                overlay.bindState(VoiceUiState.LISTENING, null)
                DebugVoiceLog.log("transcript_probe state=LISTENING")
            }, speakingMs)
        }
    }
}
