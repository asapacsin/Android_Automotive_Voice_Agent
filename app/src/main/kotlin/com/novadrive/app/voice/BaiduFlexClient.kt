package com.novadrive.app.voice

import com.novadrive.app.BaiduApiConfig
import com.novadrive.app.BaiduAppSettings
import com.novadrive.evaluation.EventType
import com.novadrive.evaluation.Telemetry
import com.novadrive.app.BaiduAuthMode
import com.novadrive.app.DebugVoiceLog
import com.novadrive.app.NavigationState
import com.novadrive.app.PersonaProfiles
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.RealtimeEvent
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
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class BaiduFlexClient(
    private val http: OkHttpClient = defaultHttp(),
    private val readyTimeoutMs: Long = 10_000,
    private val requireTls: Boolean = true,
    private val tokenClient: BaiduAccessTokenClient = BaiduAccessTokenClient(http),
    private val contextHint: () -> String? = { VoiceContextHints.current() },
    /** Shape of the audio that caused the current turn, measured by [SpeechUplinkGate]. */
    private val lastAudioSegment: () -> SpeechUplinkGate.Segment? = { null },
    /** True when something on screen is waiting for the driver's answer; such turns are never held. */
    private val contextAwaitingAnswer: () -> Boolean = { VoiceContextHints.current() != null },
) {
    private val eventFlow = MutableSharedFlow<RealtimeEvent>(replay = 0, extraBufferCapacity = 64)
    private val generation = AtomicLong(0)
    private val assembler = FlexFunctionCallAssembler()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var ready: CompletableDeferred<Unit>? = null
    @Volatile private var sessionCreated = false
    @Volatile private var assistantSpeaking = false
    @Volatile private var instructions: String = PersonaProfiles.DEFAULT_INSTRUCTIONS
    @Volatile private var voice: String = BaiduAppSettings.DEFAULT_VOICE
    @Volatile private var speed: Double = BaiduAppSettings.DEFAULT_SPEED
    @Volatile private var sentVoice: String = BaiduAppSettings.DEFAULT_VOICE
    @Volatile private var voiceFallbackUsed = false
    @Volatile private var vadThreshold: Double = BaiduFlexProtocol.DEFAULT_VAD_THRESHOLD
    @Volatile private var navigatingListener: ((Boolean) -> Unit)? = null
    private val emptyRetry = EmptyResponseRetryPolicy()
    private val resetPolicy = ConversationResetPolicy()
    private val resetScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )
    @Volatile private var lastConfig: BaiduApiConfig? = null
    @Volatile private var resetting = false
    @Volatile private var resetJob: kotlinx.coroutines.Job? = null

    /** Outbound messages held while the conversation is being reset; flushed to the new one. */
    private val heldOutbound = java.util.concurrent.ConcurrentLinkedQueue<String>()

    /** App-requested replies wait for Baidu's current reply and the driver's speech (see [ResponseTurnGate]). */
    private val turnGate = ResponseTurnGate()

    /** A reply is in progress on the server (response.created .. response.done). */
    @Volatile private var responseInProgress = false
    @Volatile private var ttsStartedForResponse = false

    /** Replies that claim an action without a tool call get one corrective follow-up. */
    private val actionGuard = ActionClaimGuard()
    private val assistantText = StringBuffer()

    /** Incremented on every conversation reset; a queued turn from an older one may be stale. */
    @Volatile private var conversationEpoch = 0

    fun events(): Flow<RealtimeEvent> = eventFlow.asSharedFlow()

    suspend fun connect(config: BaiduApiConfig) {
        lastConfig = config
        cancelReset()
        turnGate.clear()
        actionGuard.reset()
        openSession(config)
    }

    private suspend fun openSession(config: BaiduApiConfig) {
        closeSocket()
        val effective = config.copy(settings = config.settings.copy(model = BaiduFlexProtocol.MODEL))
        val validationSettings = if (requireTls) effective.settings else effective.settings.copy(
            endpoint = effective.settings.endpoint.replaceFirst("ws://", "wss://"),
            tokenEndpoint = effective.settings.tokenEndpoint.replaceFirst("http://", "https://"),
        )
        com.novadrive.app.BaiduSettingsValidator.validate(validationSettings, effective.credentials)?.let {
            throw VoiceProviderException(it, it)
        }
        val token = if (effective.settings.authMode == BaiduAuthMode.LEGACY_ACCESS_TOKEN) {
            tokenClient.getToken(effective.settings.tokenEndpoint, effective.credentials)
        } else null
        val endpoint = BaiduRealtimeClient.buildUrl(effective.settings.endpoint, BaiduFlexProtocol.MODEL, token, requireTls)
        val current = generation.incrementAndGet()
        val pending = CompletableDeferred<Unit>()
        ready = pending
        sessionCreated = false
        assistantSpeaking = false
        assembler.clear()
        emptyRetry.reset()
        resetPolicy.reset()
        instructions = PersonaProfiles.sanitize(effective.settings.instructions)
        voice = effective.settings.voice
        speed = effective.settings.speed
        sentVoice = voice
        voiceFallbackUsed = false
        val request = Request.Builder().url(endpoint).apply {
            if (effective.settings.authMode == BaiduAuthMode.BEARER_API_KEY) {
                header("Authorization", "Bearer ${effective.credentials.apiKey}")
            }
        }.build()
        socket = http.newWebSocket(request, listener(current, pending))
        NetworkFaults.dropConnection = { socket?.cancel() }
        vadThreshold = if (NavigationState.navigating) {
            BaiduFlexProtocol.NAVIGATION_VAD_THRESHOLD
        } else {
            BaiduFlexProtocol.DEFAULT_VAD_THRESHOLD
        }
        // Deliberately does NOT resend session.update. Measured on device 2026-09-16: Baidu
        // Flex rejects a turn-detection threshold change while input audio is in progress
        // ("Cannot update a session's turn detection threshold ..."), which is always the
        // case mid-conversation, and the rejection took the whole session down right after
        // navigate_to succeeded. The navigation threshold is applied at the next connect
        // instead (see the vadThreshold assignment above).
        val listener: (Boolean) -> Unit = { navigating ->
            DebugVoiceLog.log("vad_threshold_deferred navigating=$navigating")
        }
        navigatingListener = listener
        // Test Connection constructs a throwaway BaiduFlexClient; disconnect() must not
        // wipe another instance's listener (same hazard as onFocusChanged). See releaseNavigatingListenerIfOwned.
        NavigationState.onNavigatingChanged = listener
        try {
            withTimeout(readyTimeoutMs) { pending.await() }
        } catch (_: TimeoutCancellationException) {
            closeSocket()
            throw VoiceProviderException("BAIDU_FLEX_TIMEOUT", "Baidu Flex session readiness timed out")
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
        val config = lastConfig ?: return
        if (resetting) return
        resetting = true
        conversationEpoch++
        val started = System.currentTimeMillis()
        DebugVoiceLog.log("flex_context_reset")
        resetJob = resetScope.launch {
            try {
                openSession(config)
                DebugVoiceLog.log("flex_context_reset_done ms=${System.currentTimeMillis() - started} held=${heldOutbound.size}")
            } catch (failure: VoiceProviderException) {
                DebugVoiceLog.log("flex_context_reset_failed code=${failure.code}")
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
        heldOutbound.clear()
    }

    fun sendAudio(pcm16le: ByteArray) {
        val message = BaiduFlexProtocol.audioAppend(Base64.getEncoder().encodeToString(pcm16le))
        if (resetting) {
            if (heldOutbound.size < MAX_HELD_OUTBOUND) heldOutbound += message
            return
        }
        if (!trySend(message)) {
            emit(DomainVoiceEvent.Error("BAIDU_FLEX_CONNECTION_CLOSED", "Baidu Flex WebSocket is not connected"))
        }
    }

    fun cancelResponse() {
        if (assistantSpeaking) trySend(BaiduFlexProtocol.responseCancel())
    }

    /**
     * Listening was ended by the driver: cancel the reply to that utterance even if no audio has
     * arrived yet (cancelResponse only acts once audio plays, to keep barge-in behaviour as is).
     */
    fun cancelActiveResponse() {
        if (responseInProgress || assistantSpeaking) trySend(BaiduFlexProtocol.responseCancel())
    }

    /**
     * Listening stopped (sleep): held microphone audio is dropped, and the client sends no turns of
     * its own (false-claim follow-ups, queued replies) until listening resumes.
     */
    fun discardPendingAudio() {
        listeningSuspended = true
        heldOutbound.removeIf { it.contains("\"input_audio_buffer.append\"") }
        turnGate.clear()
    }

    /** Listening resumed after a sleep. The conversation (and its context) simply continues. */
    fun resumeListening() {
        listeningSuspended = false
    }

    @Volatile private var listeningSuspended = false

    /**
     * Diagnostics (read-only): work the client still has of its own — queued reply turns, messages
     * held during a conversation reset, a reset in progress. Used by the simulation benchmark to
     * know a turn has settled; changes nothing.
     */
    internal val ownWorkInFlight: Int
        get() = turnGate.pending() + heldOutbound.size + (if (resetting) 1 else 0)

    fun sendUserText(text: String) {
        DebugVoiceLog.log("flex_user_text chars=${text.length}")
        listeningSuspended = false
        val messages = listOf(BaiduFlexProtocol.userTextMessage(text), BaiduFlexProtocol.responseCreate())
        if (resetting) {
            heldOutbound += messages
            return
        }
        if (!turnGate.submit(ResponseTurnGate.Turn(messages, epoch = conversationEpoch))) {
            DebugVoiceLog.log("flex_turn_deferred kind=text pending=${turnGate.pending()}")
            return
        }
        messages.forEach(::sendControl)
    }

    /**
     * Calls already emitted in the current response, by name and arguments. Found by the
     * simulation benchmark (2026-09-17): the same call emitted twice in one response was executed
     * twice — harmless for 「播放」, wrong for 「调高一点」.
     */
    private val callsThisResponse = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** Filters a repeated identical call and answers it without executing it. */
    private fun dropDuplicateCall(event: DomainVoiceEvent): Boolean {
        if (event !is DomainVoiceEvent.ToolCall || event.arguments.containsKey("_validation_error")) return false
        val signature = event.name + "|" + event.arguments.toSortedMap()
        if (callsThisResponse.add(signature)) return false
        DebugVoiceLog.log("flex_duplicate_call_ignored tool=${event.name}")
        trySend(
            BaiduFlexProtocol.functionCallOutput(
                event.callId,
                JSONObject().put("ok", true).put("tool", event.name).put("status", "duplicate_call_ignored")
                    .put("instruction", "同一个操作刚才已经执行过一次，这次没有重复执行。").toString(),
            ),
        )
        resetPolicy.onToolResultSent(event.callId)
        return true
    }

    fun sendFunctionResult(callId: String, output: String) {
        sendControl(BaiduFlexProtocol.functionCallOutput(callId, output))
        resetPolicy.onToolResultSent(callId)
        actionGuard.onToolResult(output)
        // The call id belongs to this conversation: after a reset there is nothing to answer.
        val reply = ResponseTurnGate.Turn(listOf(BaiduFlexProtocol.responseCreate()), emptyList(), conversationEpoch)
        if (!turnGate.submit(reply)) {
            DebugVoiceLog.log("flex_turn_deferred kind=tool_result pending=${turnGate.pending()}")
            return
        }
        sendControl(BaiduFlexProtocol.responseCreate())
    }

    /** Sends queued app replies once Baidu is free; one at a time, each after the previous reply. */
    private fun flushDeferredTurns() {
        while (true) {
            val turn = turnGate.next() ?: return
            val messages = if (turn.epoch == conversationEpoch) turn.messages else turn.afterReset
            if (messages.isEmpty()) {
                DebugVoiceLog.log("flex_turn_dropped reason=conversation_reset")
                turnGate.onResponseDone()
                continue
            }
            DebugVoiceLog.log("flex_turn_released messages=${messages.size} held=$resetting")
            if (resetting) heldOutbound += messages else messages.forEach { trySend(it) }
            return
        }
    }

    fun disconnect() {
        cancelReset()
        closeSocket()
    }

    private fun closeSocket() {
        generation.incrementAndGet()
        ready?.cancel(); ready = null
        if (socket != null) Telemetry.record(EventType.SOCKET_DISCONNECTED, detail = "client_close")
        sessionCreated = false
        assistantSpeaking = false
        responseInProgress = false
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
        if (hint != null) DebugVoiceLog.log("flex_context_hint chars=${hint.length}")
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
                "flex_response_done status=$status " +
                    "reason=${details?.optString("reason").orEmpty().ifBlank { "-" }} outputs=$kinds",
            )
            if (kinds.isEmpty()) {
                // An empty response carries no user or assistant content, only ids and usage.
                DebugVoiceLog.log("flex_empty_response_raw ${text.take(800)}")
            }
            val callIds = (0 until (outputs?.length() ?: 0)).mapNotNull { i ->
                outputs!!.optJSONObject(i)?.takeIf { it.optString("type") == "function_call" }?.optString("call_id")?.takeIf { it.isNotEmpty() }
            }
            // A turn that asked for an action is real by definition; only actionless turns can be
            // phantoms. Decided here, where the outputs are already parsed.
            finishTurnHold(hadToolCall = kinds.any { it == "function_call" })
            if (resetPolicy.onResponseDone(kinds, callIds)) resetConversation()
            val spoken = assistantText.toString()
            assistantText.setLength(0)
            actionGuard.onResponseDone(kinds, spoken)?.takeIf { !listeningSuspended }?.let { nudge ->
                DebugVoiceLog.log("flex_action_claim_unverified follow_up=true")
                Telemetry.record(EventType.GUARD_FOLLOW_UP)
                sendUserText(nudge)
            }
            emptyRetry.onResponseDone(status, kinds.size)
        }.getOrDefault(false)

    /** One extra response.create for a turn Baidu completed with no output (see EmptyResponseRetryPolicy). */
    private fun requestReplyAfterEmptyResponse() {
        DebugVoiceLog.log("flex_empty_response_retry")
        trySend(BaiduFlexProtocol.responseCreate())
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
                if (!webSocket.send(BaiduFlexProtocol.sessionUpdate(instructionsWithContext(), voice, speed, vadThreshold))) {
                    pending.completeExceptionally(VoiceProviderException("BAIDU_FLEX_SESSION_FAILED", "failed to configure Baidu Flex session"))
                }
            }
            val events = runCatching {
                assembler.consume(text) + BaiduFlexProtocol.parseCommonEvent(text, assistantSpeaking)
            }.getOrElse { failProtocol(pending, it); return }
            if (type == "session.updated" && sessionCreated && !pending.isCompleted) {
                Telemetry.record(EventType.SOCKET_CONNECTED)
            }
            recordTelemetry(type, text)
            if (type == "session.updated" && sessionCreated) pending.complete(Unit)
            if (type == "response.audio.delta") assistantSpeaking = true
            if (type == "response.audio.done" || type == "response.done") assistantSpeaking = false
            if (type !in NOISY_EVENT_TYPES) DebugVoiceLog.log("flex_event type=$type")
            if (type == "input_audio_buffer.speech_started") {
                emptyRetry.onSpeechStarted()
                turnGate.onSpeechStarted()
                beginDriverTurn()
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
                turnGate.onResponseCreated()
                assistantText.setLength(0)
                beginTurnHold()
            }
            if (type == "response.audio_transcript.done" || type == "response.text.done") {
                val raw = JSONObject(text)
                assistantText.append(raw.optString("transcript").ifEmpty { raw.optString("text") })
                // A reply carrying real content is not a phantom whatever the audio looked like.
                if (!PhantomTurnGate.isContentlessReply(assistantText.toString())) releaseHoldEarly("real_reply")
            }
            if (type == "conversation.item.input_audio_transcription.completed") {
                val transcript = JSONObject(text).optString("transcript")
                actionGuard.onUserTranscript(transcript)
                // A request, not a grunt: noise has come back as 「。」 and as 「嗯。」.
                if (PhantomTurnGate.isMeaningfulTranscript(transcript)) {
                    userSpokeThisTurn = true
                    releaseHoldEarly("user_spoke")
                }
            }
            if (type == "error" && BaiduFlexProtocol.isResponseAlreadyActive(text)) {
                DebugVoiceLog.log("flex_turn_rejected_busy retry=true")
                turnGate.onBusyRejected(
                    ResponseTurnGate.Turn(listOf(BaiduFlexProtocol.responseCreate()), emptyList(), conversationEpoch),
                )
            }
            if (type == "conversation.item.input_audio_transcription.completed" && emptyRetry.onUserTranscriptCompleted()) {
                requestReplyAfterEmptyResponse()
            }
            if (type == "response.done") {
                turnGate.onResponseDone()
                if (onResponseDone(text)) requestReplyAfterEmptyResponse() else flushDeferredTurns()
                // Fail-safe. finishTurnHold has already cleared the buffer on either verdict, so
                // this only fires when the parse above returned early — better a spoken reply than
                // audio stranded in a list.
                releaseHeldAudio("response_done_fallback")
            }
            events.forEach { event ->
                if (dropDuplicateCall(event)) return@forEach
                // A tool call proves the driver's turn was real; never let one sit behind a hold,
                // and remember it so the spoken result of the action is not judged on its own.
                if (event is DomainVoiceEvent.ToolCall) {
                    toolCalledThisTurn = true
                    releaseHoldEarly("tool_call")
                }
                if (holdOrEmit(event)) return@forEach
                if (event is DomainVoiceEvent.Error && !pending.isCompleted) {
                    if (!voiceFallbackUsed && sentVoice != BaiduAppSettings.DEFAULT_VOICE) {
                        voiceFallbackUsed = true
                        sentVoice = BaiduAppSettings.DEFAULT_VOICE
                        webSocket.send(BaiduFlexProtocol.sessionUpdate(instructionsWithContext(), BaiduAppSettings.DEFAULT_VOICE, speed, vadThreshold))
                        return
                    }
                    pending.completeExceptionally(VoiceProviderException(event.code, event.message))
                }
                emit(event)
            }
        }

        override fun onFailure(webSocket: WebSocket, failure: Throwable, response: Response?) {
            if (generation.get() != current) return
            dropHeldAudio("socket_failure")
            Telemetry.record(EventType.SOCKET_DISCONNECTED, detail = "failure")
            val mapped = mapFailure(failure, response)
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
            dropHeldAudio("socket_closed")
            Telemetry.record(EventType.SOCKET_DISCONNECTED, detail = "server_closed code=$code")
            val failure = VoiceProviderException("BAIDU_FLEX_CONNECTION_CLOSED", "Baidu Flex WebSocket closed (status=$code)")
            if (!pending.completeExceptionally(failure)) {
                emit(DomainVoiceEvent.Error(failure.code, failure.safeMessage)); emit(DomainVoiceEvent.Closed)
            }
        }

        private fun failProtocol(pending: CompletableDeferred<Unit>, failure: Throwable) {
            val mapped = VoiceProviderException("BAIDU_FLEX_PROTOCOL_ERROR", "invalid Baidu Flex realtime event", failure)
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
                callsThisResponse.clear()
                ttsStartedForResponse = false
                Telemetry.record(EventType.AGENT_REQUEST_START)
            }
            "response.audio.delta" -> if (!ttsStartedForResponse) {
                ttsStartedForResponse = true
                Telemetry.record(EventType.TTS_START)
            }
            "response.audio.done" -> Telemetry.record(EventType.TTS_END)
            "response.audio_transcript.done", "response.text.done" -> Telemetry.record(EventType.ASSISTANT_REPLY) {
                JSONObject(text).let { it.optString("transcript").ifEmpty { it.optString("text") } }
            }
            "response.done" -> {
                responseInProgress = false
                Telemetry.record(EventType.RESPONSE_COMPLETED, detail = JSONObject(text).optJSONObject("response")?.optString("status"))
            }
            "error" -> Telemetry.record(EventType.ERROR, errorCode = runCatching {
                JSONObject(text).optJSONObject("error")?.optString("code")
            }.getOrNull())
        }
    }

    private fun trySend(message: String): Boolean = socket?.send(message) == true

    private fun sendControl(message: String) {
        if (!trySend(message)) throw VoiceProviderException(
            "BAIDU_FLEX_CONNECTION_CLOSED", "Baidu Flex WebSocket is not connected",
        )
    }

    // ---- phantom-turn hold ------------------------------------------------
    // Reply audio for a turn whose input looked like noise is held until response.done, then
    // either released or dropped. Only audio is held, and only for suspicious input, so a normal
    // turn is never delayed by a single millisecond. Tool calls are never held.

    private val heldReplyAudio = mutableListOf<DomainVoiceEvent>()

    @Volatile
    private var holdingTurn = false
    private var turnSegment: SpeechUplinkGate.Segment? = null

    /** Did the provider transcribe a request during the driver's current turn? */
    @Volatile
    private var userSpokeThisTurn = false

    /** Did the driver's current turn produce an action? Then every reply in it is real. */
    @Volatile
    private var toolCalledThisTurn = false

    /**
     * A new **driver** turn, not a new response. One utterance produces several responses — the
     * tool call, then the spoken result — and the transcript belongs to the utterance. Measured on
     * device 2026-09-18: resetting this per response made the second response of a real command
     * look like a turn nobody spoke, and 「音乐已开始播放。」 was silenced.
     */
    private fun beginDriverTurn() {
        userSpokeThisTurn = false
        toolCalledThisTurn = false
    }

    private fun beginTurnHold() {
        releaseHeldAudio("superseded")
        val segment = lastAudioSegment()
        turnSegment = segment
        // Nothing to judge once the driver has been heard or has acted: hold only what is doubtful.
        holdingTurn = segment != null && segment.needsHold() && !contextAwaitingAnswer() &&
            !userSpokeThisTurn && !toolCalledThisTurn
        if (holdingTurn) {
            DebugVoiceLog.log(
                "PHANTOM_GATE_HOLD durationMs=${segment?.durationMs} voicedRatio=${"%.0f".format(segment?.voicedRatio ?: 0.0)}",
            )
        }
    }

    /**
     * The turn has proved itself real before it finished, so stop holding immediately. Without
     * this, every short genuine command would wait for response.done to be spoken.
     */
    private fun releaseHoldEarly(reason: String) {
        if (holdingTurn) releaseHeldAudio(reason)
    }

    /** Never lose a real reply: an over-long hold is released rather than risked. */
    private fun holdOrEmit(event: DomainVoiceEvent): Boolean {
        if (!holdingTurn) return false
        if (event !is DomainVoiceEvent.AudioDelta && event !is DomainVoiceEvent.AudioDone) return false
        heldReplyAudio += event
        if (heldReplyAudio.size > MAX_HELD_AUDIO_EVENTS) {
            DebugVoiceLog.log("PHANTOM_GATE_PASS reason=hold_budget_exceeded events=${heldReplyAudio.size}")
            releaseHeldAudio("budget")
        }
        return true
    }

    /** The reply can no longer be played anyway (socket gone): discard rather than emit late. */
    private fun dropHeldAudio(reason: String) {
        holdingTurn = false
        if (heldReplyAudio.isEmpty()) return
        DebugVoiceLog.log("PHANTOM_GATE_DISCARD reason=$reason events=${heldReplyAudio.size}")
        heldReplyAudio.clear()
    }

    private fun releaseHeldAudio(reason: String) {
        holdingTurn = false
        if (heldReplyAudio.isEmpty()) return
        val pending = heldReplyAudio.toList()
        heldReplyAudio.clear()
        if (reason != "superseded") DebugVoiceLog.log("PHANTOM_GATE_RELEASE reason=$reason events=${pending.size}")
        pending.forEach { emit(it) }
    }

    private fun finishTurnHold(hadToolCall: Boolean) {
        if (!holdingTurn) {
            turnSegment = null
            return
        }
        val verdict = PhantomTurnGate.judge(
            PhantomTurnGate.Turn(
                hadToolCall = hadToolCall || toolCalledThisTurn,
                contextAwaitingAnswer = contextAwaitingAnswer(),
                audio = turnSegment,
                assistantText = assistantText.toString(),
                hadUserTranscript = userSpokeThisTurn,
            ),
        )
        when (verdict) {
            is PhantomTurnGate.Verdict.Drop -> {
                val segment = turnSegment
                holdingTurn = false
                val dropped = heldReplyAudio.size
                heldReplyAudio.clear()
                DebugVoiceLog.log(
                    "PHANTOM_GATE_DROP reason=${verdict.reason} durationMs=${segment?.durationMs} " +
                        "voicedRatio=${"%.0f".format(segment?.voicedRatio ?: 0.0)} peak=${segment?.peak} " +
                        "userSpoke=$userSpokeThisTurn audioEvents=$dropped replyChars=${assistantText.length}",
                )
                Telemetry.record(EventType.AUDIO_STOPPED, detail = "phantom_turn_dropped")
            }
            PhantomTurnGate.Verdict.Speak -> releaseHeldAudio("genuine_turn")
        }
        turnSegment = null
    }

    private fun emit(event: DomainVoiceEvent) {
        eventFlow.tryEmit(RealtimeEvent(SystemSessionClock.nowMs(), event))
    }

    companion object {
        private const val SPEECH_STOPPED_FLUSH_MS = 1_600L

        /** ~6 s of held reply at typical delta sizes: a ceiling, not an expected value. */
        private const val MAX_HELD_AUDIO_EVENTS = 120
        private fun defaultHttp() = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS).build()

        fun mapFailure(failure: Throwable, response: Response?): VoiceProviderException {
            if (response?.code == 403) return VoiceProviderException(
                "BAIDU_FLEX_ACCESS_DENIED",
                "Baidu Flex public-beta/model access was denied (HTTP 403)",
                failure,
            )
            val base = BaiduRealtimeClient.mapFailure(failure, response)
            return VoiceProviderException(base.code.replace("BAIDU_", "BAIDU_FLEX_"), base.safeMessage, failure)
        }
    }
}

/**
 * DeveloperSettingsActivity creates a throwaway BaiduFlexClient for Test Connection, so two
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
/** About 10 s of 100 ms audio frames. */
private const val MAX_HELD_OUTBOUND = 100

private val NOISY_EVENT_TYPES = setOf(
    "response.audio.delta",
    "response.audio_transcript.delta",
    "response.text.delta",
    "response.function_call_arguments.delta",
    "conversation.item.input_audio_transcription.delta",
)
