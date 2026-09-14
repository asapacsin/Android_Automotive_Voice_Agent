package com.novadrive.app

import android.content.Context
import android.content.SharedPreferences
import com.novadrive.ingress.realtime.VoiceCatalog
import com.novadrive.ingress.realtime.VoiceModels
import com.novadrive.ingress.realtime.VoiceProviderId

object DeveloperOptions {
    private const val PREFS = "nova_developer"
    const val KEY_BACKEND_URL = "backend_url"
    const val KEY_MODEL = "model"
    const val KEY_PROVIDER = "provider"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun backendUrl(context: Context): String {
        val stored = prefs(context).getString(KEY_BACKEND_URL, null)?.trim().orEmpty()
        return stored.ifBlank { context.getString(R.string.nova_backend_url) }
    }

    fun provider(context: Context): VoiceProviderId {
        val stored = prefs(context).getString(KEY_PROVIDER, null)?.trim().orEmpty()
        return if (stored.isBlank()) {
            VoiceCatalog.DEFAULT_PROVIDER
        } else {
            runCatching { VoiceProviderId.fromWire(stored) }.getOrDefault(VoiceCatalog.DEFAULT_PROVIDER)
        }
    }

    fun model(context: Context): String {
        val stored = prefs(context).getString(KEY_MODEL, null)?.trim().orEmpty()
        return stored.ifBlank { VoiceCatalog.defaultModel(provider(context)) }
    }

    fun providerLabel(context: Context): String =
        when (provider(context)) {
            VoiceProviderId.QWEN -> "Qwen"
            VoiceProviderId.GPT_LIVE -> "GPT Live"
            VoiceProviderId.BAIDU -> "Baidu"
            VoiceProviderId.FAKE -> "Fake"
        }

    fun save(context: Context, backendUrl: String, model: String, provider: VoiceProviderId = VoiceCatalog.providerForModel(model)) {
        VoiceModels.requireAllowed(model)
        prefs(context).edit()
            .putString(KEY_BACKEND_URL, backendUrl.trim())
            .putString(KEY_MODEL, model)
            .putString(KEY_PROVIDER, provider.wireName)
            .apply()
    }
}
