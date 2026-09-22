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
    /** The driver's finished utterance, before the model's reply to it. */
    val onUserFinalTranscript: (String) -> Unit = {},
    /** Structured session diagnostics (counts/flags only — never audio or transcript). */
    val onSessionLog: (String) -> Unit = {},
    /** Extra fields merged into playout_barge_in (uplink gate, RMS — never audio or transcript). */
    val bargeInDiagnostics: () -> Map<String, Any> = { emptyMap() },
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
    private val providerConnected = AtomicBoolean(false)
    private val pendingTexts = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val captureArmed = AtomicBoolean(false)

    /**
     * Listening lifecycle switch (sleep): no capture, no upload, and nothing re-arms capture —
     * not a reconnect, not a SessionReady. Separate from the temporary playback/guidance gates.
     */
    private val captureSuspended = AtomicBoolean(false)
    private val collectorGeneration = AtomicInteger(0)
    /** Remote model still generating the current reply (until [DomainVoiceEvent.ResponseDone]). */
    @Volatile private var generationActive = false
    /** Bumped on interrupt; each [PlaybackPort.enqueue] chunk is stamped with this epoch. */
    private var playbackEpoch = 0
    private var replyOpen = false
    private var replyEpoch = 0
    private var acceptingReplyAudio = true
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
        providerConnected.set(false)
        pendingTexts.clear()
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

    /**
     * Stops (true) or resumes (false) microphone capture and upload for the listening lifecycle.
     * Stopping takes effect before this returns: capture is released and queued audio dropped.
     */
    fun setCaptureSuspended(suspended: Boolean) {
        if (suspended) {
            if (!captureSuspended.compareAndSet(false, true)) return
            captureArmed.set(false)
            microphone.stop()
            provider.discardPendingAudio()
            log.info("capture_suspended")
        } else {
            if (!captureSuspended.compareAndSet(true, false)) return
            log.info("capture_resumed")
            provider.resumeListening()
            if (providerConnected.get()) resumeCaptureOnce()
        }
    }

    val captureSuspendedNow: Boolean get() = captureSuspended.get()

    /** Whether the provider connection is up (independent of capture). */
    val connectedNow: Boolean get() = providerConnected.get()

    /** Tool work that has not yet been answered (running, or finished but not delivered). */
    fun hasPendingWork(): Boolean = workCoordinator.all().any { w ->
        when (w.status) {
            WorkStatus.QUEUED, WorkStatus.RUNNING, WorkStatus.PROGRESS -> true
            WorkStatus.COMPLETED, WorkStatus.FAILED -> !w.delivered
            WorkStatus.CANCELLED -> false
        }
    }

    /** Stops the assistant's current reply: local playback now, and the reply on the server. */
    fun cancelCurrentResponse() {
        if (!sessionActive.get()) return
        invalidatePlaybackEpoch()
        scope.launch {
            mutex.withLock {
                machine.onInterrupted()
                publish()
            }
            try {
                provider.cancelActiveResponse()
            } catch (ex: Exception) {
                log.warn("cancel_failed", mapOf("error" to (ex.message ?: "cancel")))
            }
        }
    }

    fun injectAudioFrame(frame: ByteArray) {
        if (captureSuspended.get()) return
        if (sessionActive.get() && machine.streamingAudio && !microphone.muted) {
            provider.sendAudio(frame)
        }
    }

    /**
     * Sends a text turn to the model (for example a finished camera answer to be read aloud).
     * Queued until the provider is connected, so a caller may start the session and send at once.
     * Ignored when no session is active.
     */
    fun sendText(text: String) {
        if (!sessionActive.get() || text.isBlank()) return
        pendingTexts += text
        if (providerConnected.get()) flushTexts()
        log.info("text_queued", mapOf("chars" to text.length))
    }

    private fun markConnectedAndFlushTexts() {
        providerConnected.set(true)
        flushTexts()
    }

    private fun flushTexts() {
        while (true) {
            val text = pendingTexts.poll() ?: return
            scope.launch {
                try {
                    provider.sendText(text)
                } catch (ex: Exception) {
                    log.warn("text_send_failed", mapOf("error" to (ex.message ?: "send")))
                }
            }
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
        if (captureSuspended.get()) return
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
            markConnectedAndFlushTexts()
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
                DomainVoiceEvent.ResponseStarted -> openReplyStamp()
                is DomainVoiceEvent.AudioDelta -> {
                    if (!ensureReplyStamp()) return@withLock
                    diagnostics.markFirstAudio()
                    generationActive = true
                    val pcm = decodePcm(event.pcm16leBase64)
                    playback.enqueue(pcm, replyEpoch)
                }
                DomainVoiceEvent.SpeechStopped -> diagnostics.markSpeechEnd()
                is DomainVoiceEvent.Interrupted -> {
                    diagnostics.markInterruptDetected()
                    invalidatePlaybackEpoch()
                    diagnostics.markPlaybackStopped()
                }
                DomainVoiceEvent.SpeechStarted -> {
                    if (playback.playbackActive) {
                        bargeIn()
                    }
                }
                is DomainVoiceEvent.Error -> {
                    callbacks.onError(event.code, event.message)
                    failure = event.code to event.message
                    return@withLock
                }
                is DomainVoiceEvent.UserTranscript -> {
                    if (event.final && event.text.isNotBlank()) {
                        callbacks.onTranscript("你: ${event.text}")
                        callbacks.onUserFinalTranscript(event.text)
                    }
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
            if (event is DomainVoiceEvent.ResponseDone) {
                generationActive = false
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
        val queuedBytes = playback.queuedFrames * 2
        val cancelGeneration = generationActive
        val fields =
            buildMap {
                put("epoch", playbackEpoch)
                put("generationActive", generationActive)
                put("playbackActive", playback.playbackActive)
                put("queuedBytes", queuedBytes)
                put("flush", true)
                put("cancel", cancelGeneration)
                putAll(callbacks.bargeInDiagnostics())
            }
        val line = log.info("playout_barge_in", fields)
        callbacks.onSessionLog(line)
        invalidatePlaybackEpoch()
        diagnostics.markPlaybackStopped()
        if (cancelGeneration && VoiceCatalog.capabilities(config.provider).clientResponseCancel) {
            provider.cancelAssistantResponse()
        }
    }

    private fun invalidatePlaybackEpoch() {
        playback.flush()
        playbackEpoch += 1
        replyOpen = false
        acceptingReplyAudio = false
    }

    private fun openReplyStamp() {
        replyOpen = true
        replyEpoch = playbackEpoch
        acceptingReplyAudio = true
        generationActive = true
    }

    private fun ensureReplyStamp(): Boolean {
        if (!acceptingReplyAudio) return replyOpen
        if (!replyOpen) {
            replyOpen = true
            replyEpoch = playbackEpoch
        }
        return true
    }

    private fun dispatchTool(call: DomainVoiceEvent.ToolCall) {
        if (workCoordinator.snapshot(call.callId) != null) return
        workCoordinator.submit(call.callId, call.name)
        val dispatcher = callbacks.onToolCall ?: return
        val result = dispatcher(call)
        val deferred = result.deferredOutput
        if (deferred != null) {
            // Never block the event loop on a slow tool: audio and turn-taking keep running,
            // and the result is delivered once, at a safe point, via onTerminal.
            workCoordinator.submit(scope, call.callId, call.name) { deferred() }
            log.info("tool_deferred", mapOf("id" to call.callId, "name" to call.name))
            return
        }
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
            markConnectedAndFlushTexts()
            resumeCaptureOnce()
        } catch (ex: VoiceProviderException) {
            handleFailure(ex.code, ex.safeMessage)
        } catch (ex: Exception) {
            handleFailure("SERVER_DISCONNECT", ex.message ?: message)
        }
    }

    private fun beginReconnectOrTerminal(code: String, message: String): Long? {
        providerConnected.set(false)
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
            invalidatePlaybackEpoch()
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
