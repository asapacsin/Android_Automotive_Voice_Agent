package com.novadrive.app.voice

import com.novadrive.app.QwenApiConfig
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

class QwenDirectRealtimeProvider(
    private val apiConfig: QwenApiConfig,
    private val client: QwenRealtimeClient = QwenRealtimeClient(),
) : RealtimeVoiceProvider {
    override val providerId: String = "qwen.audio.realtime.direct"
    override val capabilities: ProviderCapabilities = VoiceCatalog.capabilities(VoiceProviderId.QWEN)

    override fun events(): Flow<RealtimeEvent> = client.events()

    override suspend fun connect(config: RealtimeSessionConfig) {
        VoiceCatalog.requireAllowed(VoiceProviderId.QWEN, config.model)
        client.connect(apiConfig.copy(model = config.model))
    }

    override fun connect(model: String) {
        runBlocking { connect(RealtimeSessionConfig(provider = VoiceProviderId.QWEN, model = model)) }
    }

    override suspend fun disconnect() = client.disconnect()

    override fun sendAudio(pcm16le: ByteArray) = client.sendAudio(pcm16le)

    override suspend fun commitInputAudio() = client.commit()

    override suspend fun cancelAssistantResponse(): DomainVoiceEvent {
        client.cancel()
        return DomainVoiceEvent.Interrupted("client_cancelled")
    }

    override suspend fun injectWorkResult(result: WorkInjection): DomainVoiceEvent {
        client.sendWorkResult(result.callId, result.output)
        return DomainVoiceEvent.WorkResult(result.callId, result.output)
    }

    override fun interrupt(): DomainVoiceEvent = runBlocking { cancelAssistantResponse() }

    override fun sendToolResult(result: ToolResult): DomainVoiceEvent =
        runBlocking { injectWorkResult(WorkInjection(result.callId, result.ok, result.output)) }

    override fun close() = client.close()
}
