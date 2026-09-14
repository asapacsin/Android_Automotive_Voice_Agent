package com.novadrive.app.voice

import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.ProviderCapabilities
import com.novadrive.ingress.realtime.RealtimeEvent
import com.novadrive.ingress.realtime.RealtimeSessionConfig
import com.novadrive.ingress.realtime.RealtimeVoiceProvider
import com.novadrive.ingress.realtime.SystemSessionClock
import com.novadrive.ingress.realtime.ToolResult
import com.novadrive.ingress.realtime.VoiceCatalog
import com.novadrive.ingress.realtime.WorkInjection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking

/**
 * Android transport to the provider-neutral backend WebSocket. Vendor JSON stays on the server.
 */
class BackendRealtimeProvider(
    private val client: BackendVoiceClient,
) : RealtimeVoiceProvider {
    override val providerId: String = "backend.proxy"
    override val capabilities: ProviderCapabilities
        get() = VoiceCatalog.capabilities(lastConfig?.provider ?: VoiceCatalog.DEFAULT_PROVIDER)

    private val flow = MutableSharedFlow<RealtimeEvent>(replay = 16, extraBufferCapacity = 64)
    private var lastConfig: RealtimeSessionConfig? = null

    init {
        client.replaceListener { event ->
            flow.tryEmit(RealtimeEvent(SystemSessionClock.nowMs(), event))
        }
    }

    override fun events(): Flow<RealtimeEvent> = flow.asSharedFlow()

    override fun connect(model: String) {
        runBlocking {
            val providerId =
                runCatching { VoiceCatalog.providerForModel(model) }.getOrDefault(VoiceCatalog.DEFAULT_PROVIDER)
            connect(RealtimeSessionConfig(provider = providerId, model = model))
        }
    }

    override suspend fun connect(config: RealtimeSessionConfig) {
        lastConfig = config
        val url = lastBackendUrl ?: return
        client.connect(url, config.model, config.provider.wireName)
    }

    override suspend fun disconnect() {
        client.stopSession()
    }

    override fun sendAudio(pcm16le: ByteArray) {
        client.sendAudio(pcm16le)
    }

    override suspend fun commitInputAudio() {
        client.commit()
    }

    override suspend fun cancelAssistantResponse(): DomainVoiceEvent {
        if (!capabilities.clientResponseCancel) {
            return DomainVoiceEvent.SpeechStarted
        }
        client.interrupt()
        return DomainVoiceEvent.Interrupted("client_cancelled")
    }

    override fun interrupt(): DomainVoiceEvent = runBlocking { cancelAssistantResponse() }

    override suspend fun injectWorkResult(result: WorkInjection): DomainVoiceEvent {
        client.sendWorkResult(result.callId, result.ok, result.output)
        return DomainVoiceEvent.WorkResult(result.callId, result.output)
    }

    override fun sendToolResult(result: ToolResult): DomainVoiceEvent {
        return runBlocking { injectWorkResult(WorkInjection(result.callId, result.ok, result.output)) }
    }

    override fun close() {
        client.close()
    }

    fun attachBackendUrl(url: String) {
        lastBackendUrl = url
    }

    companion object {
        @Volatile
        var lastBackendUrl: String? = null
    }
}
