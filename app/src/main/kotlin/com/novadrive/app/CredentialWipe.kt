package com.novadrive.app

import android.content.Context
import com.novadrive.app.vision.VisionSettings
import com.novadrive.app.wake.WakeWordCredentials

/**
 * The developer screen's 「清除所有密钥」: every stored secret, including the inert Baidu, Gemini
 * and Azure ones kept until ADR-017 Q-5. Kept outside the activity so the activity never names the
 * dormant providers' repositories. Never reads or prints a value.
 */
object CredentialWipe {
    fun clearAll(context: Context) {
        QwenSettingsRepository(context).clearKey()
        AmapSettingsRepository(context).clear()
        VisionSettings.from(context).clearDedicatedKey()
        AndroidKeystoreCredentialStore(context.applicationContext).clear(WakeWordCredentials.KEY_APP_ID)
        BaiduSettingsRepository(context).clearAllCredentials()
        GeminiSettingsRepository(context).clearKey()
        AzureSpeechSettingsRepository(context).clearKey()
    }
}
