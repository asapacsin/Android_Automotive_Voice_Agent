package com.novadrive.app.voice

import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.novadrive.ingress.realtime.DomainVoiceEvent
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class BackendVoiceClient(
    onEvent: (DomainVoiceEvent) -> Unit,
    private val onStateLabel: (String) -> Unit,
    private val onRawError: (String, String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var listener: (DomainVoiceEvent) -> Unit = onEvent
    private val http =
        OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    private var socket: WebSocket? = null

    fun replaceListener(next: (DomainVoiceEvent) -> Unit) {
        listener = next
    }

    fun connect(backendHttpUrl: String, model: String, provider: String? = null) {
        close()
        val wsUrl = toWebSocketUrl(backendHttpUrl)
        val request = Request.Builder().url(wsUrl).build()
        socket =
            http.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        val body =
                            JSONObject()
                                .put("type", "start")
                                .put("model", model)
                        if (!provider.isNullOrBlank()) {
                            body.put("provider", provider)
                        }
                        webSocket.send(body.toString())
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        main.post { dispatch(text) }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        main.post {
                            onRawError("BACKEND_WS_FAILED", "backend unreachable")
                            listener(DomainVoiceEvent.Error("BACKEND_WS_FAILED", "backend unreachable"))
                        }
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        main.post { listener(DomainVoiceEvent.Closed) }
                    }
                },
            )
    }

    fun sendAudio(pcm16le: ByteArray) {
        val audio = Base64.encodeToString(pcm16le, Base64.NO_WRAP)
        socket?.send(JSONObject().put("type", "audio").put("audio", audio).toString())
    }

    fun interrupt() {
        socket?.send(JSONObject().put("type", "interrupt").toString())
    }

    fun commit() {
        socket?.send(JSONObject().put("type", "commit").toString())
    }

    fun sendWorkResult(callId: String, ok: Boolean, output: String) {
        socket?.send(
            JSONObject()
                .put("type", "work_result")
                .put("call_id", callId)
                .put("ok", ok)
                .put("output", output)
                .toString(),
        )
    }

    fun stopSession() {
        socket?.send(JSONObject().put("type", "stop").toString())
        close()
    }

    fun close() {
        socket?.close(1000, "client close")
        socket = null
    }

    private fun dispatch(text: String) {
        val json = JSONObject(text)
        when (json.optString("type")) {
            "state" -> onStateLabel(json.optString("state"))
            "error" -> {
                val code = json.optString("code", "PROVIDER_REJECTED")
                val message = json.optString("message", "request rejected")
                onRawError(code, message)
                listener(DomainVoiceEvent.Error(code, message))
            }
            "session_created", "session_updated" ->
                listener(
                    DomainVoiceEvent.SessionReady(
                        model = json.optString("model"),
                        interruptResponse = json.optBoolean("interrupt_response", true),
                    ),
                )
            "speech_started" -> listener(DomainVoiceEvent.SpeechStarted)
            "speech_stopped" -> listener(DomainVoiceEvent.SpeechStopped)
            "user_transcript" ->
                listener(DomainVoiceEvent.UserTranscript(json.optString("text"), json.optBoolean("final")))
            "assistant_transcript" ->
                listener(
                    DomainVoiceEvent.AssistantTranscript(json.optString("text"), json.optBoolean("final")),
                )
            "audio_delta" -> listener(DomainVoiceEvent.AudioDelta(json.optString("audio")))
            "audio_done" -> listener(DomainVoiceEvent.AudioDone)
            "response_done" ->
                listener(
                    DomainVoiceEvent.ResponseDone(json.optString("status"), json.optString("reason").ifBlank { null }),
                )
            "interrupted" -> listener(DomainVoiceEvent.Interrupted(json.optString("reason")))
            "closed" -> listener(DomainVoiceEvent.Closed)
            "tool_unsupported" ->
                listener(DomainVoiceEvent.ToolUnsupported(json.optString("reason")))
            "tool_call" ->
                listener(
                    DomainVoiceEvent.ToolCall(
                        json.optString("call_id"),
                        json.optString("name"),
                        emptyMap(),
                    ),
                )
            "work_progress" ->
                listener(DomainVoiceEvent.WorkProgress(json.optString("work_id"), json.optString("message")))
            "work_result" ->
                listener(DomainVoiceEvent.WorkResult(json.optString("work_id"), json.optString("output")))
            "reconnecting" -> listener(DomainVoiceEvent.Reconnecting)
        }
    }

    companion object {
        fun toWebSocketUrl(backendHttpUrl: String): String {
            val trimmed = backendHttpUrl.trim().trimEnd('/')
            val swapped =
                when {
                    trimmed.startsWith("https://") -> "wss://" + trimmed.removePrefix("https://")
                    trimmed.startsWith("http://") -> "ws://" + trimmed.removePrefix("http://")
                    trimmed.startsWith("ws://") || trimmed.startsWith("wss://") -> trimmed
                    else -> "ws://$trimmed"
                }
            return "$swapped/v1/voice/realtime"
        }
    }
}
