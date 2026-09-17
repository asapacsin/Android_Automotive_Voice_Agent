package com.novadrive.ingress.realtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger

/**
 * Deterministic quota-free provider for JVM/Android integration and benchmarks.
 */
class FakeRealtimeVoiceProvider(
    private val clock: SessionClock = SystemSessionClock,
) : RealtimeVoiceProvider {
    override val providerId: String = "fake.realtime"
    override val capabilities: ProviderCapabilities = VoiceCatalog.capabilities(VoiceProviderId.FAKE)

    val sentChunks = mutableListOf<ByteArray>()
    var connectCount: Int = 0
        private set
    var disconnectCount: Int = 0
        private set
    var commitCount: Int = 0
        private set
    var cancelCount: Int = 0
        private set
    var closed: Boolean = false
        private set
    var lastConfig: RealtimeSessionConfig? = null
        private set
    val workInjections = mutableListOf<WorkInjection>()
    val collectorStarts = AtomicInteger(0)
    var failNextConnectWith: String? = null
    var failNextInjectWith: String? = null
    var injectDelayMs: Long = 0
    var disconnectDelayMs: Long = 0
    var injectGate: CompletableDeferred<Unit>? = null

    private val flow = MutableSharedFlow<RealtimeEvent>(replay = 32, extraBufferCapacity = 64)
    private val inbox = mutableListOf<DomainVoiceEvent>()

    override fun events(): Flow<RealtimeEvent> =
        flow.onSubscription { collectorStarts.incrementAndGet() }

    override fun connect(model: String) {
        runBlocking { connect(RealtimeSessionConfig(provider = VoiceProviderId.FAKE, model = model)) }
    }

    override suspend fun connect(config: RealtimeSessionConfig) {
        VoiceCatalog.requireAllowed(config.model)
        val fail = failNextConnectWith
        if (fail != null) {
            failNextConnectWith = null
            emit(DomainVoiceEvent.Error(fail, "injected failure"))
            throw IllegalStateException(fail)
        }
        lastConfig = config
        connectCount += 1
        closed = false
        emit(DomainVoiceEvent.SessionReady(config.model, interruptResponse = true))
    }

    override suspend fun disconnect() {
        if (disconnectDelayMs > 0) delay(disconnectDelayMs)
        disconnectCount += 1
        close()
    }

    override fun sendAudio(pcm16le: ByteArray) {
        sentChunks += pcm16le.copyOf()
    }

    override suspend fun commitInputAudio() {
        commitCount += 1
    }

    override suspend fun cancelAssistantResponse(): DomainVoiceEvent {
        cancelCount += 1
        val event = DomainVoiceEvent.Interrupted("client_cancelled")
        emit(event)
        emit(DomainVoiceEvent.ResponseDone("cancelled", "client_cancelled"))
        return event
    }

    override fun interrupt(): DomainVoiceEvent = runBlocking { cancelAssistantResponse() }

    var discardAudioCount: Int = 0
        private set
    var resumeCount: Int = 0
        private set

    override fun discardPendingAudio() {
        discardAudioCount += 1
    }

    override fun resumeListening() {
        resumeCount += 1
    }

    override suspend fun sendText(text: String) {
        emit(DomainVoiceEvent.UserTranscript(text, final = true))
    }

    override suspend fun injectWorkResult(result: WorkInjection): DomainVoiceEvent {
        injectGate?.await()
        if (injectDelayMs > 0) delay(injectDelayMs)
        failNextInjectWith?.let { code ->
            failNextInjectWith = null
            throw IllegalStateException(code)
        }
        workInjections += result
        val event = DomainVoiceEvent.WorkResult(result.callId, result.output)
        emit(event)
        return event
    }

    override fun sendToolResult(result: ToolResult): DomainVoiceEvent {
        return runBlocking {
            injectWorkResult(WorkInjection(result.callId, result.ok, result.output))
        }
    }

    override fun receiveEvents(): List<DomainVoiceEvent> {
        val copy = inbox.toList()
        inbox.clear()
        return copy
    }

    override fun close() {
        closed = true
        emit(DomainVoiceEvent.Closed)
    }

    fun emit(event: DomainVoiceEvent) {
        inbox += event
        val payload = RealtimeEvent(clock.nowMs(), event)
        if (!flow.tryEmit(payload)) {
            runBlocking { flow.emit(payload) }
        }
    }

    fun playMandarin() {
        emit(DomainVoiceEvent.SpeechStarted)
        emit(DomainVoiceEvent.SpeechStopped)
        emit(DomainVoiceEvent.UserTranscript("打开空调到二十二度。", final = true))
        emit(DomainVoiceEvent.AudioDelta("AAAA"))
        emit(DomainVoiceEvent.AssistantTranscript("好的，正在设置温度。", final = true))
        emit(DomainVoiceEvent.AudioDone)
        emit(DomainVoiceEvent.ResponseDone("completed"))
    }

    fun playCodeSwitch() {
        emit(DomainVoiceEvent.SpeechStarted)
        emit(DomainVoiceEvent.SpeechStopped)
        emit(DomainVoiceEvent.UserTranscript("Navigate to 人民广场 then play some music.", final = true))
        emit(DomainVoiceEvent.AudioDelta("AQID"))
        emit(DomainVoiceEvent.AssistantTranscript("收到，正在规划去人民广场。", final = true))
        emit(DomainVoiceEvent.AudioDone)
        emit(DomainVoiceEvent.ResponseDone("completed"))
    }

    fun playRapidInterrupt() {
        emit(DomainVoiceEvent.AudioDelta("AAAA"))
        emit(DomainVoiceEvent.SpeechStarted)
        emit(DomainVoiceEvent.Interrupted("turn_detected"))
        emit(DomainVoiceEvent.ResponseDone("cancelled", "turn_detected"))
        emit(DomainVoiceEvent.SpeechStopped)
        emit(DomainVoiceEvent.AudioDelta("AQID"))
        emit(DomainVoiceEvent.ResponseDone("completed"))
    }

    fun playWorkRefine(workId: String = "work-1") {
        emit(DomainVoiceEvent.ToolCall(workId, "set_temperature", mapOf("zone" to "driver", "temperature_c" to "22")))
        emit(DomainVoiceEvent.WorkProgress(workId, "refining"))
    }

    fun playReconnect() {
        emit(DomainVoiceEvent.Error("SERVER_DISCONNECT", "socket closed"))
        emit(DomainVoiceEvent.Reconnecting)
        emit(DomainVoiceEvent.SessionReady(VoiceCatalog.FAKE_MODEL, interruptResponse = true))
    }
}
