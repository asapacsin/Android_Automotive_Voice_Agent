package com.novadrive.app.voice

import com.novadrive.app.DebugVoiceLog
import com.novadrive.app.GeminiApiConfig
import com.novadrive.app.GeminiSettingsValidator
import com.novadrive.app.PersonaProfiles
import com.novadrive.evaluation.EventType
import com.novadrive.evaluation.Telemetry
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.RealtimeEvent
import com.novadrive.ingress.realtime.ResponseOutcome
import com.novadrive.ingress.realtime.SystemSessionClock
import com.novadrive.ingress.realtime.VoiceCatalog
import com.novadrive.ingress.realtime.VoiceProviderException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Gemini Live socket and turn state machine (ADR-010). Feeds the shared [DriverTurnPipeline]; it
 * never re-implements the claim gate. Gemini has no speech-started/stopped events: the session core
 * reports local onset through [onLocalSpeechActivity] and itself emits SpeechStarted/SpeechStopped.
 *
 * Logs event kinds, ids, sizes, counts and close codes only — never the key, a transcript, tool
 * arguments, the resumption handle or the instructions (I-8).
 */
class GeminiLiveClient(
    private val http: OkHttpClient = defaultHttp(),
    private val readyTimeoutMs: Long = 10_000,
    private val requireTls: Boolean = true,
    private val contextHint: () -> String? = { VoiceContextHints.current() },
    private val lastAudioSegment: () -> SpeechUplinkGate.Segment? = { null },
    private val contextAwaitingAnswer: () -> Boolean = { VoiceContextHints.awaitingAnswer() },
    private val speechEvidence: () -> Boolean = { true },
    /**
     * How long a claim correction waits while a tool call may still come. Measured
     * (docs/reports/2026-09-29-gemini-live-probe.md F20): with CALL_FIRST_HINT, spoken commands in
     * the app client got the call 5–9 s after the end of speech and text runs up to ~27 s; without
     * the hint the first probe saw up to 31 s. An immediate correction races that call and can
     * double-actuate.
     */
    private val correctionGraceMs: Long = 20_000,
) {
    /**
     * A held reply is released all at once at turn end: up to the pipeline's 120-event hold budget
     * plus that turn's tail (AudioDone, subtitle, ResponseDone). 1024 absorbs such a burst for a
     * slow collector; a drop is still counted, never silent.
     */
    private val eventFlow = MutableSharedFlow<RealtimeEvent>(replay = 0, extraBufferCapacity = 1024)
    private val droppedEvents = AtomicLong(0)
    private val generation = AtomicLong(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var socket: WebSocket? = null
    @Volatile private var ready: CompletableDeferred<Unit>? = null
    @Volatile private var model: String = ""

    /** Held in memory only; never logged. Sent in the next [connect]'s setup. */
    @Volatile private var resumptionHandle: String? = null

    @Volatile private var playbackActive = false
    @Volatile private var listeningSuspended = false
    @Volatile private var clientCancelled = false
    @Volatile private var audioErrorReported = false

    // Turn state; mutated on the socket's reader thread and under `this` lock.
    private var turnOpen = false
    private var turnAudioSeen = false
    private var generationDone = false
    private val turnCallIds = mutableListOf<String>()
    private val turnText = StringBuilder()
    private val inputText = StringBuilder()
    @Volatile private var onsetSeen = false
    /** outputTranscription that arrived with no turn open; applied when audio or a call opens one. */
    private val strayOutput = StringBuilder()
    /** A tool call was dispatched since the current driver turn began (R7: no correction race). */
    @Volatile private var callDispatchedThisDriverTurn = false
    /** The setup of the current connection carried a resumption handle. */
    @Volatile private var setupResumed = false
    private val callNames = java.util.concurrent.ConcurrentHashMap<String, String>()

    @Volatile private var pendingCorrection: Job? = null

    private val pipeline = DriverTurnPipeline(
        lastAudioSegment = lastAudioSegment,
        contextAwaitingAnswer = contextAwaitingAnswer,
        speechEvidence = speechEvidence,
        host = object : DriverTurnPipeline.Host {
            override fun emit(event: DomainVoiceEvent) = this@GeminiLiveClient.emit(event)
            override fun sendCorrection(text: String) = deferCorrection(text)
            override val responseCancelledByClient: Boolean get() = clientCancelled
            override val listeningSuspended: Boolean get() = this@GeminiLiveClient.listeningSuspended
        },
    )

    fun events(): Flow<RealtimeEvent> = eventFlow.asSharedFlow()

    suspend fun connect(config: GeminiApiConfig) {
        closeSocket()
        val settings = config.settings
        val validation = if (requireTls) settings else settings.copy(endpoint = settings.endpoint.replaceFirst("ws://", "wss://"))
        GeminiSettingsValidator.validate(validation, config.apiKey)?.let { throw VoiceProviderException(it, it) }
        if (requireTls && !settings.endpoint.startsWith("wss://", ignoreCase = true)) {
            throw VoiceProviderException("GEMINI_ENDPOINT_INVALID", "GEMINI_ENDPOINT_INVALID")
        }
        pipeline.resetGuard()
        synchronized(this) { resetTurn(); onsetSeen = false }
        callDispatchedThisDriverTurn = false
        clientCancelled = false
        audioErrorReported = false
        playbackActive = false
        callNames.clear()
        model = settings.model
        val current = generation.incrementAndGet()
        val pending = CompletableDeferred<Unit>()
        ready = pending
        val setup = GeminiLiveProtocol.setup(
            model = settings.model,
            voice = settings.voice,
            thinkingLevel = settings.thinkingLevel.wireName
                .takeIf { VoiceCatalog.geminiAcceptsThinkingLevel(settings.model) },
            instructions = instructionsWithContext(config.instructions),
            silenceDurationMs = settings.silenceDurationMs,
            resumptionHandle = resumptionHandle,
        )
        setupResumed = !resumptionHandle.isNullOrEmpty()
        DebugVoiceLog.log("gemini_connect resume=$setupResumed")
        // The key goes in this header and nowhere else: not the URL, not a log, not an exception.
        val request = Request.Builder().url(settings.endpoint.trim())
            .header(GeminiLiveProtocol.API_KEY_HEADER, config.apiKey).build()
        socket = http.newWebSocket(request, listener(current, pending, setup))
        try {
            withTimeout(readyTimeoutMs) { pending.await() }
        } catch (_: TimeoutCancellationException) {
            closeSocket()
            throw VoiceProviderException(GeminiLiveProtocol.TIMEOUT, "Gemini Live session readiness timed out")
        } catch (failure: VoiceProviderException) {
            closeSocket()
            throw failure
        }
    }

    private fun instructionsWithContext(raw: String): String {
        val base = PersonaProfiles.sanitize(raw)
        val hint = contextHint()
        if (hint != null) DebugVoiceLog.log("gemini_context_hint chars=${hint.length}")
        return if (hint == null) base else base.trim() + "\n" + hint
    }

    fun sendAudio(pcm16le: ByteArray) {
        if (trySend(GeminiLiveProtocol.audio(Base64.getEncoder().encodeToString(pcm16le)))) return
        if (!audioErrorReported) {
            audioErrorReported = true
            emit(DomainVoiceEvent.Error(GeminiLiveProtocol.CONNECTION_CLOSED, "Gemini Live WebSocket is not connected"))
        }
    }

    fun sendUserText(text: String) {
        DebugVoiceLog.log("gemini_user_text chars=${text.length}")
        listeningSuspended = false
        sendControl(GeminiLiveProtocol.textTurn(text))
    }

    fun sendToolResult(callId: String, output: String) {
        val name = callNames.remove(callId).orEmpty()
        sendControl(GeminiLiveProtocol.toolResponse(callId, name, output))
        DebugVoiceLog.log("gemini_tool_response id=$callId chars=${output.length}")
        // The one place execution evidence enters this client (INVARIANT I-1).
        pipeline.onToolResult(callId, output)
    }

    /**
     * Gemini has no client-side response cancel. The app already handled this utterance locally,
     * so the current turn's held output is dropped and it draws no correction (Baidu P40 semantics).
     */
    fun markClientCancelled() {
        clientCancelled = true
        DebugVoiceLog.log("gemini_client_cancel local_only=true")
    }

    fun onLocalSpeechActivity(active: Boolean) {
        if (!active) return
        cancelCorrection("driver_turn")
        synchronized(this) { beginDriverTurnLocked() }
    }

    /**
     * A new driver utterance. Caller holds `this`; lock order is client -> pipeline, and no Host
     * callback takes this lock. An open turn that has produced neither audio nor a call would
     * otherwise absorb the next reply without an onResponseCreated for the new turn (I-1).
     */
    private fun beginDriverTurnLocked() {
        onsetSeen = true
        callDispatchedThisDriverTurn = false
        strayOutput.setLength(0)
        if (turnOpen && !turnAudioSeen && turnCallIds.isEmpty()) {
            DebugVoiceLog.log("gemini_empty_turn_closed")
            clearTurnState()
            emit(DomainVoiceEvent.ResponseDone("cancelled", "superseded_empty"))
        }
        val speaking = turnOpen && turnAudioSeen && !generationDone
        pipeline.beginDriverTurn(playbackOrSpeaking = playbackActive || speaking, responseInProgress = turnOpen)
    }

    fun onPlaybackActiveChanged(active: Boolean) {
        playbackActive = active
    }

    /** Listening stopped: audio is not queued here, but the client sends no turns of its own. */
    fun discardPendingAudio() {
        listeningSuspended = true
    }

    fun resumeListening() {
        listeningSuspended = false
    }

    fun disconnect() {
        // A new session must not resume an old conversation. The core's reconnect calls connect()
        // again without disconnect(), so it still resumes.
        resumptionHandle = null
        closeSocket()
        pipeline.onSessionEnded()
    }

    fun close() = disconnect()

    private fun closeSocket() {
        generation.incrementAndGet()
        cancelCorrection("disconnect")
        ready?.cancel(); ready = null
        if (socket != null) Telemetry.record(EventType.SOCKET_DISCONNECTED, detail = "client_close")
        synchronized(this) { resetTurn() }
        val existing = socket; socket = null
        existing?.close(1000, "client close")
    }

    private fun resetTurn() {
        clearTurnState()
        inputText.setLength(0)
        strayOutput.setLength(0)
    }

    private fun clearTurnState() {
        turnOpen = false
        turnAudioSeen = false
        generationDone = false
        turnCallIds.clear()
        turnText.setLength(0)
    }

    // ---- deferred correction ----------------------------------------------------------------

    /**
     * Host callback (pipeline monitor held): never takes the client lock. Deferred only while a
     * model call may still come; once a call was dispatched in this driver turn the correction
     * cannot race one and goes out now.
     */
    private fun deferCorrection(text: String) {
        val current = generation.get()
        pendingCorrection?.cancel()
        if (callDispatchedThisDriverTurn) {
            pendingCorrection = null
            sendCorrectionNow(text)
            return
        }
        DebugVoiceLog.log("gemini_correction_deferred graceMs=$correctionGraceMs")
        lateinit var job: Job
        job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            delay(correctionGraceMs)
            if (generation.get() != current) return@launch
            // Only clear our own slot: a newer correction may have replaced this one.
            if (pendingCorrection === job) pendingCorrection = null
            sendCorrectionNow(text)
        }
        pendingCorrection = job
        job.start()
    }

    private fun sendCorrectionNow(text: String) {
        if (listeningSuspended) {
            DebugVoiceLog.log("gemini_correction_cancelled reason=listening_suspended")
        } else if (trySend(GeminiLiveProtocol.textTurn(text))) {
            DebugVoiceLog.log("gemini_correction_sent")
        } else {
            DebugVoiceLog.log("gemini_correction_cancelled reason=not_connected")
        }
    }

    private fun cancelCorrection(reason: String) {
        val job = pendingCorrection ?: return
        pendingCorrection = null
        if (job.isActive) {
            job.cancel()
            DebugVoiceLog.log("gemini_correction_cancelled reason=$reason")
        }
    }

    // ---- server messages --------------------------------------------------------------------

    private fun listener(current: Long, pending: CompletableDeferred<Unit>, setup: String) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (generation.get() != current) return
            if (!webSocket.send(setup)) {
                pending.completeExceptionally(VoiceProviderException(GeminiLiveProtocol.CONNECTION_FAILED, "failed to send Gemini Live setup"))
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) = handle(current, pending, text)

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(current, pending, bytes.utf8())

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (generation.get() != current) return
            fail(pending, GeminiLiveProtocol.socketFailure(response?.code, t), "socket_failure")
            Telemetry.record(EventType.SOCKET_DISCONNECTED, detail = "failure")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (generation.get() != current) return
            DebugVoiceLog.log("gemini_closed code=$code")
            Telemetry.record(EventType.SOCKET_DISCONNECTED, detail = "server_closed code=$code")
            // An expired handle rejected at setup must not make a reconnect terminal (MALFORMED).
            val resumeRejected = setupResumed && code == 1007 && !pending.isCompleted
            if (resumeRejected) {
                resumptionHandle = null
                DebugVoiceLog.log("gemini_resume_rejected handle_cleared=true")
            }
            fail(pending, GeminiLiveProtocol.closeFailure(code, reason, resumeRejected), "socket_closed", closed = true)
        }
    }

    private fun fail(pending: CompletableDeferred<Unit>, failure: VoiceProviderException, reason: String, closed: Boolean = false) {
        cancelCorrection(reason)
        pipeline.dropHeld(reason)
        synchronized(this) { resetTurn() }
        socket = null
        if (!pending.completeExceptionally(failure)) {
            emit(DomainVoiceEvent.Error(failure.code, failure.safeMessage))
            if (closed) emit(DomainVoiceEvent.Closed)
        }
    }

    private fun handle(current: Long, pending: CompletableDeferred<Unit>, text: String) {
        if (generation.get() != current) return
        val message = runCatching { GeminiLiveProtocol.parse(text) }.getOrElse {
            DebugVoiceLog.log("gemini_protocol_error chars=${text.length}")
            val mapped = VoiceProviderException(GeminiLiveProtocol.PROTOCOL_ERROR, "invalid Gemini Live message", it)
            if (!pending.completeExceptionally(mapped)) emit(DomainVoiceEvent.Error(mapped.code, mapped.safeMessage))
            return
        }
        if (message.setupComplete) {
            DebugVoiceLog.log("gemini_setup_complete")
            if (pending.complete(Unit)) {
                Telemetry.record(EventType.SOCKET_CONNECTED)
                emit(DomainVoiceEvent.SessionReady(model, interruptResponse = true))
            }
        }
        if (message.resumable && message.resumptionHandle != null) {
            resumptionHandle = message.resumptionHandle
            DebugVoiceLog.log("gemini_resumption_update resumable=true")
        }
        // The reconnect follows on the next close (a RETRYABLE code), reusing the handle.
        if (message.goAway) DebugVoiceLog.log("gemini_go_away")
        if (message.thoughtParts > 0) DebugVoiceLog.log("gemini_thought_dropped parts=${message.thoughtParts}")
        message.voiceActivity?.let { DebugVoiceLog.log("gemini_voice_activity type=$it") }
        synchronized(this) {
            if (generation.get() != current) return
            if (message.voiceActivity == GeminiLiveProtocol.ACTIVITY_START && !onsetSeen) {
                // The local uplink gate missed this onset (F19): the server's start opens the turn.
                cancelCorrection("driver_turn")
                beginDriverTurnLocked()
                DebugVoiceLog.log("gemini_driver_turn_fallback source=voice_activity")
            }
            onContent(message)
        }
        message.workPending?.let { emit(DomainVoiceEvent.ProviderWorkState(it)) }
        if (message.cancelledCallIds.isNotEmpty()) {
            DebugVoiceLog.log("gemini_tool_call_cancellation ids=${message.cancelledCallIds}")
            message.cancelledCallIds.forEach { callNames.remove(it) }
            emit(DomainVoiceEvent.ToolCallCancelled(message.cancelledCallIds))
        }
    }

    private fun onContent(message: GeminiLiveProtocol.ServerMessage) {
        message.inputTranscription?.let { inputText.append(it) }
        // Only audio or a call opens a turn: a transcript alone (e.g. a stray chunk after
        // turnComplete) would open a response for the old driver turn and swallow the next reply.
        if (!turnOpen && (message.audio.isNotEmpty() || message.toolCalls.isNotEmpty())) {
            openTurn()
        }
        message.audio.forEach { data ->
            if (!turnAudioSeen) {
                turnAudioSeen = true
                Telemetry.record(EventType.TTS_START)
            }
            val event = DomainVoiceEvent.AudioDelta(data)
            if (!pipeline.filter(event)) emit(event)
        }
        message.outputTranscription?.let { chunk ->
            if (!turnOpen) {
                strayOutput.append(chunk)
            } else {
                turnText.append(chunk)
                pipeline.appendAssistantText(chunk)
            }
        }
        message.toolCalls.forEach(::onToolCall)
        if (message.generationComplete) finishGeneration()
        if (message.interrupted) {
            emit(DomainVoiceEvent.Interrupted("server_vad"))
            Telemetry.record(EventType.INTERRUPT_DETECTED)
            closeTurn("cancelled")
        } else if (message.turnComplete) {
            closeTurn("completed")
        }
    }

    private fun openTurn() {
        // No local onset since the previous turn (e.g. the uplink gate did not see it), yet the
        // driver was transcribed: open the driver turn here, before its transcript is attributed.
        if (!onsetSeen && inputText.isNotBlank()) {
            cancelCorrection("driver_turn")
            pipeline.beginDriverTurn(playbackOrSpeaking = playbackActive, responseInProgress = false)
            DebugVoiceLog.log("gemini_driver_turn_fallback source=transcript")
        }
        flushInput()
        clientCancelled = false
        pipeline.clearCallsThisResponse()
        pipeline.onResponseCreated()
        clearTurnState()
        turnOpen = true
        Telemetry.record(EventType.AGENT_REQUEST_START)
        DebugVoiceLog.log("gemini_turn_open")
        emit(DomainVoiceEvent.ResponseStarted)
        if (strayOutput.isNotEmpty()) {
            val text = strayOutput.toString()
            strayOutput.setLength(0)
            turnText.append(text)
            pipeline.appendAssistantText(text)
        }
    }

    private fun flushInput() {
        val text = inputText.toString()
        inputText.setLength(0)
        if (text.isBlank()) return
        Telemetry.record(EventType.ASR_RESULT) { text }
        pipeline.onUserTranscript(text)
        emit(DomainVoiceEvent.UserTranscript(text, final = true))
    }

    private fun finishGeneration() {
        if (!turnOpen || generationDone) return
        generationDone = true
        val text = turnText.toString()
        if (text.isNotBlank()) {
            Telemetry.record(EventType.ASSISTANT_REPLY) { text }
            val subtitle = DomainVoiceEvent.AssistantTranscript(text, final = true)
            if (!pipeline.filter(subtitle)) emit(subtitle)
        }
        if (turnAudioSeen) {
            Telemetry.record(EventType.TTS_END)
            if (!pipeline.filter(DomainVoiceEvent.AudioDone)) emit(DomainVoiceEvent.AudioDone)
        }
    }

    private fun closeTurn(status: String) {
        flushInput()
        strayOutput.setLength(0)
        if (!turnOpen) return
        finishGeneration()
        val outcome = ResponseOutcome(spoke = turnAudioSeen, toolCallIds = turnCallIds.toList(), unidentifiedToolCalls = 0)
        val superseded = pipeline.takeSuperseded()
        if (!superseded) pipeline.settleResponse(outcome)
        pipeline.afterResponse(outcome, superseded)
        DebugVoiceLog.log("gemini_turn_done status=$status calls=${outcome.toolCallIds.size} spoke=${outcome.spoke}")
        Telemetry.record(EventType.RESPONSE_COMPLETED, detail = status)
        emit(DomainVoiceEvent.ResponseDone(status))
        pipeline.releaseFallback()
        clearTurnState()
        onsetSeen = false
        clientCancelled = false
    }

    private fun onToolCall(raw: GeminiLiveProtocol.RawCall) {
        val call = GeminiLiveProtocol.toolCall(raw)
        if (call == null) {
            DebugVoiceLog.log("gemini_call_dropped reason=invalid_id")
            return
        }
        if (pipeline.isDuplicateCall(call)) {
            DebugVoiceLog.log("gemini_duplicate_call_ignored tool=${call.name}")
            trySend(GeminiLiveProtocol.toolResponse(call.callId, call.name, GeminiLiveProtocol.duplicateCallOutput(call.name)))
            return
        }
        cancelCorrection("tool_call")
        callDispatchedThisDriverTurn = true
        pipeline.onToolCallDispatched(call)
        callNames[call.callId] = call.name
        turnCallIds += call.callId
        val shape = if (call.arguments.containsKey("_validation_error")) "rejected" else "valid"
        DebugVoiceLog.log("gemini_tool_call id=${call.callId} tool=${call.name} args=$shape")
        // Tool calls are never held.
        emit(call)
    }

    private fun trySend(message: String): Boolean = socket?.send(message) == true

    private fun sendControl(message: String) {
        if (!trySend(message)) {
            throw VoiceProviderException(GeminiLiveProtocol.CONNECTION_CLOSED, "Gemini Live WebSocket is not connected")
        }
    }

    private fun emit(event: DomainVoiceEvent) {
        if (!eventFlow.tryEmit(RealtimeEvent(SystemSessionClock.nowMs(), event))) {
            DebugVoiceLog.log("gemini_event_dropped count=${droppedEvents.incrementAndGet()}")
        }
    }

    companion object {
        private fun defaultHttp() = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS).build()
    }
}
