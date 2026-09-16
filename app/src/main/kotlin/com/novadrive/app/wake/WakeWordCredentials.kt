package com.novadrive.app.wake

import com.novadrive.app.CredentialStore

data class WakeWordCredentials(
    val appId: String,
    val apiKey: String,
    val apiSecret: String,
) {
    fun isComplete(): Boolean = appId.isNotBlank()

    override fun toString(): String =
        "WakeWordCredentials(appId=REDACTED, apiKey=REDACTED, apiSecret=REDACTED)"

    fun writeTo(store: CredentialStore) {
        store.write(KEY_APP_ID, appId)
        store.write(KEY_API_KEY, apiKey)
        store.write(KEY_API_SECRET, apiSecret)
    }

    companion object {
        const val KEY_APP_ID = "iflytek_app_id"
        const val KEY_API_KEY = "iflytek_api_key"
        const val KEY_API_SECRET = "iflytek_api_secret"

        fun readFrom(store: CredentialStore): WakeWordCredentials =
            WakeWordCredentials(
                appId = store.read(KEY_APP_ID).orEmpty(),
                apiKey = store.read(KEY_API_KEY).orEmpty(),
                apiSecret = store.read(KEY_API_SECRET).orEmpty(),
            )
    }
}
