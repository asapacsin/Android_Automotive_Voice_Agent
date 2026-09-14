package com.novadrive.ingress.realtime

/**
 * Central provider/model catalog. UI and business code select these ids only.
 * Vendor JSON stays in backend adapters.
 */
enum class VoiceProviderId {
    QWEN,
    GPT_LIVE,
    BAIDU,
    FAKE,
    ;

    val wireName: String
        get() =
            when (this) {
                QWEN -> "qwen"
                GPT_LIVE -> "gpt_live"
                BAIDU -> "baidu"
                FAKE -> "fake"
            }

    companion object {
        fun fromWire(raw: String): VoiceProviderId {
            val normalized = raw.trim().lowercase().replace('-', '_')
            return when (normalized) {
                "qwen" -> QWEN
                "gpt_live", "gptlive", "openai_live" -> GPT_LIVE
                "baidu" -> BAIDU
                "fake", "mock" -> FAKE
                else -> error("UNKNOWN_VOICE_PROVIDER")
            }
        }
    }
}

enum class InteractionMode {
    CONTINUOUS,
    PTT,
    MUTED,
}

data class RealtimeAudioConfig(
    val inputSampleRateHz: Int = 16_000,
    val outputSampleRateHz: Int = 16_000,
    val channels: Int = 1,
    val pcm16le: Boolean = true,
)

data class RealtimeModelConfig(
    val provider: VoiceProviderId,
    val model: String,
)

data class RealtimeSessionConfig(
    val provider: VoiceProviderId = VoiceCatalog.DEFAULT_PROVIDER,
    val model: String = VoiceCatalog.DEFAULT_MODEL,
    val audio: RealtimeAudioConfig = RealtimeAudioConfig(),
    val interactionMode: InteractionMode = InteractionMode.CONTINUOUS,
)

data class ProviderCapabilities(
    val provider: VoiceProviderId,
    val customTools: Boolean,
    val serverVadInterrupt: Boolean,
    val clientResponseCancel: Boolean,
    val optionalInputCommit: Boolean,
    val workResultInjection: Boolean,
    val unknownEventTolerance: Boolean,
    val requiresCredentials: Boolean,
)

enum class RealtimeConnectionState {
    IDLE,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    DISCONNECTED,
    FAILED,
}

object VoiceCatalog {
    const val DEFAULT_PROVIDER_WIRE = "qwen"
    val DEFAULT_PROVIDER: VoiceProviderId = VoiceProviderId.QWEN
    const val DEFAULT_MODEL = "qwen-audio-3.0-realtime-flash"
    const val QWEN_FLASH = "qwen-audio-3.0-realtime-flash"
    const val QWEN_PLUS = "qwen-audio-3.0-realtime-plus"
    const val GPT_LIVE_1 = "gpt-live-1"
    const val BAIDU_LITE_NEAR = "audio-mini-realtime-near"
    const val BAIDU_LITE_FAR = "audio-mini-realtime-far"
    const val BAIDU_PRO_NEAR = "audio-realtime-near"
    const val BAIDU_PRO_FAR = "audio-realtime-far"
    const val FAKE_MODEL = "fake-realtime"

    val qwenModels: Map<String, String> =
        mapOf(
            QWEN_FLASH to "Qwen Flash",
            QWEN_PLUS to "Qwen Plus",
        )
    val gptLiveModels: Map<String, String> =
        mapOf(GPT_LIVE_1 to "GPT Live 1")
    val baiduModels: Map<String, String> =
        mapOf(
            BAIDU_LITE_NEAR to "Lite Near",
            BAIDU_LITE_FAR to "Lite Far",
            BAIDU_PRO_NEAR to "Pro Near",
            BAIDU_PRO_FAR to "Pro Far",
        )
    val fakeModels: Map<String, String> =
        mapOf(FAKE_MODEL to "Fake")

    val selectableLabels: Map<String, String> =
        qwenModels + gptLiveModels + baiduModels + fakeModels

    fun defaultModel(provider: VoiceProviderId): String =
        when (provider) {
            VoiceProviderId.QWEN -> QWEN_FLASH
            VoiceProviderId.GPT_LIVE -> GPT_LIVE_1
            VoiceProviderId.BAIDU -> BAIDU_LITE_NEAR
            VoiceProviderId.FAKE -> FAKE_MODEL
        }

    fun modelsFor(provider: VoiceProviderId): Map<String, String> =
        when (provider) {
            VoiceProviderId.QWEN -> qwenModels
            VoiceProviderId.GPT_LIVE -> gptLiveModels
            VoiceProviderId.BAIDU -> baiduModels
            VoiceProviderId.FAKE -> fakeModels
        }

    fun providerForModel(model: String): VoiceProviderId =
        when (model) {
            in qwenModels -> VoiceProviderId.QWEN
            in gptLiveModels -> VoiceProviderId.GPT_LIVE
            in baiduModels -> VoiceProviderId.BAIDU
            in fakeModels -> VoiceProviderId.FAKE
            else -> error("UNKNOWN_MODEL")
        }

    fun requireAllowed(model: String): String {
        require(model in selectableLabels) { "UNKNOWN_MODEL" }
        return model
    }

    fun requireAllowed(provider: VoiceProviderId, model: String): String {
        val allowed = modelsFor(provider)
        require(model in allowed) { invalidModelCode(provider) }
        return model
    }

    fun invalidModelCode(provider: VoiceProviderId): String =
        when (provider) {
            VoiceProviderId.QWEN -> "QWEN_INVALID_MODEL"
            VoiceProviderId.GPT_LIVE -> "GPT_LIVE_INVALID_MODEL"
            VoiceProviderId.BAIDU -> "BAIDU_INVALID_MODEL"
            VoiceProviderId.FAKE -> "FAKE_INVALID_MODEL"
        }

    fun capabilities(provider: VoiceProviderId): ProviderCapabilities =
        when (provider) {
            VoiceProviderId.QWEN ->
                ProviderCapabilities(
                    provider = provider,
                    customTools = true,
                    serverVadInterrupt = true,
                    clientResponseCancel = true,
                    optionalInputCommit = true,
                    workResultInjection = true,
                    unknownEventTolerance = true,
                    requiresCredentials = true,
                )
            VoiceProviderId.GPT_LIVE ->
                ProviderCapabilities(
                    provider = provider,
                    customTools = true,
                    serverVadInterrupt = true,
                    clientResponseCancel = false,
                    optionalInputCommit = false,
                    workResultInjection = true,
                    unknownEventTolerance = true,
                    requiresCredentials = true,
                )
            VoiceProviderId.BAIDU ->
                ProviderCapabilities(
                    provider = provider,
                    customTools = false,
                    serverVadInterrupt = true,
                    clientResponseCancel = false,
                    optionalInputCommit = false,
                    workResultInjection = false,
                    unknownEventTolerance = true,
                    requiresCredentials = true,
                )
            VoiceProviderId.FAKE ->
                ProviderCapabilities(
                    provider = provider,
                    customTools = true,
                    serverVadInterrupt = true,
                    clientResponseCancel = true,
                    optionalInputCommit = true,
                    workResultInjection = true,
                    unknownEventTolerance = true,
                    requiresCredentials = false,
                )
        }
}

/** Backward-compatible aliases used by Checkpoint 1 / Baidu tests. */
object VoiceModels {
    const val DEFAULT = VoiceCatalog.DEFAULT_MODEL
    const val LITE_NEAR = VoiceCatalog.BAIDU_LITE_NEAR
    const val LITE_FAR = VoiceCatalog.BAIDU_LITE_FAR
    const val PRO_NEAR = VoiceCatalog.BAIDU_PRO_NEAR
    const val PRO_FAR = VoiceCatalog.BAIDU_PRO_FAR
    const val QWEN_FLASH = VoiceCatalog.QWEN_FLASH
    const val QWEN_PLUS = VoiceCatalog.QWEN_PLUS
    const val GPT_LIVE_1 = VoiceCatalog.GPT_LIVE_1
    const val FAKE = VoiceCatalog.FAKE_MODEL

    val labels: Map<String, String>
        get() = VoiceCatalog.selectableLabels

    fun requireAllowed(model: String): String = VoiceCatalog.requireAllowed(model)
}
