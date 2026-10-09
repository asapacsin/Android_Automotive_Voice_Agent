package com.novadrive.app.voice

import com.novadrive.app.DebugVoiceLog
import com.novadrive.app.QwenApiConfig
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.ProviderCapabilities
import com.novadrive.ingress.realtime.RealtimeEvent
import com.novadrive.ingress.realtime.RealtimeSessionConfig
import com.novadrive.ingress.realtime.RealtimeVoiceProvider
import com.novadrive.ingress.realtime.SystemSessionClock
import com.novadrive.ingress.realtime.ToolResult
import com.novadrive.ingress.realtime.VoiceCatalog
import com.novadrive.ingress.realtime.VoiceProviderId
import com.novadrive.ingress.realtime.WorkInjection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking

/**
 * Qwen-Omni Realtime behind the seam (SPEC-021, ADR-017): the model speaks in its own stock voice,
 * so there is no revoicer and no AssistantVoice. Opt-in only; never a fallback (ADR-013).
 */
class QwenOmniProvider(
    private val apiConfig: QwenApiConfig,
    private val client: QwenOmniClient = QwenOmniClient(),
    private val repliesSpoken: () -> Boolean = { true },
) : RealtimeVoiceProvider {
    /** Secondary constructor: the microphone's measurement of the audio that caused each turn. */
    constructor(
        apiConfig: QwenApiConfig,
        lastAudioSegment: () -> SpeechUplinkGate.Segment?,
        speechEvidence: () -> Boolean = { true },
        repliesSpoken: () -> Boolean = { true },
    ) : this(
        apiConfig,
        QwenOmniClient(lastAudioSegment = lastAudioSegment, speechEvidence = speechEvidence),
        repliesSpoken,
    )

    override val providerId = "qwen.omni.realtime.direct"
    override val capabilities: ProviderCapabilities = VoiceCatalog.capabilities(VoiceProviderId.QWEN)
    private val cueScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cueMarks = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 8)
    private val cueClock = WaitCueClock(
        scope = cueScope,
        clock = SystemSessionClock::nowMs,
        speaks = repliesSpoken,
        onVisual = { showing ->
            cueMarks.tryEmit(RealtimeEvent(SystemSessionClock.nowMs(), DomainVoiceEvent.WaitCueVisual(showing)))
        },
        onSpeak = { cue, elapsed ->
            if (client.speakFixedCue(cue.text)) {
                DebugVoiceLog.log("wait_cue code=${cue.code} after_ms=$elapsed")
            }
        },
    )

    override fun events(): Flow<RealtimeEvent> = merge(
        client.events().onEach { cueClock.onProviderEvent(it.payload) },
        cueMarks,
    )
    override suspend fun connect(config: RealtimeSessionConfig) {
        VoiceCatalog.requireAllowed(VoiceProviderId.QWEN, config.model)
        client.connect(apiConfig)
    }
    override fun connect(model: String) = runBlocking { connect(RealtimeSessionConfig(VoiceProviderId.QWEN, model)) }
    override suspend fun disconnect() = client.disconnect()
    override fun sendAudio(pcm16le: ByteArray) = client.sendAudio(pcm16le)
    override suspend fun cancelAssistantResponse(): DomainVoiceEvent {
        cueClock.endTurn("client_cancel")
        client.cancelResponse()
        return DomainVoiceEvent.Interrupted("client_cancelled")
    }
    override suspend fun injectWorkResult(result: WorkInjection): DomainVoiceEvent {
        client.sendFunctionResult(result.callId, result.output)
        cueClock.onToolResultDelivered()
        return DomainVoiceEvent.WorkResult(result.callId, result.output)
    }
    override suspend fun sendText(text: String) = client.sendUserText(text)
    override fun discardPendingAudio() = client.discardPendingAudio()
    override fun resumeListening() = client.resumeListening()
    override suspend fun cancelActiveResponse(): DomainVoiceEvent {
        cueClock.endTurn("client_cancel")
        client.cancelActiveResponse()
        return DomainVoiceEvent.Interrupted("client_cancelled")
    }
    override fun interrupt() = runBlocking { cancelAssistantResponse() }
    override fun sendToolResult(result: ToolResult) = runBlocking {
        injectWorkResult(WorkInjection(result.callId, result.ok, result.output))
    }
    override fun close() {
        cueClock.close()
        cueScope.cancel()
        client.close()
    }

    override fun onPlaybackActiveChanged(active: Boolean) = client.onPlaybackActiveChanged(active)

    /**
     * The live session does not call this: Qwen reports speech itself. The same clock still runs
     * if a caller does forward local activity.
     */
    override fun onLocalSpeechActivity(active: Boolean) {
        cueClock.onSpeech(active, if (active) 0L else SpeechUplinkGate.HANGOVER_MS.toLong())
    }

    override fun onPlaybackFlushed() {
        cueClock.onUsefulSpeech()
    }
}
