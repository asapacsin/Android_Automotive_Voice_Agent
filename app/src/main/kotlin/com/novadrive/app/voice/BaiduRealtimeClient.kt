package com.novadrive.app.voice

import com.novadrive.app.BaiduApiConfig
import com.novadrive.app.BaiduAuthMode
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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLException

class BaiduRealtimeClient(
    private val http: OkHttpClient = defaultHttp(),
    private val readyTimeoutMs: Long = 10_000,
    private val requireTls: Boolean = true,
    private val tokenClient: BaiduAccessTokenClient = BaiduAccessTokenClient(http),
) {
    private val eventFlow = MutableSharedFlow<RealtimeEvent>(replay = 0, extraBufferCapacity = 64)
    private val generation = AtomicLong(0)
    @Volatile private var socket: WebSocket? = null
    @Volatile private var ready: CompletableDeferred<Unit>? = null
    @Volatile private var assistantSpeaking = false

    fun events(): Flow<RealtimeEvent> = eventFlow.asSharedFlow()

    suspend fun connect(config: BaiduApiConfig) {
        disconnect()
        val settingsForValidation = if (requireTls) config.settings else config.settings.copy(
            endpoint = config.settings.endpoint.replaceFirst("ws://", "wss://"),
            tokenEndpoint = config.settings.tokenEndpoint.replaceFirst("http://", "https://"),
        )
        val error = com.novadrive.app.BaiduSettingsValidator.validate(settingsForValidation, config.credentials)
        if (error != null) throw VoiceProviderException(error, error)
        val token = if (config.settings.authMode == BaiduAuthMode.LEGACY_ACCESS_TOKEN) {
            tokenClient.getToken(config.settings.tokenEndpoint, config.credentials)
        } else null
        val endpoint = buildUrl(config.settings.endpoint, config.settings.model, token, requireTls)
        val currentGeneration = generation.incrementAndGet()
        val pending = CompletableDeferred<Unit>()
        ready = pending
        assistantSpeaking = false
        val builder = Request.Builder().url(endpoint)
        if (config.settings.authMode == BaiduAuthMode.BEARER_API_KEY) {
            builder.header("Authorization", "Bearer ${config.credentials.apiKey}")
        }
        socket = http.newWebSocket(
            builder.build(),
            listener(currentGeneration, pending, config.settings.instructions, config.settings.voice, config.settings.speed),
        )
        try {
            withTimeout(readyTimeoutMs) { pending.await() }
        } catch (_: TimeoutCancellationException) {
            disconnect()
            throw VoiceProviderException("BAIDU_TIMEOUT", "Baidu session readiness timed out")
        } catch (failure: VoiceProviderException) {
            disconnect()
            throw failure
        }
    }

    fun sendAudio(pcm16le: ByteArray) {
        val audio = Base64.getEncoder().encodeToString(pcm16le)
        if (socket?.send(BaiduProtocol.audioAppend(audio)) != true) {
            emit(DomainVoiceEvent.Error("BAIDU_CONNECTION_CLOSED", "Baidu WebSocket is not connected"))
        }
    }

    fun disconnect() {
        generation.incrementAndGet()
        ready?.cancel()
        ready = null
        assistantSpeaking = false
        val existing = socket
        socket = null
        existing?.close(1000, "client close")
    }

    fun close() = disconnect()

    private fun listener(
        currentGeneration: Long,
        pending: CompletableDeferred<Unit>,
        instructions: String,
        voice: String,
        speed: Double,
    ) =
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isCurrent(currentGeneration)) return
                if (!webSocket.send(BaiduProtocol.sessionUpdate(PersonaProfiles.sanitize(instructions), voice, speed))) {
                    pending.completeExceptionally(VoiceProviderException("BAIDU_SESSION_FAILED", "failed to configure Baidu session"))
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!isCurrent(currentGeneration)) return
                val type = runCatching { BaiduProtocol.eventType(text) }.getOrElse {
                    failProtocol(pending, it)
                    return
                }
                val events = runCatching { BaiduProtocol.parseServerEvent(text, assistantSpeaking) }.getOrElse {
                    failProtocol(pending, it)
                    return
                }
                if (type == "session.updated") pending.complete(Unit)
                if (type == "response.audio.delta") assistantSpeaking = true
                if (type == "response.audio.done" || type == "response.done") assistantSpeaking = false
                events.forEach { event ->
                    if (event is DomainVoiceEvent.Error && !pending.isCompleted) {
                        pending.completeExceptionally(VoiceProviderException(event.code, event.message))
                    }
                    emit(event)
                }
            }

            override fun onFailure(webSocket: WebSocket, failure: Throwable, response: Response?) {
                if (!isCurrent(currentGeneration)) return
                val mapped = mapFailure(failure, response)
                if (!pending.completeExceptionally(mapped)) emit(DomainVoiceEvent.Error(mapped.code, mapped.safeMessage))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!isCurrent(currentGeneration)) return
                val failure = VoiceProviderException("BAIDU_CONNECTION_CLOSED", "Baidu WebSocket closed (status=$code)")
                if (!pending.completeExceptionally(failure)) {
                    emit(DomainVoiceEvent.Error(failure.code, failure.safeMessage))
                    emit(DomainVoiceEvent.Closed)
                }
            }

            private fun failProtocol(pending: CompletableDeferred<Unit>, failure: Throwable) {
                val mapped = VoiceProviderException("BAIDU_PROTOCOL_ERROR", "invalid Baidu realtime event", failure)
                if (!pending.completeExceptionally(mapped)) emit(DomainVoiceEvent.Error(mapped.code, mapped.safeMessage))
            }
        }

    private fun emit(event: DomainVoiceEvent) {
        eventFlow.tryEmit(RealtimeEvent(SystemSessionClock.nowMs(), event))
    }

    private fun isCurrent(value: Long): Boolean = generation.get() == value

    companion object {
        private fun defaultHttp() = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()

        fun buildUrl(endpoint: String, model: String, accessToken: String? = null): String =
            buildUrl(endpoint, model, accessToken, requireTls = true)

        internal fun buildUrl(endpoint: String, model: String, accessToken: String?, requireTls: Boolean): String {
            val trimmed = endpoint.trim()
            val secure = trimmed.startsWith("wss://", true)
            val testSocket = trimmed.startsWith("ws://", true)
            if (requireTls && !secure) throw VoiceProviderException("BAIDU_TLS_REQUIRED", "Baidu Direct requires a wss:// endpoint")
            if (!secure && !testSocket) throw VoiceProviderException("BAIDU_ENDPOINT_INVALID", "invalid Baidu WebSocket endpoint")
            val httpScheme = if (secure) "https://" else "http://"
            val parsed = (httpScheme + trimmed.substringAfter("://")).toHttpUrlOrNull()
                ?: throw VoiceProviderException("BAIDU_ENDPOINT_INVALID", "invalid Baidu WebSocket endpoint")
            val result = parsed.newBuilder().setQueryParameter("model", model.trim()).apply {
                if (!accessToken.isNullOrBlank()) setQueryParameter("access_token", accessToken)
            }.build().toString()
            return if (secure) "wss://${result.removePrefix("https://")}" else "ws://${result.removePrefix("http://")}" 
        }

        fun mapFailure(failure: Throwable, response: Response?): VoiceProviderException {
            val status = response?.code
            val code = when {
                status == 401 || status == 403 -> "BAIDU_AUTH_FAILED"
                status == 404 -> "BAIDU_INVALID_MODEL"
                status == 429 -> "BAIDU_QUOTA_EXHAUSTED"
                failure.hasCause<UnknownHostException>() -> "BAIDU_DNS_FAILED"
                failure.hasCause<SSLException>() -> "BAIDU_TLS_FAILED"
                failure.hasCause<SocketTimeoutException>() -> "BAIDU_TIMEOUT"
                else -> "BAIDU_CONNECTION_FAILED"
            }
            val detail = if (status != null) "$code (HTTP $status)" else "$code: ${failure.javaClass.simpleName}"
            return VoiceProviderException(code, detail, failure)
        }

        private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
            var cursor: Throwable? = this
            while (cursor != null) {
                if (cursor is T) return true
                cursor = cursor.cause
            }
            return false
        }
    }
}
