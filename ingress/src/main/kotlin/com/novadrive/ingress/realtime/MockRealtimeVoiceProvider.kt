package com.novadrive.ingress.realtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking

class MockRealtimeVoiceProvider(
    private val autoReply: Boolean = true,
    private val emitToolCall: Boolean = false,
) : RealtimeVoiceProvider {
    override val providerId: String = "mock.realtime"
    override val capabilities: ProviderCapabilities = VoiceCatalog.capabilities(VoiceProviderId.FAKE)

    var sentAudioBytes: Int = 0
        private set
    var closed: Boolean = false
        private set

    private val inbox = mutableListOf<DomainVoiceEvent>()
    private val flow = MutableSharedFlow<RealtimeEvent>(replay = 16, extraBufferCapacity = 32)
    private var replied = false
    private val clock: SessionClock = SystemSessionClock

    override fun connect(model: String) {
        VoiceCatalog.requireAllowed(model)
        val ready = DomainVoiceEvent.SessionReady(model, interruptResponse = true)
        inbox += ready
        emitFlow(ready)
    }

    override suspend fun connect(config: RealtimeSessionConfig) {
        connect(config.model)
    }

    override suspend fun disconnect() {
        close()
    }

    override fun sendAudio(pcm16le: ByteArray) {
        sentAudioBytes += pcm16le.size
        if (autoReply && !replied && sentAudioBytes >= 1600) {
            replied = true
            val reply =
                listOf(
                    DomainVoiceEvent.SpeechStarted,
                    DomainVoiceEvent.SpeechStopped,
                    DomainVoiceEvent.UserTranscript("你好，我正在测试车载语音助手，请简短回复我。", final = true),
                    DomainVoiceEvent.AudioDelta("AAAA"),
                    DomainVoiceEvent.AssistantTranscript("收到，测试成功。", final = true),
                    DomainVoiceEvent.AudioDone,
                    DomainVoiceEvent.ResponseDone("completed"),
                )
            reply.forEach {
                inbox += it
                emitFlow(it)
            }
            if (emitToolCall) {
                val call =
                    DomainVoiceEvent.ToolCall(
                        callId = "call-mock-temp",
                        name = "set_temperature",
                        arguments = mapOf("zone" to "driver", "temperature_c" to "22"),
                    )
                inbox += call
                emitFlow(call)
            }
        }
    }

    override fun receiveEvents(): List<DomainVoiceEvent> {
        val copy = inbox.toList()
        inbox.clear()
        return copy
    }

    override fun interrupt(): DomainVoiceEvent {
        val event = DomainVoiceEvent.Interrupted("turn_detected")
        inbox += event
        inbox += DomainVoiceEvent.ResponseDone("cancelled", reason = "turn_detected")
        emitFlow(event)
        emitFlow(DomainVoiceEvent.ResponseDone("cancelled", reason = "turn_detected"))
        return event
    }

    override suspend fun cancelAssistantResponse(): DomainVoiceEvent = interrupt()

    override fun sendToolResult(result: ToolResult): DomainVoiceEvent {
        val event = DomainVoiceEvent.AssistantTranscript(result.output, final = true)
        inbox += event
        emitFlow(event)
        return event
    }

    override suspend fun injectWorkResult(result: WorkInjection): DomainVoiceEvent {
        return sendToolResult(ToolResult(result.callId, result.ok, result.output))
    }

    override fun events(): Flow<RealtimeEvent> = flow.asSharedFlow()

    override fun close() {
        closed = true
        inbox += DomainVoiceEvent.Closed
        emitFlow(DomainVoiceEvent.Closed)
    }

    private fun emitFlow(event: DomainVoiceEvent) {
        val payload = RealtimeEvent(clock.nowMs(), event)
        if (!flow.tryEmit(payload)) {
            runBlocking { flow.emit(payload) }
        }
    }
}
