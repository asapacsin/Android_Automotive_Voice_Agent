package com.novadrive.ingress.realtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.ContinuationInterceptor

data class VoiceSessionCallbacks(
    val onUiState: (VoiceUiState, String?) -> Unit = { _, _ -> },
    val onTranscript: (String) -> Unit = {},
    val onError: (String, String) -> Unit = { _, _ -> },
    val onToolCall: ((DomainVoiceEvent.ToolCall) -> ToolDispatchResult)? = null,
)

/**
 * Provider-independent session core. Android types stay behind [MicrophonePort] / [PlaybackPort]
 * so this class is unit-tested on the JVM.
 */
class VoiceSessionController(
    private val provider: RealtimeVoiceProvider,
    private val microphone: MicrophonePort,
    private val playback: PlaybackPort,
    private val scope: CoroutineScope,
    private val config: RealtimeSessionConfig = RealtimeSessionConfig(),
    private val workCoordinator: WorkCoordinator = WorkCoordinator(),
    private val reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
    private val clock: SessionClock = SystemSessionClock,
    val diagnostics: LatencyDiagnostics = LatencyDiagnostics(clock),
    private val log: StructuredVoiceLog = StructuredVoiceLog(),
    private val callbacks: VoiceSessionCallbacks = VoiceSessionCallbacks(),
) {
    val machine = VoiceSessionStateMachine()
    private val sessionActive = AtomicBoolean(false)
    private val captureArmed = AtomicBoolean(false)
    private val collectorGeneration = AtomicInteger(0)
    private val mutex = Mutex()
    private var eventJob: Job? = null
    private var connectJob: Job? = null
    private var disconnectJob: Job? = null
    private var deliveryJob: Job? = null
    var collectorStarts: Int = 0
        private set

    val sessionActiveNow: Boolean get() = sessionActive.get()
    val logs: List<String> get() = log.lines
    val work: WorkCoordinator get() = workCoordinator

    init {
        val previous = workCoordinator.onTerminal
        workCoordinator.onTerminal = {
            previous()
            scheduleDelivery()
        }
    }

    fun start() {
        if (!sessionActive.compareAndSet(false, true)) return
        reconnectPolicy.reset()
        machine.userStartSession()
        publish()
        playback.start()
        diagnostics.markConnectStart()
        log.info("session_start", mapOf("provider" to config.provider.wireName, "model" to config.model))
        eventJob?.cancel()
        connectJob?.cancel()
        val generation = collectorGeneration.incrementAndGet()
        eventJob =
            scope.launch {
                collectorStarts += 1
                collectEvents(generation)
            }
        connectJob = scope.launch { connectAndListen() }
    }

    fun stop() {
        if (!sessionActive.compareAndSet(true, false)) return
        captureArmed.set(false)
        reconnectPolicy.cancel()
        eventJob?.cancel()
        eventJob = null
        connectJob?.cancel()
        connectJob = null
        deliveryJob?.cancel()
        deliveryJob = null
        workCoordinator.cancelAll()
        microphone.stop()
        playback.stop()
        disconnectJob = launchDetached { provider.disconnect() }
        machine.userStopSession()
        publish()
        log.info("session_stop")
    }

    fun release() {
        stop()
    }

    fun setMuted(muted: Boolean) {
        microphone.muted = muted
        log.info("mute", mapOf("muted" to muted))
    }

    fun pttDown() {
        if (config.interactionMode != InteractionMode.PTT) return
        microphone.muted = false
    }

    fun pttUp() {
        if (config.interactionMode != InteractionMode.PTT) return
        microphone.muted = true
        scope.launch { provider.commitInputAudio() }
    }

    fun submitWork(id: String, payload: String) {
        workCoordinator.submit(id, payload)
        log.info("work_submit", mapOf("id" to id))
    }

    fun submitWork(
        id: String,
        payload: String,
        block: suspend WorkHandle.() -> String,
    ) {
        workCoordinator.submit(scope, id, payload, block)
        log.info("work_submit", mapOf("id" to id, "async" to true))
    }

    fun refineWork(id: String, payload: String) {
        workCoordinator.refine(id, payload)
        log.info("work_refine", mapOf("id" to id))
    }

    fun cancelWork(id: String) {
        workCoordinator.cancel(id)
        log.info("work_cancel", mapOf("id" to id))
    }

    fun injectAudioFrame(frame: ByteArray) {
        if (sessionActive.get() && machine.streamingAudio && !microphone.muted) {
            provider.sendAudio(frame)
        }
    }

    private fun launchDetached(block: suspend CoroutineScope.() -> Unit): Job {
        val oneShot = SupervisorJob()
        val dispatcher = scope.coroutineContext[ContinuationInterceptor]
        val ctx = if (dispatcher != null) dispatcher + oneShot else oneShot
        return CoroutineScope(ctx).launch {
            try {
                block()
            } finally {
                oneShot.cancel()
            }
        }
    }

    private fun resumeCaptureOnce() {
        if (!sessionActive.get()) return
        if (config.interactionMode == InteractionMode.MUTED) return
        if (!captureArmed.compareAndSet(false, true)) return
        microphone.muted = config.interactionMode == InteractionMode.PTT
        microphone.start { frame -> injectAudioFrame(frame) }
    }

    private fun stopCapture() {
        captureArmed.set(false)
        microphone.stop()
    }

    private suspend fun connectAndListen() {
        try {
            provider.connect(config)
            diagnostics.markConnected()
            resumeCaptureOnce()
        } catch (ex: VoiceProviderException) {
            handleFailure(ex.code, ex.safeMessage)
        } catch (ex: Exception) {
            handleFailure("SERVER_DISCONNECT", ex.message ?: "connect failed")
        }
    }

    private suspend fun collectEvents(generation: Int) {
        try {
            provider.events().collect { event ->
                if (!sessionActive.get() || generation != collectorGeneration.get() || !scope.isActive) {
                    return@collect
                }
                handleEvent(event.payload)
            }
        } catch (_: Exception) {
            if (sessionActive.get()) {
                handleFailure("SERVER_DISCONNECT", "event collector stopped")
            }
        }
    }

    internal suspend fun handleEvent(event: DomainVoiceEvent) {
        var failure: Pair<String, String>? = null
        var resumeCapture = false
        mutex.withLock {
            when (event) {
                is DomainVoiceEvent.SessionReady -> {
                    if (machine.state == VoiceUiState.RECONNECTING) {
                        diagnostics.markReconnected()
                    }
                    if (sessionActive.get() && config.interactionMode != InteractionMode.MUTED) {
                        microphone.muted = config.interactionMode == InteractionMode.PTT
                    }
                    resumeCapture = true
                }
                is DomainVoiceEvent.AudioDelta -> {
                    diagnostics.markFirstAudio()
                    val pcm = decodePcm(event.pcm16leBase64)
                    playback.enqueue(pcm)
                }
                DomainVoiceEvent.SpeechStopped -> diagnostics.markSpeechEnd()
                is DomainVoiceEvent.Interrupted -> {
                    diagnostics.markInterruptDetected()
                    playback.flush()
                    diagnostics.markPlaybackStopped()
                }
                DomainVoiceEvent.SpeechStarted -> {
                    if (machine.state == VoiceUiState.SPEAKING || machine.state == VoiceUiState.THINKING) {
                        bargeIn()
                    }
                }
                is DomainVoiceEvent.Error -> {
                    callbacks.onError(event.code, event.message)
                    failure = event.code to event.message
                    return@withLock
                }
                is DomainVoiceEvent.UserTranscript -> {
                    if (event.final && event.text.isNotBlank()) callbacks.onTranscript("你: ${event.text}")
                }
                is DomainVoiceEvent.AssistantTranscript -> {
                    if (event.final && event.text.isNotBlank()) callbacks.onTranscript("小诺: ${event.text}")
                }
                is DomainVoiceEvent.ToolCall -> dispatchTool(event)
                is DomainVoiceEvent.WorkResult -> {
                    workCoordinator.complete(event.workId, event.output)
                }
                is DomainVoiceEvent.WorkFailed -> {
                    workCoordinator.fail(event.workId, event.message)
                }
                is DomainVoiceEvent.WorkProgress -> {
                    workCoordinator.updateProgress(event.workId, event.message)
                }
                else -> Unit
            }
            machine.apply(event)
            publish()
        }
        if (resumeCapture) {
            resumeCaptureOnce()
        }
        if (failure != null) {
            handleFailure(failure.first, failure.second)
            return
        }
        scheduleDelivery()
    }

    private suspend fun bargeIn() {
        diagnostics.markInterruptDetected()
        playback.flush()
        diagnostics.markPlaybackStopped()
        if (VoiceCatalog.capabilities(config.provider).clientResponseCancel) {
            provider.cancelAssistantResponse()
        }
        log.info("barge_in", mapOf("work_cancelled" to false))
    }

    private fun dispatchTool(call: DomainVoiceEvent.ToolCall) {
        if (workCoordinator.snapshot(call.callId) != null) return
        workCoordinator.submit(call.callId, call.name)
        val dispatcher = callbacks.onToolCall ?: return
        val result = dispatcher(call)
        val output = result.output ?: result.orchestration?.feedbackZhCn ?: result.blockedReason ?: ""
        workCoordinator.complete(call.callId, output)
    }

    private fun scheduleDelivery() {
        val active = deliveryJob
        if (active?.isActive == true) return
        deliveryJob = scope.launch { deliverPendingWork() }
    }

    private suspend fun deliverPendingWork() {
        while (sessionActive.get()) {
            val ready =
                mutex.withLock {
                    workCoordinator.claimForDelivery(machine.isSafeWorkDeliveryPoint())
                } ?: return
            try {
                provider.injectWorkResult(
                    WorkInjection(
                        ready.id,
                        ready.status == WorkStatus.COMPLETED,
                        ready.result ?: ready.error.orEmpty(),
                    ),
                )
                mutex.withLock { workCoordinator.acknowledgeDelivery(ready.id) }
                log.info("work_delivered", mapOf("id" to ready.id, "once" to true))
            } catch (ex: Exception) {
                mutex.withLock { workCoordinator.releaseDeliveryClaim(ready.id) }
                log.warn("work_delivery_failed", mapOf("id" to ready.id, "error" to (ex.message ?: "inject")))
                return
            }
        }
    }

    private suspend fun handleFailure(code: String, message: String) {
        val delayMs = mutex.withLock { beginReconnectOrTerminal(code, message) } ?: return
        delay(delayMs)
        if (!sessionActive.get()) return
        try {
            provider.connect(config)
            mutex.withLock { diagnostics.markReconnected() }
            resumeCaptureOnce()
        } catch (ex: VoiceProviderException) {
            handleFailure(ex.code, ex.safeMessage)
        } catch (ex: Exception) {
            handleFailure("SERVER_DISCONNECT", ex.message ?: message)
        }
    }

    private fun beginReconnectOrTerminal(code: String, message: String): Long? {
        val errorClass = reconnectPolicy.classify(code)
        log.warn("session_error", mapOf("code" to code, "class" to errorClass.name))
        if (reconnectPolicy.shouldRetry(code) && sessionActive.get()) {
            val delayMs = reconnectPolicy.nextDelayMs(code) ?: run {
                failTerminal(code, message)
                return null
            }
            machine.onReconnecting()
            publish()
            diagnostics.markReconnectStart()
            stopCapture()
            playback.flush()
            emitReconnect()
            return delayMs
        }
        failTerminal(code, message)
        return null
    }

    private fun failTerminal(code: String, message: String) {
        machine.onError(code)
        stopCapture()
        playback.stop()
        callbacks.onError(code, message)
        publish()
    }

    private fun emitReconnect() {
        machine.apply(DomainVoiceEvent.Reconnecting)
    }

    private fun publish() {
        callbacks.onUiState(machine.state, machine.lastErrorCode)
    }

    companion object {
        fun decodePcm(base64: String): ByteArray =
            if (base64.isBlank()) ByteArray(0) else Base64.getDecoder().decode(base64)
    }
}
