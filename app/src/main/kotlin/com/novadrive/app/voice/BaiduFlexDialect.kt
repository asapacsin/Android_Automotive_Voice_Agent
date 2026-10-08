package com.novadrive.app.voice

import com.novadrive.app.BaiduApiConfig
import com.novadrive.app.BaiduAppSettings
import com.novadrive.app.BaiduAuthMode
import com.novadrive.app.DebugVoiceLog
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.VoiceProviderException
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

/** Baidu Qianfan Realtime Flex on [OpenAiRealtimeClient]: wire format, auth and voice fallback. */
class BaiduFlexDialect(
    private val tokenClient: BaiduAccessTokenClient,
    private val requireTls: Boolean,
) : RealtimeDialect<BaiduApiConfig> {
    override val logPrefix = "flex"
    override val defaultVadThreshold = BaiduFlexProtocol.DEFAULT_VAD_THRESHOLD

    @Volatile private var voice: String = BaiduAppSettings.DEFAULT_VOICE
    @Volatile private var speed: Double = BaiduAppSettings.DEFAULT_SPEED
    @Volatile private var sentVoice: String = BaiduAppSettings.DEFAULT_VOICE
    @Volatile private var voiceFallbackUsed = false
    /** Voice id last requested in session.update (before any fallback). */
    @Volatile var requestedVoice: String = BaiduAppSettings.DEFAULT_VOICE
        private set
    /** Voice echoed by the last session.updated (or FALLBACK_VOICE after recovery). */
    @Volatile var confirmedVoice: String? = null
        private set

    override suspend fun buildRequest(config: BaiduApiConfig): Request {
        val effective = effective(config)
        val validationSettings = if (requireTls) effective.settings else effective.settings.copy(
            endpoint = effective.settings.endpoint.replaceFirst("ws://", "wss://"),
            tokenEndpoint = effective.settings.tokenEndpoint.replaceFirst("http://", "https://"),
        )
        com.novadrive.app.BaiduSettingsValidator.validate(validationSettings, effective.credentials)?.let {
            throw VoiceProviderException(it, it)
        }
        val token = if (effective.settings.authMode == BaiduAuthMode.LEGACY_ACCESS_TOKEN) {
            tokenClient.getToken(effective.settings.tokenEndpoint, effective.credentials)
        } else null
        val endpoint = BaiduRealtimeClient.buildUrl(effective.settings.endpoint, BaiduFlexProtocol.MODEL, token, requireTls)
        return Request.Builder().url(endpoint).apply {
            if (effective.settings.authMode == BaiduAuthMode.BEARER_API_KEY) {
                header("Authorization", "Bearer ${effective.credentials.apiKey}")
            }
        }.build()
    }

    override fun onSessionOpening(config: BaiduApiConfig) {
        val settings = effective(config).settings
        voice = settings.voice
        speed = settings.speed
        sentVoice = voice
        requestedVoice = voice
        confirmedVoice = null
        voiceFallbackUsed = false
    }

    override fun instructions(config: BaiduApiConfig): String? = effective(config).settings.instructions

    override fun vadThreshold(navigating: Boolean): Double =
        BaiduFlexProtocol.playbackScopedVadThreshold(false, navigating)

    override fun sessionUpdate(instructions: String, vadThreshold: Double): String =
        BaiduFlexProtocol.sessionUpdate(instructions, voice, speed, vadThreshold)

    override fun recoverSessionError(instructions: () -> String, vadThreshold: Double): String? {
        if (voiceFallbackUsed || sentVoice == BaiduAppSettings.FALLBACK_VOICE) return null
        voiceFallbackUsed = true
        sentVoice = BaiduAppSettings.FALLBACK_VOICE
        return BaiduFlexProtocol.sessionUpdate(instructions(), BaiduAppSettings.FALLBACK_VOICE, speed, vadThreshold)
    }

    override fun onSessionUpdated(text: String) {
        val echoed = JSONObject(text).optJSONObject("session")?.optString("voice").orEmpty()
        confirmedVoice = echoed.ifBlank { sentVoice }
        DebugVoiceLog.log(
            "flex_voice requested=$requestedVoice confirmed=$confirmedVoice " +
                "match=${confirmedVoice == requestedVoice} fallback=$voiceFallbackUsed",
        )
    }

    override fun newCallAssembler(): RealtimeCallAssembler = FlexFunctionCallAssembler()
    override fun audioAppend(base64Audio: String) = BaiduFlexProtocol.audioAppend(base64Audio)
    override fun responseCancel() = BaiduFlexProtocol.responseCancel()
    override fun responseCreate() = BaiduFlexProtocol.responseCreate()
    override fun functionCallOutput(callId: String, output: String) = BaiduFlexProtocol.functionCallOutput(callId, output)
    override fun userTextMessage(text: String) = BaiduFlexProtocol.userTextMessage(text)
    override fun parseCommonEvent(text: String, speaking: Boolean): List<DomainVoiceEvent> =
        BaiduFlexProtocol.parseCommonEvent(text, speaking)
    override fun errorCode(text: String) = BaiduFlexProtocol.errorCode(text)
    override fun isResponseAlreadyActive(text: String) = BaiduFlexProtocol.isResponseAlreadyActive(text)

    override fun readyTimeout() = VoiceProviderException("BAIDU_FLEX_TIMEOUT", "Baidu Flex session readiness timed out")
    override fun notConnected() = VoiceProviderException("BAIDU_FLEX_CONNECTION_CLOSED", "Baidu Flex WebSocket is not connected")
    override fun connectionClosed(status: Int) =
        VoiceProviderException("BAIDU_FLEX_CONNECTION_CLOSED", "Baidu Flex WebSocket closed (status=$status)")
    override fun sessionFailed() = VoiceProviderException("BAIDU_FLEX_SESSION_FAILED", "failed to configure Baidu Flex session")
    override fun protocolError(cause: Throwable) =
        VoiceProviderException("BAIDU_FLEX_PROTOCOL_ERROR", "invalid Baidu Flex realtime event", cause)
    override fun mapFailure(failure: Throwable, response: Response?) = Companion.mapFailure(failure, response)

    companion object {
        fun mapFailure(failure: Throwable, response: Response?): VoiceProviderException {
            if (response?.code == 403) return VoiceProviderException(
                "BAIDU_FLEX_ACCESS_DENIED",
                "Baidu Flex public-beta/model access was denied (HTTP 403)",
                failure,
            )
            val base = BaiduRealtimeClient.mapFailure(failure, response)
            return VoiceProviderException(base.code.replace("BAIDU_", "BAIDU_FLEX_"), base.safeMessage, failure)
        }

        private fun effective(config: BaiduApiConfig) =
            config.copy(settings = config.settings.copy(model = BaiduFlexProtocol.MODEL))
    }
}
