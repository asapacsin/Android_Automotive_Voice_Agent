package com.novadrive.app.voice

import com.novadrive.app.BaiduApiConfig
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.ProviderCapabilities
import com.novadrive.ingress.realtime.RealtimeEvent
import com.novadrive.ingress.realtime.RealtimeSessionConfig
import com.novadrive.ingress.realtime.RealtimeVoiceProvider
import com.novadrive.ingress.realtime.ToolResult
import com.novadrive.ingress.realtime.VoiceCatalog
import com.novadrive.ingress.realtime.VoiceProviderId
import com.novadrive.ingress.realtime.WorkInjection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking

class BaiduFlexProvider(
    private val apiConfig: BaiduApiConfig,
    private val client: BaiduFlexClient = BaiduFlexClient(),
) : RealtimeVoiceProvider {
    /** Secondary constructor: the microphone's measurement of the audio that caused each turn. */
    constructor(
        apiConfig: BaiduApiConfig,
        lastAudioSegment: () -> SpeechUplinkGate.Segment?,
        speechEvidence: () -> Boolean = { true },
    ) : this(apiConfig, BaiduFlexClient(lastAudioSegment = lastAudioSegment, speechEvidence = speechEvidence))

    override val providerId = "baidu.flex.realtime.direct"
    override val capabilities: ProviderCapabilities = VoiceCatalog.capabilities(VoiceProviderId.BAIDU_FLEX)
    override fun events(): Flow<RealtimeEvent> = client.events()
    override suspend fun connect(config: RealtimeSessionConfig) {
        VoiceCatalog.requireAllowed(VoiceProviderId.BAIDU_FLEX, config.model)
        client.connect(apiConfig.copy(settings = apiConfig.settings.copy(model = BaiduFlexProtocol.MODEL)))
    }
    override fun connect(model: String) = runBlocking { connect(RealtimeSessionConfig(VoiceProviderId.BAIDU_FLEX, model)) }
    override suspend fun disconnect() = client.disconnect()
    override fun sendAudio(pcm16le: ByteArray) = client.sendAudio(pcm16le)
    override suspend fun cancelAssistantResponse(): DomainVoiceEvent {
        client.cancelResponse(); return DomainVoiceEvent.Interrupted("client_cancelled")
    }
    override suspend fun injectWorkResult(result: WorkInjection): DomainVoiceEvent {
        client.sendFunctionResult(result.callId, result.output)
        return DomainVoiceEvent.WorkResult(result.callId, result.output)
    }
    override suspend fun sendText(text: String) = client.sendUserText(text)
    override fun discardPendingAudio() = client.discardPendingAudio()
    override fun resumeListening() = client.resumeListening()
    override suspend fun cancelActiveResponse(): DomainVoiceEvent {
        client.cancelActiveResponse(); return DomainVoiceEvent.Interrupted("client_cancelled")
    }
    override fun interrupt() = runBlocking { cancelAssistantResponse() }
    override fun sendToolResult(result: ToolResult) = runBlocking {
        injectWorkResult(WorkInjection(result.callId, result.ok, result.output))
    }
    override fun close() = client.close()

    fun onPlaybackActiveChanged(active: Boolean) = client.onPlaybackActiveChanged(active)
}
