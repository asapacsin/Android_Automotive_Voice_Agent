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

class BaiduDirectRealtimeProvider(
    private val apiConfig: BaiduApiConfig,
    private val client: BaiduRealtimeClient = BaiduRealtimeClient(),
) : RealtimeVoiceProvider {
    override val providerId = "baidu.e2e.realtime.direct"
    override val capabilities: ProviderCapabilities = VoiceCatalog.capabilities(VoiceProviderId.BAIDU)
    override fun events(): Flow<RealtimeEvent> = client.events()
    override suspend fun connect(config: RealtimeSessionConfig) {
        VoiceCatalog.requireAllowed(VoiceProviderId.BAIDU, config.model)
        client.connect(apiConfig.copy(settings = apiConfig.settings.copy(model = config.model)))
    }
    override fun connect(model: String) = runBlocking { connect(RealtimeSessionConfig(VoiceProviderId.BAIDU, model)) }
    override suspend fun disconnect() = client.disconnect()
    override fun sendAudio(pcm16le: ByteArray) = client.sendAudio(pcm16le)
    override suspend fun cancelAssistantResponse() = DomainVoiceEvent.Interrupted("server_vad_only")
    override suspend fun injectWorkResult(result: WorkInjection) = DomainVoiceEvent.ToolUnsupported("BLOCKED_BAIDU_FUNCTION_CALLING")
    override fun interrupt(): DomainVoiceEvent = DomainVoiceEvent.Interrupted("server_vad_only")
    override fun sendToolResult(result: ToolResult): DomainVoiceEvent = DomainVoiceEvent.ToolUnsupported("BLOCKED_BAIDU_FUNCTION_CALLING")
    override fun close() = client.close()
}
