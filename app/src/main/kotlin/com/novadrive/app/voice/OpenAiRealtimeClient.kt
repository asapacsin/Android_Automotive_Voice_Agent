package com.novadrive.app.voice

import com.novadrive.evaluation.EventType
import com.novadrive.evaluation.Telemetry
import com.novadrive.app.DebugVoiceLog
import com.novadrive.app.NavigationState
import com.novadrive.app.PersonaProfiles
import com.novadrive.app.SpeakingStyleState
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.RealtimeEvent
import com.novadrive.ingress.realtime.ResponseOutcome
import com.novadrive.ingress.realtime.SystemSessionClock
import com.novadrive.ingress.realtime.VoiceProviderException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns OpenAI-Realtime turn handling for every dialect (SPEC-021): socket lifecycle, readiness,
 * the event flow, turn gating, empty-response retry, conversation reset, cancel tracking, tool
 * dispatch and the [DriverTurnPipeline] wiring. Everything a vendor does differently on the wire
 * (endpoint, auth, session.update, error codes, log prefix, voice fallback) is behind [dialect].
 */
open class OpenAiRealtimeClient<C : Any>(
    protected val dialect: RealtimeDialect<C>,
    private val http: OkHttpClient,
    private val readyTimeoutMs: Long = 10_000,
    private val contextHint: () -> String? = { VoiceContextHints.current() },
    /** Shape of the audio that caused the current turn, measured by [SpeechUplinkGate]. */
    private val lastAudioSegment: () -> SpeechUplinkGate.Segment? = { null },
    /** True when something on screen is waiting for the driver's answer; such turns are never held. */
    private val contextAwaitingAnswer: () -> Boolean = { VoiceContextHints.awaitingAnswer() },
    /** Time-scoped post-AEC speech evidence for speech over playback (Astra P4). */
    private val speechEvidence: () -> Boolean = { true },
) {
    private val eventFlow = MutableSharedFlow<RealtimeEvent>(replay = 0, extraBufferCapacity = 64)
    private val generation = AtomicLong(0)
    private val assembler = dialect.newCallAssembler()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var ready: CompletableDeferred<Unit>? = null
    @Volatile private var sessionCreated = false
    @Volatile private var assistantSpeaking = false
    @Volatile private var rawInstructions: String? = null
    @Volatile private var vadThreshold: Double = dialect.defaultVadThreshold
    @Volatile private var playbackActive = false
    @Volatile private var navigatingListener: ((Boolean) -> Unit)? = null
    @Volatile private var textUnsupportedLogged = false
    private val emptyRetry = EmptyResponseRetryPolicy()
    private val resetPolicy = ConversationResetPolicy()
    private val resetScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )
    @Volatile private var lastConfig: C? = null
    @Volatile private var resetting = false

    /**
     * The reset policy has decided to reset, but [resetConversation] has not started yet. Text
     * turns are held from this point, not from [resetting]: found 2026-09-24 by the simulation
     * benchmark, a correction sent by DriverTurn while the finished response was still being
     * judged went out on the socket the reset closed a moment later, and the driver heard nothing.
     */
    @Volatile private var resetPending = false
    @Volatile private var resetJob: kotlinx.coroutines.Job? = null

    /** Outbound messages held while the conversation is being reset; flushed to the new one. */
    private val heldOutbound = java.util.concurrent.ConcurrentLinkedQueue<String>()

    /** App-requested replies wait for the provider's current reply and the driver's speech (see [ResponseTurnGate]). */
    private val turnGate = ResponseTurnGate()

    /** A reply is in progress on the server (response.created .. response.done). */
    @Volatile private var responseInProgress = false
    @Volatile private var ttsStartedForResponse = false

    @Volatile private var responseStartedAtMs = 0L

    @Volatile private var firstAudioAtMs = 0L

    /** The per-driver-turn output gate (INVARIANT I-1); this client only feeds it. */
    private val pipeline = DriverTurnPipeline(
        lastAudioSegment = lastAudioSegment,
        contextAwaitingAnswer = contextAwaitingAnswer,
        speechEvidence = speechEvidence,
        host = object : DriverTurnPipeline.Host {
            override fun emit(event: DomainVoiceEvent) = this@OpenAiRealtimeClient.emit(event)
            override fun sendCorrection(text: String, callMayFollow: Boolean) = sendUserText(text)
            override val responseCancelledByClient: Boolean get() = cancelSentThisResponse
            override val listeningSuspended: Boolean get() = this@OpenAiRealtimeClient.listeningSuspended
        },
    )

    /** Incremented on every conversation reset; a queued turn from an older one may be stale. */
    @Volatile private var conversationEpoch = 0

    fun events(): Flow<RealtimeEvent> = eventFlow.asSharedFlow()

    suspend fun connect(config: C) {
        lastConfig = config
        cancelReset()
        turnGate.clear()
        pipeline.resetGuard()
        openSession(config)
    }

    private suspend fun openSession(config: C) {
        closeSocket()
        val request = dialect.buildRequest(config)
        val current = generation.incrementAndGet()
        val pending = CompletableDeferred<Unit>()
        ready = pending
        sessionCreated = false
        assistantSpeaking = false
        assembler.clear()
        emptyRetry.reset()
        resetPolicy.reset()
        rawInstructions = dialect.instructions(config)
        dialect.onSessionOpening(config)
        textUnsupportedLogged = false
        socket = http.newWebSocket(request, listener(current, pending))
        NetworkFaults.dropConnection = { socket?.cancel() }
        playbackActive = false
        vadThreshold = dialect.vadThreshold(NavigationState.navigating)
        if (dialect.tracksNavigationVad) { // never resends session.update mid-session; see the dialect
            val listener: (Boolean) -> Unit = { navigating ->
                DebugVoiceLog.log("vad_threshold_deferred navigating=$navigating")
            }
            navigatingListener = listener
            // Never wipe another instance's listener; see releaseNavigatingListenerIfOwned.
            NavigationState.onNavigatingChanged = listener
        }
        try {
            withTimeout(readyTimeoutMs) { pending.await() }
        } catch (_: TimeoutCancellationException) {
            closeSocket()
            throw dialect.readyTimeout()
        } catch (failure: VoiceProviderException) {
            closeSocket()
            throw failure
        }
    }

    /**
     * Starts a fresh conversation on a new socket without the session layer noticing: audio and
     * text sent meanwhile are held and flushed afterwards. See [ConversationResetPolicy].
     */
    private fun resetConversation() {
        val config = lastConfig
        if (config == null) {
            resetPending = false
            while (true) {
                val message = heldOutbound.poll() ?: break
                if (!trySend(message)) break
            }
            return
        }
        if (resetting) {
            resetPending = false
            return
        }
        resetting = true
        resetPending = false
        conversationEpoch++
        val started = System.currentTimeMillis()
        DebugVoiceLog.log("${dialect.logPrefix}_context_reset")
        resetJob = resetScope.launch {
            try {
                openSession(config)
                DebugVoiceLog.log("${dialect.logPrefix}_context_reset_done ms=${System.currentTimeMillis() - started} held=${heldOutbound.size}")
            } catch (failure: VoiceProviderException) {
                DebugVoiceLog.log("${dialect.logPrefix}_context_reset_failed code=${failure.code}")
                emit(DomainVoiceEvent.Error(failure.code, failure.safeMessage))
            } catch (_: kotlinx.coroutines.CancellationException) {
                return@launch
            } finally {
                resetting = false
            }
            while (true) {
                val message = heldOutbound.poll() ?: break
                if (!trySend(message)) break
            }
        }
    }

    private fun cancelReset() {
        resetJob?.cancel()
        resetJob = null
        resetting = false
        resetPending = false
        heldOutbound.clear()
    }

    fun sendAudio(pcm16le: ByteArray) {
        val message = dialect.audioAppend(Base64.getEncoder().encodeToString(pcm16le))
        if (resetting) {
            if (heldOutbound.size < MAX_HELD_OUTBOUND) heldOutbound += message
            return
        }
        if (!trySend(message)) {
            dialect.notConnected().let { emit(DomainVoiceEvent.Error(it.code, it.safeMessage)) }
        }
    }

    fun cancelResponse() {
        if (assistantSpeaking) sendCancelOnce("barge_in")
    }

    /**
     * Listening was ended by the driver: cancel the reply to that utterance even if no audio has
     * arrived yet (cancelResponse only acts once audio plays, to keep barge-in behaviour as is).
     */
    fun cancelActiveResponse() {
        if (responseInProgress || assistantSpeaking) sendCancelOnce("active")
    }

    /**
     * This response was cancelled by us (OPEN_PROBLEMS P34/P35): send `response.cancel` once (a
     * second one, from 「闭嘴」's two paths, drew an `error`), and drop calls it still completes
     * (their arguments arrive cut off; the app already handled the utterance).
     */
    @Volatile private var cancelSentThisResponse = false

    private fun sendCancelOnce(reason: String) {
        if (cancelSentThisResponse) {
            DebugVoiceLog.log("${dialect.logPrefix}_cancel_skipped reason=$reason already_sent=true")
            return
        }
        if (trySend(dialect.responseCancel())) {
            cancelSentThisResponse = true
            DebugVoiceLog.log("${dialect.logPrefix}_cancel_sent reason=$reason")
        }
    }

    /** A call from a response this client cancelled: not executed, not answered, no new reply. */
    private fun dropCallFromCancelledResponse(event: DomainVoiceEvent): Boolean {
        if (event !is DomainVoiceEvent.ToolCall || !cancelSentThisResponse) return false
        val shape = if (event.arguments.containsKey("_validation_error")) "rejected" else "valid"
        DebugVoiceLog.log("${dialect.logPrefix}_call_dropped reason=response_cancelled tool=${event.name} args=$shape")
        resetPolicy.onCallDropped(event.callId)
        return true
    }

    /**
     * Listening stopped (sleep): held microphone audio is dropped, and the client sends no turns of
     * its own (false-claim follow-ups, queued replies) until listening resumes.
     */
    fun discardPendingAudio() {
        listeningSuspended = true
        heldOutbound.removeIf(dialect::isAudioAppend)
        turnGate.clear()
    }

    /** Listening resumed after a sleep. The conversation (and its context) simply continues. */
    fun resumeListening() {
        listeningSuspended = false
    }

    /** Tracks local assistant playout for barge-in; echo is cancelled client-side via WebRTC AEC3. */
    fun onPlaybackActiveChanged(active: Boolean) {
        playbackActive = active
    }

    @Volatile private var listeningSuspended = false

    /**
     * Diagnostics (read-only): work the client still has of its own — queued reply turns, messages
     * held during a conversation reset, a reset in progress. Used by the simulation benchmark to
     * know a turn has settled; changes nothing.
     */
    internal val ownWorkInFlight: Int
        get() = turnGate.pending() + heldOutbound.size + (if (resetting || resetPending) 1 else 0)

    fun sendUserText(text: String) {
        DebugVoiceLog.log("${dialect.logPrefix}_user_text chars=${text.length}")
        listeningSuspended = false
        val item = dialect.userTextMessage(text)
        if (item == null) {
            if (!textUnsupportedLogged) DebugVoiceLog.log("${dialect.logPrefix}_text_unsupported")
            textUnsupportedLogged = true
            return
        }
        val messages = listOf(item, dialect.responseCreate())
        if (resetting || resetPending) {
            heldOutbound += messages
            return
        }
        if (!turnGate.submit(ResponseTurnGate.Turn(messages, epoch = conversationEpoch))) {
            DebugVoiceLog.log("${dialect.logPrefix}_turn_deferred kind=text pending=${turnGate.pending()}")
            return
        }
        messages.forEach(::sendControl)
    }

    /** Filters a repeated identical call and answers it without executing it. */
    private fun dropDuplicateCall(event: DomainVoiceEvent): Boolean {
        if (event !is DomainVoiceEvent.ToolCall || !pipeline.isDuplicateCall(event)) return false
        DebugVoiceLog.log("${dialect.logPrefix}_duplicate_call_ignored tool=${event.name}")
        trySend(
            dialect.functionCallOutput(
                event.callId,
                JSONObject().put("ok", true).put("tool", event.name).put("status", "duplicate_call_ignored")
                    .put("instruction", "同一个操作刚才已经执行过一次，这次没有重复执行。").toString(),
            ),
        )
        resetPolicy.onToolResultSent(event.callId)
        return true
    }

    fun sendFunctionResult(callId: String, output: String) {
        sendControl(dialect.functionCallOutput(callId, output))
        resetPolicy.onToolResultSent(callId)
        // The one place execution evidence enters this client (INVARIANT I-1; see DriverTurnPipeline).
        pipeline.onToolResult(callId, output)
        // The call id belongs to this conversation: after a reset there is nothing to answer.
        val reply = ResponseTurnGate.Turn(listOf(dialect.responseCreate()), emptyList(), conversationEpoch)
        if (!turnGate.submit(reply)) {
            DebugVoiceLog.log("${dialect.logPrefix}_turn_deferred kind=tool_result pending=${turnGate.pending()}")
            return
        }
        sendControl(dialect.responseCreate())
    }

    /** Sends queued app replies once the provider is free; one at a time, each after the previous reply. */
    private fun flushDeferredTurns() {
        while (true) {
            val turn = turnGate.next() ?: return
            val messages = if (turn.epoch == conversationEpoch) turn.messages else turn.afterReset
            if (messages.isEmpty()) {
                DebugVoiceLog.log("${dialect.logPrefix}_turn_dropped reason=conversation_reset")
                turnGate.onResponseDone()
                continue
            }
            DebugVoiceLog.log("${dialect.logPrefix}_turn_released messages=${messages.size} held=$resetting")
            if (resetting) heldOutbound += messages else messages.forEach { trySend(it) }
            return
        }
    }

    fun disconnect() {
        cancelReset()
        closeSocket()
        // S2 of SPEC-006: a referent does not survive the session that produced it. 「再低一点」 after
        // a reconnect must be asked about, not answered from what the driver said before.
        pipeline.onSessionEnded()
    }

    private fun closeSocket() {
        generation.incrementAndGet()
        ready?.cancel(); ready = null
        if (socket != null) Telemetry.record(EventType.SOCKET_DISCONNECTED, detail = "client_close")
        sessionCreated = false
        assistantSpeaking = false
        responseInProgress = false
        cancelSentThisResponse = false
        turnGate.onConnectionReset()
        assembler.clear()
        val owned = navigatingListener
        navigatingListener = null
        releaseNavigatingListenerIfOwned(owned)
        val existing = socket; socket = null
        existing?.close(1000, "client close")
    }

    fun close() = disconnect()

    /** Persona plus a one-line description of what is on screen (see [VoiceContextHints]). */
    private fun instructionsWithContext(): String {
        val hint = contextHint()
        if (hint != null) DebugVoiceLog.log("${dialect.logPrefix}_context_hint chars=${hint.length}")
        val instructions = PersonaProfiles.compose(rawInstructions, SpeakingStyleState.current)
        return if (hint == null) instructions else instructions.trim() + "\n" + hint
    }

    /**
     * Diagnoses silent turns (THINKING -> LISTENING with no tool call and no reply): records how
     * the response ended and which kinds of output it held. Never the content.
     */
    private fun onResponseDone(text: String): Boolean =
        runCatching {
            val response = JSONObject(text).optJSONObject("response") ?: return false
            val status = response.optString("status")
            val details = response.optJSONObject("status_details")
            val outputs = response.optJSONArray("output")
            val kinds = (0 until (outputs?.length() ?: 0)).map { outputs!!.optJSONObject(it)?.optString("type").orEmpty() }
            DebugVoiceLog.log(
                "${dialect.logPrefix}_response_done status=$status " +
                    "reason=${details?.optString("reason").orEmpty().ifBlank { "-" }} outputs=$kinds",
            )
            if (kinds.isEmpty()) {
                // An empty response carries no user or assistant content, only ids and usage.
                DebugVoiceLog.log("${dialect.logPrefix}_empty_response_raw ${text.take(800)}")
            }
            // The provider's wire vocabulary stops here. Everything downstream is policy, and policy does
            // not get to know what this provider calls a tool call (ADR-009).
            val outcome = toOutcome(kinds, outputs)
            // A turn that asked for an action is real by definition; only actionless turns can be
            // phantoms. Decided here, where the outputs are already parsed.
            // Decided before the verdict: a correction the verdict sends must wait for the new
            // conversation rather than go out on the socket the reset is about to close.
            val superseded = pipeline.takeSuperseded()
            val resetNow = resetPolicy.onResponseDone(outcome, superseded)
            if (resetNow) resetPending = true
            // A superseded response was already dropped with its turn; judging it now would judge
            // the driver's *new* turn by the old reply.
            if (!superseded) pipeline.settleResponse(outcome)
            if (resetNow) resetConversation()
            pipeline.afterResponse(outcome, superseded)
            emptyRetry.onResponseDone(status, kinds.size)
        }.getOrDefault(false)

    /**
     * The provider's `output[].type` list translated into the neutral [ResponseOutcome].
     *
     * `"function_call"` and `"message"` are this vendor's words. A provider that called them
     * something else would translate here and every policy downstream would be unaffected, which
     * is the entire point of the boundary.
     */
    private fun toOutcome(kinds: List<String>, outputs: org.json.JSONArray?): ResponseOutcome {
        val callIds = (0 until (outputs?.length() ?: 0)).mapNotNull { i ->
            outputs!!.optJSONObject(i)
                ?.takeIf { it.optString("type") == WIRE_FUNCTION_CALL }
                ?.optString("call_id")
                ?.takeIf { it.isNotEmpty() }
        }
        val calls = kinds.count { it == WIRE_FUNCTION_CALL }
        return ResponseOutcome(
            spoke = kinds.any { it == WIRE_MESSAGE },
            toolCallIds = callIds,
            unidentifiedToolCalls = (calls - callIds.size).coerceAtLeast(0),
        )
    }

    /** One extra response.create for a turn the provider completed with no output (see EmptyResponseRetryPolicy). */
    private fun requestReplyAfterEmptyResponse() {
        DebugVoiceLog.log("${dialect.logPrefix}_empty_response_retry")
        trySend(dialect.responseCreate())
    }

    private fun listener(current: Long, pending: CompletableDeferred<Unit>) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = Unit

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (generation.get() != current) return
            NetworkFaults.inboundDelayMs.takeIf { it > 0 }?.let { Thread.sleep(it) }
            val type = runCatching { JSONObject(text).optString("type") }.getOrElse {
                failProtocol(pending, it); return
            }
            if (type == "session.created") {
                sessionCreated = true
                if (!webSocket.send(dialect.sessionUpdate(instructionsWithContext(), vadThreshold))) {
                    pending.completeExceptionally(dialect.sessionFailed())
                }
            }
            val events = runCatching {
                assembler.consume(text) + dialect.parseCommonEvent(text, assistantSpeaking)
            }.getOrElse { failProtocol(pending, it); return }
            if (type == "session.updated" && sessionCreated && !pending.isCompleted) {
                Telemetry.record(EventType.SOCKET_CONNECTED)
            }
            if (type == "session.updated") dialect.onSessionUpdated(text)
            recordTelemetry(type, text)
            if (type == "session.updated" && sessionCreated) pending.complete(Unit)
            if (type == "response.audio.delta") assistantSpeaking = true
            if (type == "response.audio.done" || type == "response.done") assistantSpeaking = false
            if (type !in NOISY_EVENT_TYPES) DebugVoiceLog.log("${dialect.logPrefix}_event type=$type")
            // Code only: a provider message can quote what the driver said.
            if (type == "error") DebugVoiceLog.log("${dialect.logPrefix}_error code=${dialect.errorCode(text)}")
            if (type == "input_audio_buffer.speech_started") {
                emptyRetry.onSpeechStarted()
                turnGate.onSpeechStarted()
                pipeline.beginDriverTurn(playbackActive || assistantSpeaking, responseInProgress)
            }
            if (type == "input_audio_buffer.speech_stopped") {
                turnGate.onSpeechStopped()
                // If the speech gets no reply (noise), queued turns must still go out.
                resetScope.launch {
                    kotlinx.coroutines.delay(SPEECH_STOPPED_FLUSH_MS)
                    if (generation.get() == current) flushDeferredTurns()
                }
            }
            if (type == "response.created") {
                cancelSentThisResponse = false
                turnGate.onResponseCreated()
                pipeline.onResponseCreated()
            }
            if (type == "response.audio_transcript.done" || type == "response.text.done") {
                val raw = JSONObject(text)
                pipeline.appendAssistantText(raw.optString("transcript").ifEmpty { raw.optString("text") })
            }
            if (type == "conversation.item.input_audio_transcription.completed") {
                val transcript = JSONObject(text).optString("transcript")
                pipeline.onUserTranscript(transcript)
            }
            if (type == "error" && dialect.isResponseAlreadyActive(text)) {
                DebugVoiceLog.log("${dialect.logPrefix}_turn_rejected_busy retry=true")
                turnGate.onBusyRejected(
                    ResponseTurnGate.Turn(listOf(dialect.responseCreate()), emptyList(), conversationEpoch),
                )
            }
            if (type == "conversation.item.input_audio_transcription.completed" && emptyRetry.onUserTranscriptCompleted()) {
                requestReplyAfterEmptyResponse()
            }
            if (type == "response.done") {
                turnGate.onResponseDone()
                if (onResponseDone(text)) requestReplyAfterEmptyResponse() else flushDeferredTurns()
                // Fail-safe: finishResponse clears the buffer on either verdict, so this only
                // fires when the parse above returned early. Better a spoken reply than audio
                // stranded in a list.
                pipeline.releaseFallback()
            }
            events.forEach { event ->
                if (dropCallFromCancelledResponse(event)) return@forEach
                if (dropDuplicateCall(event)) return@forEach
                // A tool call proves the driver's turn was real; never let one sit behind a hold,
                // and remember it so the spoken result of the action is not judged on its own.
                if (event is DomainVoiceEvent.ToolCall) pipeline.onToolCallDispatched(event)
                if (pipeline.filter(event)) return@forEach
                if (event is DomainVoiceEvent.Error && !pending.isCompleted) {
                    val recovery = dialect.recoverSessionError({ instructionsWithContext() }, vadThreshold)
                    if (recovery != null) {
                        webSocket.send(recovery)
                        return
                    }
                    pending.completeExceptionally(VoiceProviderException(event.code, event.message))
                }
                emit(event)
            }
        }

        override fun onFailure(webSocket: WebSocket, failure: Throwable, response: Response?) {
            if (generation.get() != current) return
            pipeline.dropHeld("socket_failure")
            Telemetry.record(EventType.SOCKET_DISCONNECTED, detail = "failure")
            val mapped = dialect.mapFailure(failure, response)
            if (!pending.completeExceptionally(mapped)) emit(DomainVoiceEvent.Error(mapped.code, mapped.safeMessage))
        }

        /**
         * The server closed the connection. OkHttp only reports [onClosed] once we answer the close;
         * without this a server-side close left the session silently dead (found by the simulation
         * benchmark, 2026-09-17): nothing failed, nothing reconnected, audio went nowhere.
         */
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (generation.get() != current) return
            pipeline.dropHeld("socket_closed")
            Telemetry.record(EventType.SOCKET_DISCONNECTED, detail = "server_closed code=$code")
            val failure = dialect.connectionClosed(code)
            if (!pending.completeExceptionally(failure)) {
                emit(DomainVoiceEvent.Error(failure.code, failure.safeMessage)); emit(DomainVoiceEvent.Closed)
            }
        }

        private fun failProtocol(pending: CompletableDeferred<Unit>, failure: Throwable) {
            val mapped = dialect.protocolError(failure)
            if (!pending.completeExceptionally(mapped)) emit(DomainVoiceEvent.Error(mapped.code, mapped.safeMessage))
        }
    }

    /** Interaction telemetry. Text is only kept by the recorder during benchmark runs. */
    private fun recordTelemetry(type: String, text: String) {
        when (type) {
            "input_audio_buffer.speech_started" -> {
                // Speech over a reply in progress is an interruption (server VAD barge-in).
                val interrupting = responseInProgress || assistantSpeaking
                Telemetry.record(EventType.SPEECH_START)
                if (interrupting) Telemetry.record(EventType.INTERRUPT_DETECTED)
            }
            "input_audio_buffer.speech_stopped" -> Telemetry.record(EventType.SPEECH_END)
            "conversation.item.input_audio_transcription.completed" ->
                Telemetry.record(EventType.ASR_RESULT) { JSONObject(text).optString("transcript") }
            "response.created" -> {
                responseInProgress = true
                pipeline.clearCallsThisResponse()
                ttsStartedForResponse = false
                responseStartedAtMs = System.currentTimeMillis()
                firstAudioAtMs = 0L
                Telemetry.record(EventType.AGENT_REQUEST_START)
            }
            "response.audio.delta" -> if (!ttsStartedForResponse) {
                ttsStartedForResponse = true
                firstAudioAtMs = System.currentTimeMillis()
                Telemetry.record(EventType.TTS_START)
            }
            "response.audio.done" -> Telemetry.record(EventType.TTS_END)
            "response.audio_transcript.done", "response.text.done" -> Telemetry.record(EventType.ASSISTANT_REPLY) {
                JSONObject(text).let { it.optString("transcript").ifEmpty { it.optString("text") } }
            }
            "response.done" -> {
                responseInProgress = false
                // What holding reply audio until the response proves itself would cost the driver
                // (B-014): the gap between first hearing something and the app first being able to
                // tell whether it was true.
                if (responseStartedAtMs > 0L) {
                    val now = System.currentTimeMillis()
                    DebugVoiceLog.log(
                        "reply_timing firstAudioMs=${if (firstAudioAtMs > 0L) firstAudioAtMs - responseStartedAtMs else -1L}" +
                            " doneMs=${now - responseStartedAtMs}" +
                            " holdCostMs=${if (firstAudioAtMs > 0L) now - firstAudioAtMs else -1L}",
                    )
                    responseStartedAtMs = 0L
                }
                Telemetry.record(EventType.RESPONSE_COMPLETED, detail = JSONObject(text).optJSONObject("response")?.optString("status"))
            }
            "error" -> Telemetry.record(EventType.ERROR, errorCode = runCatching {
                JSONObject(text).optJSONObject("error")?.optString("code")
            }.getOrNull())
        }
    }

    private fun trySend(message: String): Boolean = socket?.send(message) == true

    private fun sendControl(message: String) {
        if (!trySend(message)) throw dialect.notConnected()
    }

    private fun emit(event: DomainVoiceEvent) {
        eventFlow.tryEmit(RealtimeEvent(SystemSessionClock.nowMs(), event))
    }

    companion object {
        /**
         * The OpenAI-Realtime `output[].type` values. The only place in the app these strings may appear:
         * everything downstream takes a [ResponseOutcome] instead (ADR-009).
         */
        private const val WIRE_FUNCTION_CALL = "function_call"
        private const val WIRE_MESSAGE = "message"

        private const val SPEECH_STOPPED_FLUSH_MS = 1_600L

        internal fun defaultHttp() = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS).build()
    }
}

/**
 * DeveloperSettingsActivity creates a throwaway client for Test Connection, so two
 * instances can exist at once. A naive `onNavigatingChanged = null` in disconnect() would let
 * that throwaway wipe a live session's listener — the same class of defect as onFocusChanged
 * being assigned in two places with the second silently winning. Only clear the slot if this
 * instance still owns it (identity compare).
 */
internal fun releaseNavigatingListenerIfOwned(installed: ((Boolean) -> Unit)?) {
    if (NavigationState.onNavigatingChanged === installed) {
        NavigationState.onNavigatingChanged = null
    }
}

/** Streaming chunks: logging every one would flood logcat and could carry content. */
/** About 10 s of capture frames at the current mic cadence. */
private val MAX_HELD_OUTBOUND =
    com.novadrive.ingress.realtime.AudioFrameTiming.heldOutboundMessagesForDuration(10_000)

private val NOISY_EVENT_TYPES = setOf(
    "response.audio.delta",
    "response.audio_transcript.delta",
    "response.text.delta",
    "response.function_call_arguments.delta",
    "conversation.item.input_audio_transcription.delta",
)
