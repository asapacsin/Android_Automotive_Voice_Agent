package com.novadrive.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class QwenSettingsTest {
    @Test
    fun validationReportsSpecificMissingAndTransportErrors() {
        assertEquals("QWEN_API_KEY_MISSING", QwenSettingsValidator.validate(null, "model", "wss://example.test/ws"))
        assertEquals("QWEN_MODEL_MISSING", QwenSettingsValidator.validate("placeholder", "", "wss://example.test/ws"))
        assertEquals("QWEN_ENDPOINT_MISSING", QwenSettingsValidator.validate("placeholder", "model", ""))
        assertEquals("QWEN_ENDPOINT_INVALID", QwenSettingsValidator.validate("placeholder", "model", "https://example.test/ws"))
        assertEquals("QWEN_TLS_REQUIRED", QwenSettingsValidator.validate("placeholder", "model", "ws://example.test/ws"))
        assertNull(QwenSettingsValidator.validate("placeholder", "model", "wss://example.test/ws"))
    }

    @Test
    fun keyManagerCanSaveReloadReplaceAndClearWithoutKnowingStorageFormat() {
        val store = FakeApiKeyStore()
        val manager = ApiKeyManager(store)
        manager.apply(ApiKeyUpdate.Replace("first-placeholder"))
        assertEquals("first-placeholder", manager.read())
        manager.apply(ApiKeyUpdate.Replace("second-placeholder"))
        assertEquals("second-placeholder", manager.read())
        manager.apply(ApiKeyUpdate.Keep)
        assertEquals("second-placeholder", manager.read())
        manager.apply(ApiKeyUpdate.Clear)
        assertNull(manager.read())
        assertThrows<IllegalArgumentException> { manager.apply(ApiKeyUpdate.Replace("  ")) }
    }

    private class FakeApiKeyStore : ApiKeyStore {
        private var value: String? = null
        override fun read(): String? = value
        override fun replace(value: String) { this.value = value }
        override fun clear() { value = null }
    }
}
