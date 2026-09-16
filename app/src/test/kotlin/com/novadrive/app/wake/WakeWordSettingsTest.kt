package com.novadrive.app.wake

import com.novadrive.app.CredentialStore
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WakeWordSettingsTest {
    @Test
    fun enabledDefaultsFalseAndRoundTrips() {
        var stored: Boolean? = null
        val settings = WakeWordSettings(
            credentials = FakeCredentialStore(),
            readEnabled = { stored ?: false },
            writeEnabled = { stored = it },
        )
        assertFalse(settings.isEnabled())
        settings.setEnabled(true)
        assertTrue(settings.isEnabled())
        settings.setEnabled(false)
        assertFalse(settings.isEnabled())
    }

    private class FakeCredentialStore : CredentialStore {
        private val values = mutableMapOf<String, String>()
        override fun read(name: String): String? = values[name]
        override fun write(name: String, value: String) { values[name] = value }
        override fun clear(name: String) { values.remove(name) }
    }
}
