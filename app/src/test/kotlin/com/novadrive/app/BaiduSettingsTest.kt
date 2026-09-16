package com.novadrive.app

import com.novadrive.ingress.realtime.VoiceCatalog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BaiduSettingsTest {
    @Test
    fun legacyRequiresAllCredentialsAndTls() {
        val settings = BaiduAppSettings()
        assertEquals("BAIDU_API_KEY_MISSING", BaiduSettingsValidator.validate(settings, BaiduCredentials("", "", "")))
        assertEquals("BAIDU_APP_ID_MISSING", BaiduSettingsValidator.validate(settings, BaiduCredentials("", "key", "secret")))
        assertEquals("BAIDU_SECRET_KEY_MISSING", BaiduSettingsValidator.validate(settings, BaiduCredentials("app", "key", "")))
        assertNull(BaiduSettingsValidator.validate(settings, BaiduCredentials("app", "key", "secret")))
    }

    @Test
    fun bearerNeedsOnlyApiKeyAndKnownModel() {
        val settings = BaiduAppSettings(authMode = BaiduAuthMode.BEARER_API_KEY)
        assertNull(BaiduSettingsValidator.validate(settings, BaiduCredentials("", "key", "")))
        assertEquals("BAIDU_MODEL_INVALID", BaiduSettingsValidator.validate(settings.copy(model = "invented"), BaiduCredentials("", "key", "")))
        assertEquals(VoiceCatalog.BAIDU_FLEX, BaiduAppSettings().model)
        assertEquals(BaiduRuntimeProvider.FLEX, BaiduAppSettings().runtimeProvider)
    }

    @Test
    fun defaultInstructionsAreDefaultPersonaAndRejectOverlongText() {
        assertEquals("小诺", PersonaProfiles.DEFAULT_PERSONA_NAME)
        assertTrue(PersonaProfiles.DEFAULT_INSTRUCTIONS.startsWith("你是「小诺」"))
        assertTrue(PersonaProfiles.DEFAULT_INSTRUCTIONS.contains("端庄"))
        assertTrue(PersonaProfiles.DEFAULT_INSTRUCTIONS.contains("普通话"))
        assertTrue(PersonaProfiles.DEFAULT_INSTRUCTIONS.contains("禁止使用粤语"))
        assertTrue(PersonaProfiles.DEFAULT_INSTRUCTIONS.contains("谎报"))
        assertTrue(PersonaProfiles.DEFAULT_INSTRUCTIONS.contains("绝对不许谎报结果"))
        assertTrue(PersonaProfiles.DEFAULT_INSTRUCTIONS.contains("control_music"))
        assertTrue(PersonaProfiles.DEFAULT_INSTRUCTIONS.contains("保持安静"))
        assertFalse(PersonaProfiles.DEFAULT_INSTRUCTIONS.contains("傲娇"))
        assertEquals(PersonaProfiles.DEFAULT_INSTRUCTIONS, BaiduAppSettings().instructions)
        assertEquals(PersonaProfiles.DEFAULT_INSTRUCTIONS, PersonaProfiles.sanitize(null))
        assertEquals(
            "BAIDU_INSTRUCTIONS_TOO_LONG",
            BaiduSettingsValidator.validate(
                BaiduAppSettings(instructions = "x".repeat(5000)),
                BaiduCredentials("app", "key", "secret"),
            ),
        )
    }

    @Test
    fun defaultInstructionsRequireControlMusicStopAndStayQuiet() {
        assertTrue(PersonaProfiles.DEFAULT_INSTRUCTIONS.contains("control_music"))
        assertTrue(PersonaProfiles.DEFAULT_INSTRUCTIONS.contains("保持安静"))
    }

    @Test
    fun defaultInstructionsRequireExitNavigationMode() {
        assertTrue(PersonaProfiles.DEFAULT_INSTRUCTIONS.contains("exit_navigation_mode"))
    }

    @Test
    fun voiceAndSpeedDefaultAndRejectInvalidValues() {
        assertEquals("default", BaiduAppSettings().voice)
        assertEquals(1.1, BaiduAppSettings().speed)
        assertEquals(
            "BAIDU_VOICE_INVALID",
            BaiduSettingsValidator.validate(
                BaiduAppSettings(voice = "bad voice!"),
                BaiduCredentials("app", "key", "secret"),
            ),
        )
        assertEquals(
            "BAIDU_SPEED_INVALID",
            BaiduSettingsValidator.validate(
                BaiduAppSettings(speed = 2.0),
                BaiduCredentials("app", "key", "secret"),
            ),
        )
    }

    @Test
    fun outputSampleRateFromWireDefaultsToAuto() {
        assertEquals(OutputSampleRate.AUTO, OutputSampleRate.fromWire(null))
        assertEquals(OutputSampleRate.HZ_24000, OutputSampleRate.fromWire("24000"))
    }

    @Test
    fun resolvedOutputSampleRateFollowsProviderUnlessOverridden() {
        assertEquals(24_000, BaiduAppSettings().resolvedOutputSampleRateHz())
        assertEquals(
            16_000,
            BaiduAppSettings(runtimeProvider = BaiduRuntimeProvider.LITE, model = VoiceCatalog.BAIDU_LITE_NEAR)
                .resolvedOutputSampleRateHz(),
        )
        assertEquals(
            16_000,
            BaiduAppSettings(outputSampleRate = OutputSampleRate.HZ_16000).resolvedOutputSampleRateHz(),
        )
    }

    @Test
    fun credentialUpdatesKeepReplaceAndExplicitlyClearIndependentFields() {
        val store = FakeCredentialStore()
        store.applyCredentialUpdate("api", CredentialUpdate.Replace(" first "))
        store.applyCredentialUpdate("secret", CredentialUpdate.Replace("second"))
        store.applyCredentialUpdate("api", CredentialUpdate.Keep)
        assertEquals("first", store.read("api"))
        assertEquals("second", store.read("secret"))
        store.applyCredentialUpdate("api", CredentialUpdate.Clear)
        assertNull(store.read("api"))
        assertEquals("second", store.read("secret"))
    }

    private class FakeCredentialStore : CredentialStore {
        private val values = mutableMapOf<String, String>()
        override fun read(name: String): String? = values[name]
        override fun write(name: String, value: String) { values[name] = value }
        override fun clear(name: String) { values.remove(name) }
    }
}
