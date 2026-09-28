package com.novadrive.app.nav.amap

import com.amap.api.navi.AMapNavi
import com.amap.api.navi.TTSPlayListener
import com.amap.api.navi.enums.BroadcastMode
import com.novadrive.app.DebugVoiceLog
import com.novadrive.app.nav.NavigationGuidanceVoice
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

    override fun onPlayStart(text: String?) {
        DebugVoiceLog.log("nav_guidance_play_start chars=${text?.length ?: 0}")
        NavigationGuidanceVoice.onPlayStart()
        if (text.isNullOrBlank()) return
        // Debug host bridge only (null otherwise): the emulator speaker is inaudible, so the
        // guidance text is spoken again on the PC.
        runCatching { HostAudioTap.guidanceSink?.invoke(text) }
        textReceiver?.invoke(text)
    }

    override fun onPlayEnd(text: String?) {
        DebugVoiceLog.log("nav_guidance_play_end")
        NavigationGuidanceVoice.onPlayEnd()
    }

    fun enable(navi: AMapNavi?) {
        navi ?: return
        runCatching {
            navi.setUseInnerVoice(true, false)
            navi.addTTSPlayListener(this)
        }.onSuccess {
            val on = runCatching { navi.getIsUseInnerVoice() }.getOrDefault(false)
            DebugVoiceLog.log("nav_guidance_voice enabled=$on")
        }.onFailure {
            DebugVoiceLog.log("nav_guidance_voice enabled=false exception=${it.javaClass.simpleName}")
        }
        applyConciseBroadcast(navi)
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

    internal fun broadcastModeName(mode: Int): String = when (mode) {
        BroadcastMode.CONCISE -> "concise"
        BroadcastMode.DETAIL -> "detail"
        BroadcastMode.MUTE -> "mute"
        else -> "unknown"
    }

    fun disable(navi: AMapNavi?) {
        runCatching { navi?.removeTTSPlayListener(this) }
        textReceiver = null
        // Guidance cut off mid-sentence may never report its end; do not leave the mic gated.
        if (NavigationGuidanceVoice.speaking) NavigationGuidanceVoice.onPlayEnd()
    }
}
