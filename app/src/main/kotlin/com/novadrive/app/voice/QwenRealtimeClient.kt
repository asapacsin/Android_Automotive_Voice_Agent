package com.novadrive.app.voice

import com.novadrive.app.QwenApiConfig
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

class QwenRealtimeClient(
    private val http: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build(),
    private val readyTimeoutMs: Long = 10_000,
    private val requireTls: Boolean = true,
) {
    private val eventFlow = MutableSharedFlow<RealtimeEvent>(replay = 16, extraBufferCapacity = 64)
    private val generation = AtomicLong(0)
    @Volatile private var socket: WebSocket? = null
    @Volatile private var ready: CompletableDeferred<Unit>? = null

    fun events(): Flow<RealtimeEvent> = eventFlow.asSharedFlow()

    suspend fun connect(config: QwenApiConfig) {
        disconnect()
        val currentGeneration = generation.incrementAndGet()
        val endpoint = buildUrl(config.endpoint, config.model, requireTls)
        val pending = CompletableDeferred<Unit>()
        ready = pending
        val request =
            Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer ${config.apiKey}")
                .build()
        socket =
            http.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        if (!isCurrent(currentGeneration)) return
                        if (!webSocket.send(QwenProtocol.sessionUpdate())) {
                            failPending(currentGeneration, VoiceProviderException("QWEN_SESSION_FAILED", "failed to configure Qwen session"))
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (!isCurrent(currentGeneration)) return
                        val events =
                            runCatching { QwenProtocol.parseServerEvent(text) }.getOrElse { error ->
                                val failure = VoiceProviderException("QWEN_PROTOCOL_ERROR", "invalid Qwen realtime event", error)
                                failPending(currentGeneration, failure)
                                emit(DomainVoiceEvent.Error(failure.code, failure.safeMessage))
                                return
                            }
                        events.forEach { event ->
                            if (event is DomainVoiceEvent.SessionReady) pending.complete(Unit)
                            if (event is DomainVoiceEvent.Error && !pending.isCompleted) {
                                pending.completeExceptionally(VoiceProviderException(event.code, event.message))
                            }
                            emit(event)
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, failure: Throwable, response: Response?) {
                        if (!isCurrent(currentGeneration)) return
                        val mapped = mapFailure(failure, response)
                        if (!pending.completeExceptionally(mapped)) {
                            emit(DomainVoiceEvent.Error(mapped.code, mapped.safeMessage))
                        }
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (!isCurrent(currentGeneration)) return
                        val message = "Qwen WebSocket closed (status=$code)"
                        val failure = VoiceProviderException("QWEN_CONNECTION_CLOSED", message)
                        if (!pending.completeExceptionally(failure)) {
                            emit(DomainVoiceEvent.Error(failure.code, failure.safeMessage))
                            emit(DomainVoiceEvent.Closed)
                        }
                    }
                },
            )
        try {
            withTimeout(readyTimeoutMs) { pending.await() }
        } catch (_: TimeoutCancellationException) {
            disconnect()
            throw VoiceProviderException("QWEN_TIMEOUT", "Qwen session readiness timed out")
        } catch (failure: VoiceProviderException) {
            disconnect()
            throw failure
        }
    }

    fun sendAudio(pcm16le: ByteArray) {
        val audio = Base64.getEncoder().encodeToString(pcm16le)
        if (socket?.send(QwenProtocol.audioAppend(audio)) != true) {
            emit(DomainVoiceEvent.Error("QWEN_CONNECTION_CLOSED", "Qwen WebSocket is not connected"))
        }
    }

    fun commit() = sendControl(QwenProtocol.audioCommit())

    fun cancel() = sendControl(QwenProtocol.responseCancel())

    fun sendWorkResult(callId: String, output: String) {
        sendControl(QwenProtocol.functionCallOutput(callId, output))
        sendControl(QwenProtocol.responseCreate())
    }

    fun disconnect() {
        generation.incrementAndGet()
        ready?.cancel()
        ready = null
        val existing = socket
        socket = null
        existing?.close(1000, "client close")
    }

    fun close() = disconnect()

    private fun sendControl(message: String) {
        if (socket?.send(message) != true) {
            throw VoiceProviderException("QWEN_CONNECTION_CLOSED", "Qwen WebSocket is not connected")
        }
    }

    private fun emit(event: DomainVoiceEvent) {
        eventFlow.tryEmit(RealtimeEvent(SystemSessionClock.nowMs(), event))
    }

    private fun isCurrent(value: Long): Boolean = generation.get() == value

    private fun failPending(value: Long, failure: VoiceProviderException) {
        if (isCurrent(value)) ready?.completeExceptionally(failure)
    }

    companion object {
        fun buildUrl(endpoint: String, model: String): String {
            return buildUrl(endpoint, model, requireTls = true)
        }

        internal fun buildUrl(endpoint: String, model: String, requireTls: Boolean): String {
            val trimmed = endpoint.trim()
            val isSecure = trimmed.startsWith("wss://", ignoreCase = true)
            val isTestSocket = trimmed.startsWith("ws://", ignoreCase = true)
            if (requireTls && !isSecure) {
                throw VoiceProviderException("QWEN_TLS_REQUIRED", "Qwen Direct requires a wss:// endpoint")
            }
            if (!isSecure && !isTestSocket) {
                throw VoiceProviderException("QWEN_ENDPOINT_INVALID", "invalid Qwen WebSocket endpoint")
            }
            val httpScheme = if (isSecure) "https://" else "http://"
            val parsed = (httpScheme + trimmed.substringAfter("://")).toHttpUrlOrNull()
                ?: throw VoiceProviderException("QWEN_ENDPOINT_INVALID", "invalid Qwen WebSocket endpoint")
            val httpUrl = parsed.newBuilder().setQueryParameter("model", model.trim()).build().toString()
            return if (isSecure) "wss://" + httpUrl.removePrefix("https://") else "ws://" + httpUrl.removePrefix("http://")
        }

        fun mapFailure(failure: Throwable, response: Response?): VoiceProviderException {
            val status = response?.code
            val code =
                when {
                    status == 401 || status == 403 -> "QWEN_AUTH_FAILED"
                    status == 404 -> "QWEN_MODEL_NOT_FOUND"
                    status == 429 -> "QWEN_RATE_LIMITED"
                    failure.hasCause<UnknownHostException>() -> "QWEN_DNS_FAILED"
                    failure.hasCause<SSLException>() -> "QWEN_TLS_FAILED"
                    failure.hasCause<SocketTimeoutException>() -> "QWEN_TIMEOUT"
                    else -> "QWEN_CONNECTION_FAILED"
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
