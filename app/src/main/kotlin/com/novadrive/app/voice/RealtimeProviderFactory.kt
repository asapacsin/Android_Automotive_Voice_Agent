package com.novadrive.app.voice

import com.novadrive.app.BaiduApiConfig
import com.novadrive.app.BaiduRuntimeProvider
import com.novadrive.app.GeminiApiConfig
import com.novadrive.app.resolvedOutputSampleRateHz
import com.novadrive.ingress.realtime.RealtimeAudioConfig
import com.novadrive.ingress.realtime.RealtimeSessionConfig
import com.novadrive.ingress.realtime.RealtimeVoiceProvider
import com.novadrive.ingress.realtime.VoiceProviderId

/** Which provider a session uses, with that provider's configuration. Chosen once per session. */
sealed interface SessionProviderConfig {
    data class Baidu(val api: BaiduApiConfig) : SessionProviderConfig
    data class Gemini(val api: GeminiApiConfig) : SessionProviderConfig
}

/** The composition boundary's one `when` that chooses the realtime implementation (ADR-009 §4, ADR-010). */
object RealtimeProviderFactory {
    data class Built(
        val provider: RealtimeVoiceProvider,
        val session: RealtimeSessionConfig,
        val outputSampleRateHz: Int,
    )

    /** Gemini Live replies are `audio/pcm;rate=24000` (measured). */
    const val GEMINI_OUTPUT_SAMPLE_RATE_HZ = 24_000
    private const val INPUT_SAMPLE_RATE_HZ = 16_000

    fun build(
        config: SessionProviderConfig,
        lastAudioSegment: () -> SpeechUplinkGate.Segment?,
        speechEvidence: () -> Boolean,
    ): Built = when (config) {
        is SessionProviderConfig.Baidu -> {
            val api = config.api
            val rate = api.settings.resolvedOutputSampleRateHz()
            when (api.settings.runtimeProvider) {
                BaiduRuntimeProvider.FLEX -> built(
                    BaiduFlexProvider(api, lastAudioSegment, speechEvidence),
                    VoiceProviderId.BAIDU_FLEX, api.settings.model, rate,
                )
                BaiduRuntimeProvider.LITE -> built(
                    BaiduDirectRealtimeProvider(api), VoiceProviderId.BAIDU, api.settings.model, rate,
                )
            }
        }
        is SessionProviderConfig.Gemini -> built(
            GeminiLiveProvider(config.api, lastAudioSegment, speechEvidence),
            VoiceProviderId.GEMINI_LIVE, config.api.settings.model, GEMINI_OUTPUT_SAMPLE_RATE_HZ,
        )
    }

    private fun built(provider: RealtimeVoiceProvider, id: VoiceProviderId, model: String, rate: Int) =
        Built(provider, RealtimeSessionConfig(provider = id, model = model, audio = RealtimeAudioConfig(INPUT_SAMPLE_RATE_HZ, rate)), rate)
}
