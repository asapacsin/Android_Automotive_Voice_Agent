package com.novadrive.app

import java.net.URI

data class VoicePreferenceSnapshot(
    val backendUrl: String? = null,
    val providerWire: String? = null,
    val model: String? = null,
)

data class VoiceBuildDefaults(
    val backendUrl: String,
    val providerWire: String,
    val model: String,
)

object LocalConnectivity {
    const val EMULATOR_HOST = "10.0.2.2"
    const val OBSOLETE_DEFAULT_PROVIDER = "baidu"
    const val OBSOLETE_DEFAULT_MODEL = "audio-mini-realtime-near"

    fun migratePreferences(
        stored: VoicePreferenceSnapshot,
        build: VoiceBuildDefaults,
    ): VoicePreferenceSnapshot {
        val url = stored.backendUrl.normalized()
        val provider = stored.providerWire.normalized()
        val model = stored.model.normalized()
        val nextUrl =
            if (url != null && isEmulatorUrl(url) && !isEmulatorUrl(build.backendUrl)) {
                build.backendUrl
            } else {
                stored.backendUrl
            }
        val nextProvider: String?
        val nextModel: String?
        if (isObsoleteBaiduDefaultPair(provider, model) &&
            !isObsoleteBaiduDefaultPair(build.providerWire, build.model)
        ) {
            nextProvider = build.providerWire
            nextModel = build.model
        } else {
            nextProvider = stored.providerWire
            nextModel = stored.model
        }
        return VoicePreferenceSnapshot(nextUrl, nextProvider, nextModel)
    }

    fun resolvePreferences(
        stored: VoicePreferenceSnapshot,
        build: VoiceBuildDefaults,
    ): VoiceBuildDefaults {
        val migrated = migratePreferences(stored, build)
        return VoiceBuildDefaults(
            backendUrl = migrated.backendUrl.normalized() ?: build.backendUrl,
            providerWire = migrated.providerWire.normalized() ?: build.providerWire,
            model = migrated.model.normalized() ?: build.model,
        )
    }

    fun backendHostPort(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return "unknown"
        return try {
            val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
            val uri = URI(withScheme)
            val host = uri.host
            if (host.isNullOrBlank()) {
                sanitizeFallback(trimmed)
            } else if (uri.port > 0) {
                "$host:${uri.port}"
            } else {
                host
            }
        } catch (_: Exception) {
            sanitizeFallback(trimmed)
        }
    }

    fun backendWsFailedMessage(attemptedUrl: String): String {
        val hostPort = backendHostPort(attemptedUrl)
        return "无法连接后端 $hostPort。请在电脑上启动 backend 并监听 0.0.0.0:8000，把 Backend URL 设为电脑的局域网 IP；真机不要使用 10.0.2.2。"
    }

    fun credentialsFooter(currentProviderLabel: String, isFake: Boolean): String {
        val local =
            if (isFake) {
                "当前本地冒烟测试：$currentProviderLabel（非产品默认）。"
            } else {
                "当前本地：$currentProviderLabel。"
            }
        return "凭证只写在 backend/.env。产品默认 Qwen Flash。${local}App 不输入 API Key。"
    }

    fun isEmulatorUrl(url: String?): Boolean {
        val hostPort = backendHostPort(url ?: return false)
        if (hostPort == "unknown") return false
        val host = hostPort.substringBeforeLast(':')
        return host == EMULATOR_HOST
    }

    private fun isObsoleteBaiduDefaultPair(provider: String?, model: String?): Boolean {
        val providerWire = provider.normalized()?.lowercase()?.replace('-', '_')
        val modelId = model.normalized()
        val storedAnything = providerWire != null || modelId != null
        val providerIsBaiduOrMissing = providerWire == null || providerWire == OBSOLETE_DEFAULT_PROVIDER
        val modelIsLiteNearOrMissing = modelId == null || modelId == OBSOLETE_DEFAULT_MODEL
        return storedAnything && providerIsBaiduOrMissing && modelIsLiteNearOrMissing
    }

    private fun sanitizeFallback(raw: String): String {
        var value = raw.trim().substringBefore('?').substringBefore('#')
        value = value.replace(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://"), "")
        if ('@' in value) {
            value = value.substringAfterLast('@')
        }
        value = value.substringBefore('/')
        return value.ifBlank { "unknown" }
    }

    private fun String?.normalized(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
