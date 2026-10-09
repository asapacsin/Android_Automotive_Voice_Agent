package com.novadrive.app.voice

import com.novadrive.app.QwenApiConfig
import com.novadrive.app.QwenAppSettings
import com.novadrive.app.QwenSettings
import com.novadrive.ingress.realtime.VoiceCatalog
import com.novadrive.ingress.realtime.VoiceProviderId
import okhttp3.OkHttpClient

/** Qwen-Omni Realtime: [OpenAiRealtimeClient] driven by [QwenOmniDialect] (SPEC-021). */
class QwenOmniClient(
    http: OkHttpClient = OpenAiRealtimeClient.defaultHttp(),
    readyTimeoutMs: Long = 10_000,
    requireTls: Boolean = true,
    /** The WebSocket URL for the settings; tests point it at a local server. */
    endpoint: (QwenAppSettings) -> String = QwenSettings::endpointUrl,
    contextHint: () -> String? = { VoiceContextHints.current() },
    /** Shape of the audio that caused the current turn, measured by [SpeechUplinkGate]. */
    lastAudioSegment: () -> SpeechUplinkGate.Segment? = { null },
    /** True when something on screen is waiting for the driver's answer; such turns are never held. */
    contextAwaitingAnswer: () -> Boolean = { VoiceContextHints.awaitingAnswer() },
    /** Time-scoped post-AEC speech evidence for speech over playback (Astra P4). */
    speechEvidence: () -> Boolean = { true },
    responseStallMs: Long = ResponseStallWatchdog.STALL_MS,
) : OpenAiRealtimeClient<QwenApiConfig>(
    QwenOmniDialect(requireTls, endpoint),
    http,
    readyTimeoutMs,
    contextHint,
    lastAudioSegment,
    contextAwaitingAnswer,
    speechEvidence,
    streamedReplyText = VoiceCatalog.capabilities(VoiceProviderId.QWEN).streamedReplyText,
    responseStallMs = responseStallMs,
)
