package com.novadrive.app.voice

import com.novadrive.app.BaiduApiConfig
import com.novadrive.app.BaiduAppSettings
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

    fun events(): Flow<RealtimeEvent> = eventFlow.asSharedFlow()

    suspend fun connect(config: BaiduApiConfig) {
        disconnect()
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
            disconnect()
            throw VoiceProviderException("BAIDU_FLEX_TIMEOUT", "Baidu Flex session readiness timed out")
        } catch (failure: VoiceProviderException) {
            disconnect()
            throw failure
        }
    }

    fun sendAudio(pcm16le: ByteArray) {
        if (!trySend(BaiduFlexProtocol.audioAppend(Base64.getEncoder().encodeToString(pcm16le)))) {
            emit(DomainVoiceEvent.Error("BAIDU_FLEX_CONNECTION_CLOSED", "Baidu Flex WebSocket is not connected"))
        }
    }

    fun cancelResponse() {
        if (assistantSpeaking) trySend(BaiduFlexProtocol.responseCancel())
    }

    fun sendFunctionResult(callId: String, output: String) {
        sendControl(BaiduFlexProtocol.functionCallOutput(callId, output))
        sendControl(BaiduFlexProtocol.responseCreate())
    }

    fun disconnect() {
        generation.incrementAndGet()
        ready?.cancel(); ready = null
        sessionCreated = false
        assistantSpeaking = false
        assembler.clear()
        val owned = navigatingListener
        navigatingListener = null
        releaseNavigatingListenerIfOwned(owned)
        val existing = socket; socket = null
        existing?.close(1000, "client close")
    }

    fun close() = disconnect()

    private fun listener(current: Long, pending: CompletableDeferred<Unit>) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = Unit

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (generation.get() != current) return
            val type = runCatching { JSONObject(text).optString("type") }.getOrElse {
                failProtocol(pending, it); return
            }
            if (type == "session.created") {
                sessionCreated = true
                if (!webSocket.send(BaiduFlexProtocol.sessionUpdate(instructions, voice, speed, vadThreshold))) {
                    pending.completeExceptionally(VoiceProviderException("BAIDU_FLEX_SESSION_FAILED", "failed to configure Baidu Flex session"))
                }
            }
            val events = runCatching {
                assembler.consume(text) + BaiduFlexProtocol.parseCommonEvent(text, assistantSpeaking)
            }.getOrElse { failProtocol(pending, it); return }
            if (type == "session.updated" && sessionCreated) pending.complete(Unit)
            if (type == "response.audio.delta") assistantSpeaking = true
            if (type == "response.audio.done" || type == "response.done") assistantSpeaking = false
            events.forEach { event ->
                if (event is DomainVoiceEvent.Error && !pending.isCompleted) {
                    if (!voiceFallbackUsed && sentVoice != BaiduAppSettings.DEFAULT_VOICE) {
                        voiceFallbackUsed = true
                        sentVoice = BaiduAppSettings.DEFAULT_VOICE
                        webSocket.send(BaiduFlexProtocol.sessionUpdate(instructions, BaiduAppSettings.DEFAULT_VOICE, speed, vadThreshold))
                        return
                    }
                    pending.completeExceptionally(VoiceProviderException(event.code, event.message))
                }
                emit(event)
            }
        }

        override fun onFailure(webSocket: WebSocket, failure: Throwable, response: Response?) {
            if (generation.get() != current) return
            val mapped = mapFailure(failure, response)
            if (!pending.completeExceptionally(mapped)) emit(DomainVoiceEvent.Error(mapped.code, mapped.safeMessage))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (generation.get() != current) return
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

    private fun trySend(message: String): Boolean = socket?.send(message) == true

    private fun sendControl(message: String) {
        if (!trySend(message)) throw VoiceProviderException(
            "BAIDU_FLEX_CONNECTION_CLOSED", "Baidu Flex WebSocket is not connected",
        )
    }

    private fun emit(event: DomainVoiceEvent) {
        eventFlow.tryEmit(RealtimeEvent(SystemSessionClock.nowMs(), event))
    }

    companion object {
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
