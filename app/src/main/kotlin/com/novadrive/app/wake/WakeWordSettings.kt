package com.novadrive.app.wake

import android.content.Context
import com.novadrive.app.AndroidKeystoreCredentialStore
import com.novadrive.app.CredentialStore

class WakeWordSettings(
    private val credentials: CredentialStore,
    private val readEnabled: () -> Boolean,
    private val writeEnabled: (Boolean) -> Unit,
) {
    fun isEnabled(): Boolean = readEnabled()

    fun setEnabled(enabled: Boolean) = writeEnabled(enabled)

    fun loadCredentials(): WakeWordCredentials = WakeWordCredentials.readFrom(credentials)

    fun saveCredentials(value: WakeWordCredentials) = value.writeTo(credentials)

    companion object {
        private const val PREFS = "nova_baidu_settings"
        private const val KEY_ENABLED = "wake_word_enabled"

        fun from(context: Context): WakeWordSettings {
            val app = context.applicationContext
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return WakeWordSettings(
                credentials = AndroidKeystoreCredentialStore(app),
                readEnabled = { prefs.getBoolean(KEY_ENABLED, false) },
                writeEnabled = { prefs.edit().putBoolean(KEY_ENABLED, it).apply() },
            )
        }
    }
}
