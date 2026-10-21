package com.novadrive.app.voice

import com.novadrive.app.BaiduApiConfig
import com.novadrive.ingress.realtime.VoiceProviderException
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * Baidu Qianfan Realtime Flex: [OpenAiRealtimeClient] driven by [BaiduFlexDialect] (SPEC-021).
 * Turn handling lives in the base class; this class only keeps the Baidu constructor and the
 * voice-confirmation members its callers read.
 */
class BaiduFlexClient(
    http: OkHttpClient = defaultHttp(),
    readyTimeoutMs: Long = 10_000,
    requireTls: Boolean = true,
    tokenClient: BaiduAccessTokenClient = BaiduAccessTokenClient(http),
    contextHint: () -> String? = { VoiceContextHints.current() },
    /** Shape of the audio that caused the current turn, measured by [SpeechUplinkGate]. */
    lastAudioSegment: () -> SpeechUplinkGate.Segment? = { null },
    /** True when something on screen is waiting for the driver's answer; such turns are never held. */
    contextAwaitingAnswer: () -> Boolean = { VoiceContextHints.awaitingAnswer() },
    /** Time-scoped post-AEC speech evidence for speech over playback (Astra P4). */
    speechEvidence: () -> Boolean = { true },
) : OpenAiRealtimeClient<BaiduApiConfig>(
    BaiduFlexDialect(tokenClient, requireTls),
    http,
    readyTimeoutMs,
    contextHint,
    lastAudioSegment,
    contextAwaitingAnswer,
    speechEvidence,
) {
    private val flex: BaiduFlexDialect get() = dialect as BaiduFlexDialect

    /** Voice id last requested in session.update (before any fallback). */
    val requestedVoice: String get() = flex.requestedVoice

    /** Voice echoed by the last session.updated (or FALLBACK_VOICE after recovery). */
    val confirmedVoice: String? get() = flex.confirmedVoice

    val voiceConfirmedAsRequested: Boolean
        get() = confirmedVoice != null && confirmedVoice == requestedVoice

    companion object {
        private fun defaultHttp() = OpenAiRealtimeClient.defaultHttp()

        fun mapFailure(failure: Throwable, response: Response?): VoiceProviderException =
            BaiduFlexDialect.mapFailure(failure, response)
    }
}
