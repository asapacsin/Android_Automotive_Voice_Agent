package com.novadrive.app.nav.amap

import com.amap.api.navi.AMapNavi
import com.amap.api.navi.TTSPlayListener
import com.amap.api.navi.enums.BroadcastMode
import com.novadrive.app.DebugVoiceLog
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.novadrive.app.nav.Cancellable
import com.novadrive.app.nav.GuidanceArbiterView
import com.novadrive.app.nav.GuidanceRelay
import com.novadrive.app.nav.GuidanceSession
import com.novadrive.app.nav.GuidanceSpeaker
import com.novadrive.app.nav.NavigationGuidanceVoice
import com.novadrive.app.voice.SpeechAuthority
import com.novadrive.app.voice.VoiceSessionGateway
import com.novadrive.app.voice.HostAudioTap

/**
 * Spoken turn-by-turn guidance. Never enabled before 2026-09-17: navigation ran silently
 * because the SDK's own voice is off unless asked for, and the guidance text callback was
 * discarded. The SDK ships an offline Mandarin voice (assets/tts), so no extra service.
 *
 * The play listener is what keeps guidance out of the assistant's ears (P3): see
 * [NavigationGuidanceVoice]. Logs never carry the guidance text (it names places).
 */
internal object AmapGuidanceVoice : TTSPlayListener {
    /** The live host's text panel (`TranslatedAbi.amapNativeSafe` false only), else null. */
    @Volatile var textReceiver: ((String) -> Unit)? = null

    /** SPEC-018: the relay of this navigation when 「助手播报导航」 is on, else null (today's path). */
    @Volatile private var relay: GuidanceRelay? = null

    override fun onPlayStart(text: String?) {
        DebugVoiceLog.log("nav_guidance_play_start chars=${text?.length ?: 0}")
        NavigationGuidanceVoice.onPlayStart()
        if (text.isNullOrBlank() || relay != null) return
        feedText(text)
    }

    /** SPEC-018 B10: with Amap muted the text arrives here; the panel and host tap move with it. */
    fun onNavigationText(text: String?) {
        val active = relay ?: return
        if (text.isNullOrBlank()) return
        feedText(text)
        active.onGuidanceText(text)
    }

    private fun feedText(text: String) {
        // Debug host bridge only (null otherwise): the emulator speaker is inaudible, so the
        // guidance text is spoken again on the PC.
        runCatching { HostAudioTap.guidanceSink?.invoke(text) }
        textReceiver?.invoke(text)
    }

    override fun onPlayEnd(text: String?) {
        DebugVoiceLog.log("nav_guidance_play_end")
        NavigationGuidanceVoice.onPlayEnd()
    }

    /** SPEC-018 developer toggle, set by the host before [enable]; false = today's Amap voice. */
    @Volatile var assistantVoice: Boolean = false

    fun enable(navi: AMapNavi?) {
        navi ?: return
        runCatching {
            // SPEC-018: with the toggle on Amap is muted and hands us the text; off = today's voice.
            if (assistantVoice) navi.setUseInnerVoice(false, true) else navi.setUseInnerVoice(true, false)
            navi.addTTSPlayListener(this)
        }.onSuccess {
            val on = runCatching { navi.getIsUseInnerVoice() }.getOrDefault(false)
            DebugVoiceLog.log("nav_guidance_voice enabled=$on")
        }.onFailure {
            DebugVoiceLog.log("nav_guidance_voice enabled=false exception=${it.javaClass.simpleName}")
        }
        applyConciseBroadcast(navi)
        if (assistantVoice) installRelay(navi)
    }

    private fun installRelay(navi: AMapNavi) {
        val handler = Handler(Looper.getMainLooper())
        val relay = GuidanceRelay(
            clock = { SystemClock.elapsedRealtime() },
            schedule = { delayMs, task ->
                val runnable = Runnable { task() }
                handler.postDelayed(runnable, delayMs)
                Cancellable { handler.removeCallbacks(runnable) }
            },
            session = object : GuidanceSession {
                override fun blocker(): String? = VoiceSessionGateway.guidanceBlocker()
                override fun sendPrompt(text: String, promptId: String) = VoiceSessionGateway.sendPrompt(text, promptId)
            },
            amap = GuidanceSpeaker { text ->
                val accepted = runCatching { navi.playTTS(text, true) }.getOrDefault(false)
                DebugVoiceLog.log("nav_guidance_fallback_tts accepted=$accepted")
                accepted
            },
            arbiter = object : GuidanceArbiterView {
                override fun amapSpeaking() = NavigationGuidanceVoice.speaking
                override fun transientFocusLoss() = SpeechAuthority.arbiter.guidanceHeld()
                override fun abandon(promptId: String) = SpeechAuthority.arbiter.abandon(promptId)
            },
            enabled = { true },
        )
        this.relay = relay
        GuidanceRelay.install(relay)
        DebugVoiceLog.log("nav_guidance_relay installed=true")
    }

    /**
     * Concise guidance (2026-09-28): on the owner's simulated drive the default mode spoke about
     * every 3–7 s and the uplink was closed for 60–70% of the drive (P3 gates it while Amap speaks),
     * so driver commands were lost. [BroadcastMode.CONCISE] keeps manoeuvre and camera prompts and
     * drops the road/traffic narration between them.
     */
    private fun applyConciseBroadcast(navi: AMapNavi) {
        val accepted = runCatching { navi.setBroadcastMode(BroadcastMode.CONCISE) }.getOrDefault(false)
        val mode = runCatching { navi.getBroadcastMode() }.getOrDefault(-1)
        DebugVoiceLog.log("nav_guidance_broadcast accepted=$accepted mode=${broadcastModeName(mode)}")
    }

    /** Developer toggle 「助手播报导航（实验，默认关）」; read once per [enable]. */
    const val PREFS = "nova_guidance_relay"
    const val PREF_ASSISTANT_VOICE = "assistant_speaks_guidance"

    fun assistantVoiceEnabled(context: android.content.Context): Boolean =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getBoolean(PREF_ASSISTANT_VOICE, false)

    internal fun broadcastModeName(mode: Int): String = when (mode) {
        BroadcastMode.CONCISE -> "concise"
        BroadcastMode.DETAIL -> "detail"
        BroadcastMode.MUTE -> "mute"
        else -> "unknown"
    }

    fun disable(navi: AMapNavi?) {
        runCatching { navi?.removeTTSPlayListener(this) }
        relay?.let { GuidanceRelay.uninstall(it) }
        relay = null
        textReceiver = null
        // Guidance cut off mid-sentence may never report its end; do not leave the mic gated.
        if (NavigationGuidanceVoice.speaking) NavigationGuidanceVoice.onPlayEnd()
    }
}
