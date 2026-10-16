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

    /**
     * P63, toggle off (every shipped build): the app's own sentences (the over-speed warning) go
     * through a [GuidanceRelay] that never sends to the assistant (`enabled = false`), so they get
     * its Amap rules: wait until Amap is quiet, bracket the `playTTS` for the arbiter (mic gated
     * whether or not G-1b+ holds), cleared at navigation end. Not installed as the active relay.
     */
    @Volatile private var alertQueue: GuidanceRelay? = null
    private val amapListeners = mutableListOf<(Boolean) -> Unit>()

    /** The alert sentence already handed to the host bridge, so its listener echo is not voiced twice. */
    @Volatile private var alertFedToHost: String? = null

    override fun onPlayStart(text: String?) {
        DebugVoiceLog.log("nav_guidance_play_start chars=${text?.length ?: 0}")
        NavigationGuidanceVoice.onPlayStart()
        if (text.isNullOrBlank() || relay != null) return
        if (text == alertFedToHost) { alertFedToHost = null; textReceiver?.invoke(text); return }
        feedText(text)
    }

    /** SPEC-018 B10: with Amap muted the text arrives here; the panel and host tap move with it. */
    fun onNavigationText(text: String?) {
        val active = relay ?: return
        if (text.isNullOrBlank()) return
        // P62: with the relay on, the host hears only what Amap's fallback speaks (relay's speaker
        // below); what 小诺 speaks arrives as her own reply audio.
        feedText(text, host = false)
        active.onGuidanceText(text)
    }

    private fun feedText(text: String, host: Boolean = true) {
        // Debug host bridge only (null otherwise): the emulator speaker is inaudible, so the
        // guidance text is spoken again on the PC.
        if (host) runCatching { HostAudioTap.guidanceSink?.invoke(text) }
        textReceiver?.invoke(text)
    }

    /**
     * P63: a sentence the app itself must say during navigation (the over-speed warning), queued
     * like guidance on the main thread: to the relay when the toggle is on (小诺's voice, Amap as the
     * fallback), else to [alertQueue] (Amap's voice once Amap is quiet, bracketed for the arbiter).
     * The log says where it was queued, not that it was heard: the relay's `guidance_route`,
     * `guidance_claim` and `nav_guidance_fallback_tts` lines say what happened to it.
     */
    fun speakAlert(text: String) {
        mainHandler.post {
            if (!com.novadrive.app.NavigationState.navigating) {
                DebugVoiceLog.log("nav_alert_dropped reason=not_navigating")
                return@post
            }
            val active = relay
            val queue = active ?: alertQueue
            if (queue == null) {
                DebugVoiceLog.log("nav_alert_dropped reason=no_queue")
                return@post
            }
            if (active != null) runCatching { textReceiver?.invoke(text) }
            queue.onGuidanceText(text)
            DebugVoiceLog.log("nav_alert_queued to=${if (active != null) "relay" else "amap"} chars=${text.length}")
        }
    }

    /** P63: every speed-HUD change; the over-speed warning is queued when [alert] says so (the limit only in the log). */
    fun onSpeedHud(alert: OverspeedAlert, snap: DrivingSpeedHud.Snapshot) {
        val sentence = alert.onSnapshot(snap, SystemClock.elapsedRealtime()) ?: return
        DebugVoiceLog.log("nav_overspeed_alert limitKmh=${snap.limitKmh}")
        speakAlert(sentence)
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** Debug builds only (the debug receiver's `guidance_relay on:<ms>`): a measurement deadline, else SPEC-018's. */
    @Volatile var debugDeadlineMs: Long? = null

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
        if (assistantVoice) installRelay(navi) else installAlertQueue(navi)
    }

    private fun installAlertQueue(navi: AMapNavi) {
        val queue = buildRelay(navi, assistantSpeaks = false)
        alertQueue = queue
        DebugVoiceLog.log("nav_alert_queue installed=true")
    }

    private fun installRelay(navi: AMapNavi) {
        val relay = buildRelay(navi, assistantSpeaks = true)
        DebugVoiceLog.log("nav_guidance_relay deadline_ms=${debugDeadlineMs ?: GuidanceRelay.DEFAULT_DEADLINE_MS}")
        this.relay = relay
        GuidanceRelay.install(relay) { task -> mainHandler.post { task() } }
        DebugVoiceLog.log("nav_guidance_relay installed=true")
    }

    /** One relay construction for both uses; `assistantSpeaks = false` never sends to the session. */
    private fun buildRelay(navi: AMapNavi, assistantSpeaks: Boolean): GuidanceRelay {
        val handler = mainHandler
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
                // P62: only what Amap's voice was given reaches the host bridge (debug; null otherwise).
                if (accepted && HostAudioTap.guidanceSink != null) {
                    if (!assistantSpeaks) alertFedToHost = text
                    runCatching { HostAudioTap.guidanceSink?.invoke(text) }
                }
                accepted
            },
            arbiter = object : GuidanceArbiterView {
                override fun amapSpeaking() = NavigationGuidanceVoice.speaking
                override fun transientFocusLoss() = SpeechAuthority.arbiter.guidanceHeld()
                override fun abandon(promptId: String) = SpeechAuthority.arbiter.abandon(promptId)
                // G-1b+: the relay's own playTTS is guidance for the arbiter (R1–R3) until its end.
                override fun bracket(speaking: Boolean) =
                    if (speaking) NavigationGuidanceVoice.onPlayStart() else NavigationGuidanceVoice.onPlayEnd()
            },
            enabled = { assistantSpeaks },
            deadlineMs = debugDeadlineMs ?: GuidanceRelay.DEFAULT_DEADLINE_MS,
        )
        val listener: (Boolean) -> Unit = { speaking -> handler.post { relay.onAmapSpeaking(speaking) } }
        synchronized(amapListeners) { amapListeners += listener }
        NavigationGuidanceVoice.addListener(listener)
        return relay
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

    /** GUIDANCE-EMU-001 measurement only: written by the debug receiver, read in debuggable builds. */
    const val PREF_DEBUG_DEADLINE_MS = "debug_deadline_ms"

    fun debugDeadline(context: android.content.Context): Long? {
        if (!DebugVoiceLog.isEnabled) return null
        val ms = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getLong(PREF_DEBUG_DEADLINE_MS, 0L)
        return ms.takeIf { it > 0 }
    }

    internal fun broadcastModeName(mode: Int): String = when (mode) {
        BroadcastMode.CONCISE -> "concise"
        BroadcastMode.DETAIL -> "detail"
        BroadcastMode.MUTE -> "mute"
        else -> "unknown"
    }

    /** Every navigation end (the host's single stop funnel): unstick the gate, reset the relay. */
    fun onNavigationStopped() {
        // Guidance cut off mid-sentence may never report its end; do not leave the mic gated.
        if (NavigationGuidanceVoice.speaking) NavigationGuidanceVoice.onPlayEnd()
        relay?.onNavigationEnded()
        alertQueue?.onNavigationEnded() // P63: a warning still waiting for Amap is never said after the drive
        alertFedToHost = null
    }

    fun disable(navi: AMapNavi?) {
        runCatching { navi?.removeTTSPlayListener(this) }
        relay?.let { GuidanceRelay.uninstall(it) }
        relay = null
        alertQueue?.onNavigationEnded()
        alertQueue = null
        alertFedToHost = null
        synchronized(amapListeners) {
            amapListeners.forEach { NavigationGuidanceVoice.removeListener(it) }
            amapListeners.clear()
        }
        textReceiver = null
        // Guidance cut off mid-sentence may never report its end; do not leave the mic gated.
        if (NavigationGuidanceVoice.speaking) NavigationGuidanceVoice.onPlayEnd()
    }
}
