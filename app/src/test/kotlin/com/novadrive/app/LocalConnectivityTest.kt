package com.novadrive.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalConnectivityTest {
    private val lanBuild =
        VoiceBuildDefaults(
            backendUrl = "http://192.168.1.8:8000",
            providerWire = "fake",
            model = "fake-realtime",
        )
    private val emulatorQwenBuild =
        VoiceBuildDefaults(
            backendUrl = "http://10.0.2.2:8000",
            providerWire = "qwen",
            model = "qwen-audio-3.0-realtime-flash",
        )

    @Test
    fun migrateReplacesEmulatorUrlWhenBuildSuppliesLanUrl() {
        val stored =
            VoicePreferenceSnapshot(
                backendUrl = "http://10.0.2.2:8000",
                providerWire = "qwen",
                model = "qwen-audio-3.0-realtime-plus",
            )
        val migrated = LocalConnectivity.migratePreferences(stored, lanBuild)
        assertEquals("http://192.168.1.8:8000", migrated.backendUrl)
        assertEquals("qwen", migrated.providerWire)
        assertEquals("qwen-audio-3.0-realtime-plus", migrated.model)
    }

    @Test
    fun migrateLeavesEmulatorUrlWhenBuildIsAlsoEmulator() {
        val stored =
            VoicePreferenceSnapshot(
                backendUrl = "http://10.0.2.2:8000",
                providerWire = "qwen",
                model = "qwen-audio-3.0-realtime-flash",
            )
        assertEquals(stored, LocalConnectivity.migratePreferences(stored, emulatorQwenBuild))
    }

    @Test
    fun migratePreservesDeliberateLanUrl() {
        val stored =
            VoicePreferenceSnapshot(
                backendUrl = "http://192.168.50.20:8000",
                providerWire = "qwen",
                model = "qwen-audio-3.0-realtime-flash",
            )
        assertEquals(stored, LocalConnectivity.migratePreferences(stored, lanBuild))
    }

    @Test
    fun migrateReplacesObsoleteBaiduLiteNearPairWhenBuildDiffers() {
        val stored =
            VoicePreferenceSnapshot(
                backendUrl = "http://192.168.50.20:8000",
                providerWire = "baidu",
                model = "audio-mini-realtime-near",
            )
        val migrated = LocalConnectivity.migratePreferences(stored, lanBuild)
        assertEquals("http://192.168.50.20:8000", migrated.backendUrl)
        assertEquals("fake", migrated.providerWire)
        assertEquals("fake-realtime", migrated.model)
    }

    @Test
    fun migrateTreatsBaiduLiteNearWithBlankProviderAsObsoletePair() {
        val stored =
            VoicePreferenceSnapshot(
                backendUrl = "http://10.0.2.2:8000",
                providerWire = null,
                model = "audio-mini-realtime-near",
            )
        val migrated = LocalConnectivity.migratePreferences(stored, lanBuild)
        assertEquals("http://192.168.1.8:8000", migrated.backendUrl)
        assertEquals("fake", migrated.providerWire)
        assertEquals("fake-realtime", migrated.model)
    }

    @Test
    fun migratePreservesDeliberateBaiduNonDefaultModel() {
        val stored =
            VoicePreferenceSnapshot(
                backendUrl = "http://10.0.2.2:8000",
                providerWire = "baidu",
                model = "audio-mini-realtime-far",
            )
        val migrated = LocalConnectivity.migratePreferences(stored, lanBuild)
        assertEquals("http://192.168.1.8:8000", migrated.backendUrl)
        assertEquals("baidu", migrated.providerWire)
        assertEquals("audio-mini-realtime-far", migrated.model)
    }

    @Test
    fun migratePreservesQwenAndGptLiveChoicesWhenBuildIsFake() {
        val qwen =
            VoicePreferenceSnapshot(
                backendUrl = "http://192.168.1.8:8000",
                providerWire = "qwen",
                model = "qwen-audio-3.0-realtime-flash",
            )
        val gpt =
            VoicePreferenceSnapshot(
                backendUrl = "http://192.168.1.8:8000",
                providerWire = "gpt_live",
                model = "gpt-live-1",
            )
        assertEquals(qwen, LocalConnectivity.migratePreferences(qwen, lanBuild))
        assertEquals(gpt, LocalConnectivity.migratePreferences(gpt, lanBuild))
    }

    @Test
    fun migrateLeavesBlankPrefsUnchangedSoResolveCanUseBuildDefaults() {
        val stored = VoicePreferenceSnapshot()
        assertEquals(stored, LocalConnectivity.migratePreferences(stored, lanBuild))
        assertEquals(lanBuild, LocalConnectivity.resolvePreferences(stored, lanBuild))
        assertEquals(emulatorQwenBuild, LocalConnectivity.resolvePreferences(stored, emulatorQwenBuild))
    }

    @Test
    fun backendHostPortStripsUserinfoPathAndQuery() {
        val raw = "http://user:s3cret-token@192.168.1.8:8000/v1/voice/realtime?access_token=abc#frag"
        assertEquals("192.168.1.8:8000", LocalConnectivity.backendHostPort(raw))
        val message = LocalConnectivity.backendWsFailedMessage(raw)
        assertTrue(message.contains("192.168.1.8:8000"))
        assertTrue(message.contains("BACKEND_WS_FAILED").not())
        assertFalse(message.contains("s3cret-token"))
        assertFalse(message.contains("access_token"))
        assertFalse(message.contains("abc"))
        assertFalse(message.contains("user:"))
        assertTrue(message.contains("0.0.0.0:8000") || message.contains("局域网"))
        assertTrue(message.contains("10.0.2.2"))
    }

    @Test
    fun backendHostPortHandlesMissingScheme() {
        assertEquals("192.168.1.8:8000", LocalConnectivity.backendHostPort("192.168.1.8:8000"))
        assertEquals("10.0.2.2:8000", LocalConnectivity.backendHostPort("http://10.0.2.2:8000/"))
    }

    @Test
    fun credentialsFooterIsQwenFirstAndNamesCurrentLocalProvider() {
        val qwen = LocalConnectivity.credentialsFooter("Qwen", isFake = false)
        val fake = LocalConnectivity.credentialsFooter("Fake", isFake = true)
        assertTrue(qwen.contains("Qwen Flash"))
        assertTrue(qwen.contains("Qwen"))
        assertFalse(qwen.contains("默认百度"))
        assertFalse(qwen.contains("默认百度 Lite Near"))
        assertTrue(fake.contains("Qwen Flash"))
        assertTrue(fake.contains("Fake"))
        assertTrue(fake.contains("非产品默认") || fake.contains("不是产品默认"))
        assertFalse(fake.contains("默认百度"))
    }
}
