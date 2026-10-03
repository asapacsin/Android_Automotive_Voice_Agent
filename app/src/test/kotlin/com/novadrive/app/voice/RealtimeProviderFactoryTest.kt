package com.novadrive.app.voice

import com.novadrive.app.BaiduApiConfig
import com.novadrive.app.BaiduAppSettings
import com.novadrive.app.BaiduAuthMode
import com.novadrive.app.BaiduCredentials
import com.novadrive.app.BaiduRuntimeProvider
import com.novadrive.app.GeminiApiConfig
import com.novadrive.app.GeminiAppSettings
import com.novadrive.app.OutputSampleRate
import com.novadrive.app.resolvedOutputSampleRateHz
import com.novadrive.ingress.realtime.VoiceCatalog
import com.novadrive.ingress.realtime.VoiceProviderId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The composition boundary's one choice of realtime implementation (ADR-009 §4, ADR-010). */
class RealtimeProviderFactoryTest {
    private fun build(config: SessionProviderConfig) =
        RealtimeProviderFactory.build(config, { null }, { true })

    @Test
    fun geminiConfigBuildsGeminiLiveAt24kHzOut16kHzIn() {
        val built = build(SessionProviderConfig.Gemini(GeminiApiConfig(GeminiAppSettings(), "placeholder", "p")))
        try {
            assertTrue(built.provider is GeminiLiveProvider)
            assertEquals(VoiceProviderId.GEMINI_LIVE, built.session.provider)
            assertEquals(VoiceCatalog.GEMINI_LIVE_DEFAULT, built.session.model)
            assertEquals(24_000, built.outputSampleRateHz)
            assertEquals(24_000, built.session.audio.outputSampleRateHz)
            assertEquals(16_000, built.session.audio.inputSampleRateHz)
        } finally {
            built.provider.close()
        }
    }

    @Test
    fun baiduFlexBuildsFlexProviderAtItsResolvedRate() {
        listOf(OutputSampleRate.AUTO, OutputSampleRate.HZ_16000).forEach { rate ->
            val api = baidu(BaiduRuntimeProvider.FLEX, VoiceCatalog.BAIDU_FLEX, rate)
            val built = build(SessionProviderConfig.Baidu(api))
            try {
                assertTrue(built.provider is BaiduFlexProvider)
                assertEquals(VoiceProviderId.BAIDU_FLEX, built.session.provider)
                assertEquals(VoiceCatalog.BAIDU_FLEX, built.session.model)
                assertEquals(api.settings.resolvedOutputSampleRateHz(), built.outputSampleRateHz)
                assertEquals(built.outputSampleRateHz, built.session.audio.outputSampleRateHz)
                assertEquals(16_000, built.session.audio.inputSampleRateHz)
            } finally {
                built.provider.close()
            }
        }
    }

    @Test
    fun baiduLiteBuildsTheDirectProvider() {
        val api = baidu(BaiduRuntimeProvider.LITE, VoiceCatalog.BAIDU_LITE_NEAR, OutputSampleRate.AUTO)
        val built = build(SessionProviderConfig.Baidu(api))
        try {
            assertTrue(built.provider is BaiduDirectRealtimeProvider)
            assertEquals(VoiceProviderId.BAIDU, built.session.provider)
            assertEquals(16_000, built.outputSampleRateHz)
        } finally {
            built.provider.close()
        }
    }

    private fun baidu(runtime: BaiduRuntimeProvider, model: String, rate: OutputSampleRate) = BaiduApiConfig(
        BaiduAppSettings(
            authMode = BaiduAuthMode.BEARER_API_KEY,
            runtimeProvider = runtime,
            model = model,
            outputSampleRate = rate,
        ),
        BaiduCredentials("", "placeholder-api-key", ""),
    )
}
