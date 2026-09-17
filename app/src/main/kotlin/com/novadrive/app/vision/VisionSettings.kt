package com.novadrive.app.vision

import android.content.Context
import com.novadrive.app.AndroidKeystoreCredentialStore
import com.novadrive.app.BaiduAuthMode
import com.novadrive.app.BaiduSettingsRepository
import com.novadrive.app.CredentialStore

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
 * Credential order: the dedicated vision key if set; otherwise the Baidu voice key, but only
 * when it is already a Qianfan API key (BEARER_API_KEY mode).
 *
 * MEASURED 2026-09-17: Qianfan v2 REJECTS the legacy OAuth access token
 * (`HTTP 401 invalid_iam_token`). So with LEGACY_ACCESS_TOKEN voice credentials this returns
 * null and no request is made — the camera image is never uploaded just to be refused.
 */
class LiveVisionAuth(context: Context) : VisionAuth {
    private val app = context.applicationContext

    override suspend fun bearer(): String? {
        VisionSettings.from(app).dedicatedKey()?.let { return it }
        val repository = BaiduSettingsRepository(app)
        return when (repository.loadSettings().authMode) {
            BaiduAuthMode.BEARER_API_KEY -> repository.loadCredentials().apiKey.takeIf { it.isNotBlank() }
            BaiduAuthMode.LEGACY_ACCESS_TOKEN -> null
        }
    }
}
