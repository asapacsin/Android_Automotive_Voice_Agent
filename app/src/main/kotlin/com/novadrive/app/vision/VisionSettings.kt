package com.novadrive.app.vision

import android.content.Context
import com.novadrive.app.AndroidKeystoreCredentialStore
import com.novadrive.app.BaiduAuthMode
import com.novadrive.app.BaiduSettingsRepository
import com.novadrive.app.CredentialStore
import com.novadrive.app.voice.BaiduAccessTokenClient
import okhttp3.OkHttpClient

/**
 * Vision model settings. The optional dedicated key is a Qianfan API key stored in the Android
 * Keystore like every other credential; it is never written to source, logs or the APK.
 */
class VisionSettings(
    private val credentials: CredentialStore,
    private val readModel: () -> String?,
    private val writeModel: (String) -> Unit,
) {
    fun model(): String = readModel()?.takeIf { it.isNotBlank() } ?: QianfanVisionClient.DEFAULT_MODEL

    fun saveModel(model: String) = writeModel(model.trim())

    fun dedicatedKey(): String? = credentials.read(KEY_API_KEY)?.takeIf { it.isNotBlank() }

    fun hasDedicatedKey(): Boolean = dedicatedKey() != null

    fun saveDedicatedKey(value: String) = credentials.write(KEY_API_KEY, value.trim())

    fun clearDedicatedKey() = credentials.clear(KEY_API_KEY)

    companion object {
        const val KEY_API_KEY = "vision_api_key"
        private const val PREFS = "nova_vision_settings"
        private const val KEY_MODEL = "model"

        fun from(context: Context): VisionSettings {
            val app = context.applicationContext
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return VisionSettings(
                credentials = AndroidKeystoreCredentialStore(app),
                readModel = { prefs.getString(KEY_MODEL, null) },
                writeModel = { prefs.edit().putString(KEY_MODEL, it).apply() },
            )
        }
    }
}

/**
 * Credential order: the dedicated vision key if set; otherwise the Baidu voice credential —
 * a Bearer API key directly, or an OAuth access token obtained from the API Key + Secret Key.
 *
 * UNVERIFIED: whether Qianfan v2 accepts the legacy OAuth access token as a Bearer value. If it
 * does not, the request fails with an auth error and the UI says to enter a vision API key.
 */
class LiveVisionAuth(
    context: Context,
    private val http: OkHttpClient = OkHttpClient(),
) : VisionAuth {
    private val app = context.applicationContext
    private val tokens = BaiduAccessTokenClient(http)

    override suspend fun bearer(): String? {
        VisionSettings.from(app).dedicatedKey()?.let { return it }
        val repository = BaiduSettingsRepository(app)
        val settings = repository.loadSettings()
        val credentials = repository.loadCredentials()
        return when (settings.authMode) {
            BaiduAuthMode.BEARER_API_KEY -> credentials.apiKey.takeIf { it.isNotBlank() }
            BaiduAuthMode.LEGACY_ACCESS_TOKEN ->
                if (credentials.apiKey.isBlank() || credentials.secretKey.isBlank()) {
                    null
                } else {
                    tokens.getToken(settings.tokenEndpoint, credentials)
                }
        }
    }
}
