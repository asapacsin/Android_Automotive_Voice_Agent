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
    /** Voice id last requested in session.update (before any fallback). */
    @Volatile var requestedVoice: String = BaiduAppSettings.DEFAULT_VOICE
        private set
    /** Voice echoed by the last session.updated (or FALLBACK_VOICE after recovery). */
    @Volatile var confirmedVoice: String? = null
        private set
    val voiceConfirmedAsRequested: Boolean
        get() = confirmedVoice != null && confirmedVoice == requestedVoice
    @Volatile private var vadThreshold: Double = BaiduFlexProtocol.DEFAULT_VAD_THRESHOLD
    @Volatile private var playbackActive = false
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

    /** Reply-latency measurement for B-014; see the response.done branch. */
    /** A correction was already sent for the response being finished; see onResponseDone. */
    @Volatile private var correctionSentThisResponse = false

    @Volatile private var responseStartedAtMs = 0L

    @Volatile private var firstAudioAtMs = 0L

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
        requestedVoice = voice
        confirmedVoice = null
        voiceFallbackUsed = false
        val request = Request.Builder().url(endpoint).apply {
            if (effective.settings.authMode == BaiduAuthMode.BEARER_API_KEY) {
                header("Authorization", "Bearer ${effective.credentials.apiKey}")
            }
        }.build()
        socket = http.newWebSocket(request, listener(current, pending))
        NetworkFaults.dropConnection = { socket?.cancel() }
        playbackActive = false
        vadThreshold = resolveVadThreshold()
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

    /** Tracks local assistant playout for barge-in; echo is cancelled client-side via WebRTC AEC3. */
    fun onPlaybackActiveChanged(active: Boolean) {
        playbackActive = active
    }

    private fun resolveVadThreshold(): Double =
        BaiduFlexProtocol.playbackScopedVadThreshold(false, NavigationState.navigating)

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
        // The one place execution evidence enters this client. INVARIANT I-1: a reply that claims
        // an action happened is released only once a result with ok=true has arrived.
        onExecutionResult(output)
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
        // S2 of SPEC-006: a referent does not survive the session that produced it. 「再低一点」 after
        // a reconnect must be asked about, not answered from what the driver said before.
        driverContext.onSessionEnded()
        DriverContext.clear()
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
            // Baidu's wire vocabulary stops here. Everything downstream is policy, and policy does
            // not get to know what this provider calls a tool call (ADR-009).
            val outcome = toOutcome(kinds, outputs)
            // A turn that asked for an action is real by definition; only actionless turns can be
            // phantoms. Decided here, where the outputs are already parsed.
            finishResponse(hadToolCall = outcome.requestedTool)
            if (resetPolicy.onResponseDone(outcome)) resetConversation()
            val spoken = assistantText.toString()
            assistantText.setLength(0)
            // DriverTurn may already have corrected this response when it dropped the reply. Two
            // corrections mean the model is told the same thing twice: measured on device
            // 2026-09-19, 「算了」 produced two identical follow-ups and `exit_navigation_mode` ran
            // twice. It is idempotent and nothing broke; `adjust_temperature{-2}` twice is -4 degC
            // and would have, were it not for the dispatcher's own duplicate guard. One owner.
            val alreadyCorrected = correctionSentThisResponse
            correctionSentThisResponse = false
            actionGuard.onResponseDone(outcome, spoken)?.takeIf { !listeningSuspended && !alreadyCorrected }?.let { nudge ->
                // Which follow-up, not just that there was one: "we did not catch that" and
                // "which control did you mean" are different product behaviours, and a suite that
                // cannot tell them apart passes S16 either way.
                val kind = when (nudge) {
                    ActionClaimGuard.CLARIFY_REFERENT -> "clarify"
                    ActionClaimGuard.UNVERIFIED_ACTION_CLAIM -> "unheard"
                    else -> "perform"
                }
                DebugVoiceLog.log("flex_action_claim_unverified follow_up=true kind=$kind")
                Telemetry.record(EventType.GUARD_FOLLOW_UP)
                sendUserText(nudge)
            }
            emptyRetry.onResponseDone(status, kinds.size)
        }.getOrDefault(false)

    /**
     * Baidu's `output[].type` list translated into the neutral [ResponseOutcome].
     *
     * `"function_call"` and `"message"` are this vendor's words. A provider that called them
     * something else would translate here and every policy downstream would be unaffected, which
     * is the entire point of the boundary.
     */
    private fun toOutcome(kinds: List<String>, outputs: org.json.JSONArray?): ResponseOutcome {
        val callIds = (0 until (outputs?.length() ?: 0)).mapNotNull { i ->
            outputs!!.optJSONObject(i)
                ?.takeIf { it.optString("type") == FLEX_FUNCTION_CALL }
                ?.optString("call_id")
                ?.takeIf { it.isNotEmpty() }
        }
        val calls = kinds.count { it == FLEX_FUNCTION_CALL }
        return ResponseOutcome(
            spoke = kinds.any { it == FLEX_MESSAGE },
            toolCallIds = callIds,
            unidentifiedToolCalls = (calls - callIds.size).coerceAtLeast(0),
        )
    }

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
            if (type == "session.updated") {
                val echoed = JSONObject(text).optJSONObject("session")?.optString("voice").orEmpty()
                confirmedVoice = echoed.ifBlank { sentVoice }
                DebugVoiceLog.log(
                    "flex_voice requested=$requestedVoice confirmed=$confirmedVoice " +
                        "match=${confirmedVoice == requestedVoice} fallback=$voiceFallbackUsed",
                )
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
                onResponseCreated()
            }
            if (type == "response.audio_transcript.done" || type == "response.text.done") {
                val raw = JSONObject(text)
                assistantText.append(raw.optString("transcript").ifEmpty { raw.optString("text") })
                onAssistantText(assistantText.toString())
            }
            if (type == "conversation.item.input_audio_transcription.completed") {
                val transcript = JSONObject(text).optString("transcript")
                actionGuard.onUserTranscript(transcript)
                onUserTranscript(transcript)
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
                // Fail-safe: finishResponse clears the buffer on either verdict, so this only
                // fires when the parse above returned early. Better a spoken reply than audio
                // stranded in a list.
                applyVerdict(turn, DriverTurn.Verdict.Release("response_done_fallback"))
            }
            events.forEach { event ->
                if (dropDuplicateCall(event)) return@forEach
                // A tool call proves the driver's turn was real; never let one sit behind a hold,
                // and remember it so the spoken result of the action is not judged on its own.
                if (event is DomainVoiceEvent.ToolCall) onToolCallDispatched()
                if (holdOrEmit(event)) return@forEach
                if (event is DomainVoiceEvent.Error && !pending.isCompleted) {
                    if (!voiceFallbackUsed && sentVoice != BaiduAppSettings.FALLBACK_VOICE) {
                        voiceFallbackUsed = true
                        sentVoice = BaiduAppSettings.FALLBACK_VOICE
                        webSocket.send(BaiduFlexProtocol.sessionUpdate(instructionsWithContext(), BaiduAppSettings.FALLBACK_VOICE, speed, vadThreshold))
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
        if (!trySend(message)) throw VoiceProviderException(
            "BAIDU_FLEX_CONNECTION_CLOSED", "Baidu Flex WebSocket is not connected",
        )
    }

    // ---- per-turn output gate ---------------------------------------------
    //
    // One object owns everything known about the driver's current utterance and decides whether
    // the reply may be heard yet: DriverTurn. The rule it enforces is
    // docs/INVARIANTS.md I-1 — only deterministic execution evidence may establish that an
    // external action occurred. Reply audio and its subtitle wait for that evidence; the
    // transcript, the tool call, the execution and any error never wait for anything.

    @Volatile
    private var turn: DriverTurn = DriverTurn(0)

    private val turnEpoch = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * Cross-turn context, owned here because the driver turn is owned here. Installed so the tool
     * dispatcher and [VoiceContextHints] read this record rather than each keeping their own.
     */
    private val driverContext = DriverContext().also { DriverContext.install(it) }

    /**
     * A new **driver** turn, not a new response. One command produces several responses — the tool
     * call, then the spoken result — and the transcript belongs to the utterance, not the response.
     */
    @Synchronized
    private fun beginDriverTurn() {
        val previous = turn
        if (previous.isHolding || previous.heldCount > 0) {
            // A superseded turn's output is discarded: it can no longer be true or timely, and
            // nothing it produced may be referred to by the utterance that replaced it (S5).
            applyVerdict(previous, previous.cancel("superseded"))
            driverContext.cancel(previous.epoch)
        }
        turn = DriverTurn(turnEpoch.incrementAndGet())
    }

    @Synchronized
    private fun onResponseCreated() {
        val reason = turn.onResponseStarted(lastAudioSegment(), contextAwaitingAnswer())
        if (reason != DriverTurn.HoldReason.NONE) {
            DebugVoiceLog.log(
                "TURN_HOLD epoch=${turn.epoch} reason=$reason kind=${turn.kind} " +
                    "durationMs=${turn.audio?.durationMs ?: -1}",
            )
        }
    }

    @Synchronized
    private fun onUserTranscript(text: String) {
        val before = turn.isHolding
        val reason = turn.onUserTranscript(text) { DriverTurn.classify(it) }
        if (turn.userSpoke) driverContext.onDriverUtterance(text, turn.epoch)
        if (before && reason == DriverTurn.HoldReason.NONE) {
            applyVerdict(turn, DriverTurn.Verdict.Release("user_spoke"))
        } else if (!before && reason != DriverTurn.HoldReason.NONE) {
            DebugVoiceLog.log("TURN_HOLD epoch=${turn.epoch} reason=$reason kind=${turn.kind}")
        }
    }

    /**
     * Execution evidence. This is the only path in the client by which an external action can be
     * shown to have happened; a `ok=false` result explicitly does not release a claim.
     */
    @Synchronized
    private fun onExecutionResult(output: String) {
        val ok = output.contains("\"ok\":true")
        val failure = if (ok) null else Regex("\"error\":\"([^\"]+)\"").find(output)?.groupValues?.get(1)
        applyVerdict(turn, turn.onExecutionResult(ok, failure))
    }

    @Synchronized
    private fun onAssistantText(text: String) {
        applyVerdict(turn, turn.onAssistantText(text))
    }

    @Synchronized
    private fun onToolCallDispatched() {
        turn.onToolCall()
    }

    @Synchronized
    private fun finishResponse(hadToolCall: Boolean) {
        applyVerdict(turn, turn.onResponseDone(assistantText.toString(), hadToolCall))
    }

    /** Held output is audio and its subtitle only — never a tool call, an error or a transcript. */
    @Synchronized
    private fun holdOrEmit(event: DomainVoiceEvent): Boolean {
        if (!turn.isHolding) return false
        val holdable = event is DomainVoiceEvent.AudioDelta ||
            event is DomainVoiceEvent.AudioDone ||
            event is DomainVoiceEvent.AssistantTranscript
        if (!holdable) return false
        turn.hold(event)
        if (turn.heldCount > MAX_HELD_AUDIO_EVENTS) {
            applyVerdict(turn, turn.onHoldBudgetExceeded())
        }
        return true
    }

    private fun applyVerdict(target: DriverTurn, verdict: DriverTurn.Verdict) {
        when (verdict) {
            DriverTurn.Verdict.Wait -> Unit
            is DriverTurn.Verdict.Release -> {
                val pending = target.takeHeld()
                if (pending.isNotEmpty()) {
                    DebugVoiceLog.log(
                        "TURN_RELEASE epoch=${target.epoch} reason=${verdict.reason} events=${pending.size}",
                    )
                    pending.forEach { emit(it as DomainVoiceEvent) }
                }
            }
            is DriverTurn.Verdict.Drop -> {
                val dropped = target.takeHeld().size
                DebugVoiceLog.log(
                    "TURN_DROP epoch=${target.epoch} reason=${verdict.reason} kind=${target.kind} " +
                        "proven=${target.proven} events=$dropped replyChars=${assistantText.length}",
                )
                Telemetry.record(EventType.AUDIO_STOPPED, detail = "turn_dropped_${verdict.reason}")
                // In standby the client sends no turns of its own (see discardPendingAudio): the
                // dropped reply was never going to be played, so there is nothing to correct.
                verdict.correction?.takeIf { !listeningSuspended }?.let {
                    correctionSentThisResponse = true
                    sendUserText(it)
                }
            }
        }
    }

    /** The socket went away: held output can never be played, so it is discarded. */
    @Synchronized
    private fun dropHeldAudio(reason: String) {
        applyVerdict(turn, turn.cancel(reason))
    }

    private fun emit(event: DomainVoiceEvent) {
        eventFlow.tryEmit(RealtimeEvent(SystemSessionClock.nowMs(), event))
    }

    companion object {
        /**
         * Baidu's own `output[].type` values. The only place in the app these strings may appear:
         * everything downstream takes a [ResponseOutcome] instead (ADR-009).
         */
        private const val FLEX_FUNCTION_CALL = "function_call"
        private const val FLEX_MESSAGE = "message"

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
