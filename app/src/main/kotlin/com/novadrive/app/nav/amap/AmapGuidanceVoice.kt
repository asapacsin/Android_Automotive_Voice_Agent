package com.novadrive.app.nav.amap

import com.amap.api.navi.AMapNavi
import com.amap.api.navi.TTSPlayListener
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
    /** The live host's text panel (translated ABI only); null otherwise. */
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
    }

    fun disable(navi: AMapNavi?) {
        runCatching { navi?.removeTTSPlayListener(this) }
        textReceiver = null
        // Guidance cut off mid-sentence may never report its end; do not leave the mic gated.
        if (NavigationGuidanceVoice.speaking) NavigationGuidanceVoice.onPlayEnd()
    }
}
