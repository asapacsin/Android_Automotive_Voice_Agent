package com.novadrive.app.voice

import com.novadrive.app.BaiduApiConfig
import com.novadrive.app.BaiduAppSettings
import com.novadrive.app.BaiduAuthMode
import com.novadrive.app.BaiduCredentials
import com.novadrive.app.BaiduRuntimeProvider
import com.novadrive.app.GeminiApiConfig
import com.novadrive.app.GeminiAppSettings
import com.novadrive.app.OutputSampleRate
import com.novadrive.app.QwenApiConfig
import com.novadrive.app.QwenAppSettings
import com.novadrive.app.QwenSettingsValidator
import com.novadrive.app.VoiceProviderChoice
import com.novadrive.app.VoiceProviderPreference
import com.novadrive.app.resolvedOutputSampleRateHz
import com.novadrive.ingress.realtime.VoiceCatalog
import com.novadrive.ingress.realtime.VoiceProviderId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

    /** SPEC-021 A8: Qwen is built only from a Qwen config, at 24 kHz out, with its own capabilities. */
    @Test
    fun qwenConfigBuildsQwenOmniAt24kHzOut16kHzIn() {
        val api = QwenApiConfig(QwenAppSettings(consentAccepted = true, workspaceId = "ws-test"), "placeholder", "p")
        val built = build(SessionProviderConfig.Qwen(api))
        try {
            assertTrue(built.provider is QwenOmniProvider)
            assertEquals(VoiceProviderId.QWEN, built.session.provider)
            assertEquals(VoiceCatalog.QWEN_OMNI_FLASH, built.session.model)
            assertEquals(24_000, built.outputSampleRateHz)
            assertEquals(24_000, built.session.audio.outputSampleRateHz)
            assertEquals(16_000, built.session.audio.inputSampleRateHz)
            assertEquals(VoiceCatalog.capabilities(VoiceProviderId.QWEN), built.provider.capabilities)
        } finally {
            built.provider.close()
        }
    }

    /** SPEC-021 A8 (amended 2026-10-09): the product session is Qwen; stored prefs do not change that. */
    @Test
    fun qwenIsReadOnlyForTheQwenPreferenceAndNeverFallsBack() {
        VoiceProviderPreference.entries.forEach { pref ->
            val choice = VoiceProviderChoice.resolve(GeminiAppSettings(provider = pref), keyPresent = true)
            assertEquals(VoiceProviderId.QWEN, choice)
            var geminiTouched = false
            var baiduTouched = false
            var qwenTouched = false
            runCatching {
                sessionConfigFor(
                    choice,
                    gemini = { geminiTouched = true; error("gemini") },
                    baidu = { baiduTouched = true; error("baidu") },
                    qwen = { qwenTouched = true; error("qwen") },
                )
            }
            assertFalse(geminiTouched, "Gemini must not run for pref=$pref")
            assertFalse(baiduTouched, "Baidu must not run for pref=$pref")
            assertTrue(qwenTouched, "Qwen must run for pref=$pref")
        }

        val choice = VoiceProviderChoice.resolve(GeminiAppSettings(provider = VoiceProviderPreference.GEMINI), keyPresent = false)
        assertEquals(VoiceProviderId.QWEN, choice)
        val ok = QwenAppSettings(consentAccepted = true, workspaceId = "ws-test")
        listOf(
            Triple(ok, "", "QWEN_API_KEY_MISSING"),
            Triple(ok.copy(workspaceId = ""), "k", "QWEN_WORKSPACE_MISSING"),
            Triple(ok.copy(consentAccepted = false), "k", "QWEN_CONSENT_MISSING"),
        ).forEach { (settings, key, code) ->
            var otherTouched = false
            val failure = runCatching {
                sessionConfigFor(
                    choice,
                    gemini = { otherTouched = true; error("gemini") },
                    baidu = { otherTouched = true; error("baidu") },
                    qwen = { QwenSettingsValidator.configOrThrow(settings, key, "persona") },
                )
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException, code)
            assertEquals(code, failure!!.message)
            assertFalse(otherTouched, "a Qwen failure never falls back to another provider ($code)")
        }
        val config = sessionConfigFor(choice, { error("gemini") }, { error("baidu") }, { QwenSettingsValidator.configOrThrow(ok, "k", "p") })
        assertTrue(config is SessionProviderConfig.Qwen)
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
