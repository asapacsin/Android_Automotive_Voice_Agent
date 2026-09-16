package com.novadrive.app

import android.content.Context
import com.novadrive.app.wake.WakeWordSettings
import com.novadrive.ingress.realtime.VoiceProviderId

/** Compatibility facade for callers that still use the earlier developer-option API. */
object DeveloperOptions {
    fun settings(context: Context): BaiduAppSettings = BaiduSettingsRepository(context).loadSettings()

    fun backendUrl(context: Context): String = ""

    fun provider(context: Context): VoiceProviderId =
        if (settings(context).runtimeProvider == BaiduRuntimeProvider.FLEX) VoiceProviderId.BAIDU_FLEX else VoiceProviderId.BAIDU

    fun model(context: Context): String = settings(context).model

    fun providerLabel(context: Context): String = if (provider(context) == VoiceProviderId.BAIDU_FLEX) "Baidu Flex Direct" else "Baidu Lite Direct"

    fun wakeWordEnabled(context: Context): Boolean = WakeWordSettings.from(context).isEnabled()
}
