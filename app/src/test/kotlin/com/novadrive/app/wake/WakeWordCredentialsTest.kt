package com.novadrive.app.wake

import com.novadrive.app.CredentialStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WakeWordCredentialsTest {
    @Test
    fun isCompleteWhenAppIdIsNonBlank() {
        // MSC uses appId only; apiKey/apiSecret are unused and must not gate completeness.
        assertTrue(WakeWordCredentials("app-id", "api-key", "api-secret").isComplete())
        assertTrue(WakeWordCredentials("app-id", "", "").isComplete())
        assertTrue(WakeWordCredentials("app-id", "", "api-secret").isComplete())
        assertFalse(WakeWordCredentials("", "api-key", "api-secret").isComplete())
        assertFalse(WakeWordCredentials("  ", "api-key", "api-secret").isComplete())
        assertFalse(WakeWordCredentials("", "", "").isComplete())
    }

    @Test
    fun toStringRedactsAllThreeValues() {
        val appId = "unique-iflytek-app-id-value"
        val apiKey = "unique-iflytek-api-key-value"
        val apiSecret = "unique-iflytek-api-secret-value"
        val text = WakeWordCredentials(appId, apiKey, apiSecret).toString()
        assertFalse(text.contains(appId), "toString leaked appId")
        assertFalse(text.contains(apiKey), "toString leaked apiKey")
        assertFalse(text.contains(apiSecret), "toString leaked apiSecret")
    }

    @Test
    fun loadAndSaveRoundTripThroughCredentialStore() {
        val store = FakeCredentialStore()
        val saved = WakeWordCredentials(
            appId = "stored-app-id",
            apiKey = "stored-api-key",
            apiSecret = "stored-api-secret",
        )
        saved.writeTo(store)
        val loaded = WakeWordCredentials.readFrom(store)
        assertEquals(saved, loaded)
        assertTrue(loaded.isComplete())
        assertTrue(store.names().containsAll(listOf("iflytek_app_id", "iflytek_api_key", "iflytek_api_secret")))
        assertFalse(loaded.toString().contains("stored-app-id"))
        assertFalse(loaded.toString().contains("stored-api-key"))
        assertFalse(loaded.toString().contains("stored-api-secret"))
    }

    private class FakeCredentialStore : CredentialStore {
        private val values = mutableMapOf<String, String>()

        override fun read(name: String): String? = values[name]
        override fun write(name: String, value: String) { values[name] = value }
        override fun clear(name: String) { values.remove(name) }
        fun names(): Set<String> = values.keys
    }
}
