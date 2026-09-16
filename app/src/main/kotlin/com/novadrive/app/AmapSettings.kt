package com.novadrive.app

import android.content.Context

class AmapSettingsRepository(
    context: Context,
    private val credentials: CredentialStore = AndroidKeystoreCredentialStore(context.applicationContext),
) {
    fun loadWebKey(): String? = credentials.read(CRED_WEB_KEY)?.takeIf { it.isNotBlank() }

    fun isConfigured(): Boolean = loadWebKey() != null

    fun save(update: CredentialUpdate) {
        credentials.applyCredentialUpdate(CRED_WEB_KEY, update)
    }

    fun clear() {
        credentials.clear(CRED_WEB_KEY)
    }

    private companion object {
        const val CRED_WEB_KEY = "amap_web_key"
    }
}
