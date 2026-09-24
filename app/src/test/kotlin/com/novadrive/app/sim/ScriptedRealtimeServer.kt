package com.novadrive.app.sim

import com.novadrive.app.voice.PcmAudioCapture
import com.novadrive.evaluation.ModelBehavior
import com.novadrive.evaluation.ToolCallSpec
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * A scripted stand-in for Baidu Qianfan realtime Flex, speaking the same event protocol the real
 * client handles (session.created/updated, VAD events, transcription, function calls, audio,
 * response.done, errors). The "model" is deterministic: it emits the calls the scenario scripts,
 * answers tool results from their `ok` field, and can be told to misbehave.
 *
 * Assumptions about the real service that are NOT verified (see docs/EVALUATION.md):
 * - a function_call_output whose call_id belongs to an earlier connection is ignored silently;
 * - a response.create while a response is running is refused with the measured error text;
 * - with interrupt_response, speech during a reply cancels it with status "cancelled".
 */
class ScriptedRealtimeServer(seed: Long) {
    data class Turn(
        val utterance: String,
        val calls: List<ToolCallSpec>,
        val behavior: ModelBehavior,
        val bargeIn: Boolean = false,
        /** A long spoken reply of this length (a primer the driver will interrupt); 0 = normal. */
        val longReplyMs: Long = 0,
    )

    private val random = Random(seed)
    private val out = Executors.newSingleThreadExecutor { r -> Thread(r, "scripted-server").apply { isDaemon = true } }
    private val lock = Any()
    private val ids = AtomicInteger()
    val server = MockWebServer()

    // Faults
    @Volatile var latencyMs = 0L
    @Volatile var rejectNextConnect = false
    @Volatile var disconnectNextTurn: Boolean? = null // null: none; false: before the tool; true: after
    @Volatile var errorOnNextTurn = false
    @Volatile var duplicateToolEvent = false

    // Observable state
    val connections = AtomicInteger()
    @Volatile var lastInstructions: String = ""
        private set

    private var socket: WebSocket? = null
    private var turn: Turn? = null
    private var responseActive = false
    private var cancelledResponse = false
    private var emptyAnswered = false
    private var ignoreErrors = false
    private val openCalls = mutableMapOf<String, String>() // call_id -> tool name
    private val outputs = mutableListOf<Pair<String, JSONObject>>() // tool name -> output
    private val pendingUserText = mutableListOf<String>()
    private val pendingEmissions = AtomicInteger()
    private var callsEmitted = false

    private val responsesDone = AtomicInteger()
    private val latencySlept = java.util.concurrent.atomic.AtomicLong()

    /**
     * The server's side of "settled": no response running, nothing scheduled or streaming, no tool
     * result owed. This alone is NOT enough — frames are delivered asynchronously — so the driver
     * also waits for the client to have recorded [responsesCompleted] completions.
     */
    val idle: Boolean
        get() = synchronized(lock) { !responseActive && openCalls.isEmpty() } && pendingEmissions.get() == 0

    /** response.done events sent on live connections (including cancelled replies). */
    val responsesCompleted: Int get() = responsesDone.get()

    /** Deliberate latency applied so far. */
    val latencySleptMs: Long get() = latencySlept.get()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (rejectNextConnect) {
                    rejectNextConnect = false
                    return MockResponse().setResponseCode(503)
                }
                return MockResponse().withWebSocketUpgrade(Listener())
            }
        }
        server.start()
    }

    fun endpoint(): String = server.url("/ws/2.0/speech/v1/realtime").toString().replace("http://", "ws://")

    fun shutdown() {
        runCatching { socket?.close(1000, "done") }
        runCatching { server.shutdown() }
        out.shutdownNow()
    }

    /**
     * Drops the connection. MockWebServer's server-side socket cannot be cancelled (NPE inside
     * OkHttp), so this is an abrupt 1011 close; the client sees the connection end mid-session.
     */
    fun dropConnection() {
        synchronized(lock) {
            runCatching { socket?.close(1011, "simulated network drop") }
            socket = null
            responseActive = false
        }
    }

    // ---- driver entry -----------------------------------------------------------------------

    /** The driver speaks. Emits VAD, transcription and the model's first response. */
    fun userSays(next: Turn) {
        schedule {
            synchronized(lock) {
                turn = next
                emptyAnswered = false
                callsEmitted = false
                ignoreErrors = next.behavior == ModelBehavior.IGNORE_TOOL_ERROR
                outputs.clear()
            }
            if (next.bargeIn) {
                // Baidu hears the driver over its own reply and cancels it.
                send("""{"type":"input_audio_buffer.speech_started"}""")
                val wasActive = synchronized(lock) {
                    val a = responseActive
                    if (a) {
                        cancelledResponse = true
                        responseActive = false
                    }
                    a
                }
                if (wasActive) {
                    responsesDone.incrementAndGet()
                    send(JSONObject().put("type", "response.done").put("response", JSONObject()
                        .put("status", "cancelled").put("status_details", JSONObject().put("reason", "turn_detected"))
                        .put("output", JSONArray())).toString())
                }
            } else {
                send("""{"type":"input_audio_buffer.speech_started"}""")
            }
            // Just enough speech duration to keep VAD events ordered and interleavable.
            pause(3 + random.nextLong(5))
            send("""{"type":"input_audio_buffer.speech_stopped"}""")
            if (disconnectNextTurn == false) {
                disconnectNextTurn = null
                pause(5)
                dropConnection()
                return@schedule
            }
            send("""{"type":"input_audio_buffer.committed"}""")
            if (errorOnNextTurn) {
                errorOnNextTurn = false
                send("""{"type":"error","error":{"code":"server_error","message":"The server had an error while processing your request."}}""")
                return@schedule
            }
            startResponse()
            send(JSONObject().put("type", "conversation.item.input_audio_transcription.completed")
                .put("item_id", "item_${ids.incrementAndGet()}").put("transcript", next.utterance).toString())
            answerTurn(next)
        }
    }

    // ---- model --------------------------------------------------------------------------------

    private fun answerTurn(t: Turn) {
        when (t.behavior) {
            // The measured pattern: the request repeated back as if done (「调高温度了。」).
            ModelBehavior.FALSE_CLAIM -> message("好的，" + t.utterance.trimEnd('。', '！', '!') + "了。")
            ModelBehavior.REFUSE -> message("抱歉，这个我暂时不支持。")
            ModelBehavior.EMPTY_RESPONSE -> if (!emptyAnswered) {
                emptyAnswered = true
                finishResponse(emptyList())
            } else {
                emitCalls(t.calls, duplicate = false)
            }
            ModelBehavior.DUPLICATE_CALL -> emitCalls(t.calls, duplicate = true)
            else -> if (t.longReplyMs > 0) scheduleLong { longMessage(t.longReplyMs) } else if (t.calls.isEmpty()) message("好的。") else emitCalls(t.calls, duplicate = false)
        }
    }

    private fun emitCalls(calls: List<ToolCallSpec>, duplicate: Boolean) {
        callsEmitted = true
        val kinds = mutableListOf<String>()
        for (call in calls) {
            repeat(if (duplicate) 2 else 1) {
                val callId = "call_${ids.incrementAndGet()}"
                val args = JSONObject()
                call.args.forEach { (k, v) ->
                    val value = v.removePrefix("~")
                    when {
                        k == "index" || (k == "value" && value.toDoubleOrNull() != null) -> args.put(k, value.toDouble().let { d -> if (d % 1.0 == 0.0) d.toLong() else d })
                        else -> args.put(k, value)
                    }
                }
                if (call.name == "describe_camera_view" && !args.has("question")) args.put("question", turn?.utterance ?: "前面有什么")
                synchronized(lock) { openCalls[callId] = call.name }
                send(JSONObject().put("type", "response.output_item.added").put("item", JSONObject()
                    .put("id", "fc_$callId").put("type", "function_call").put("call_id", callId).put("name", call.name)).toString())
                val done = JSONObject().put("type", "response.function_call_arguments.done").put("call_id", callId)
                    .put("arguments", args.toString()).toString()
                send(done)
                if (duplicateToolEvent) {
                    duplicateToolEvent = false
                    send(done)
                }
                kinds += "function_call:$callId"
            }
        }
        finishResponse(kinds)
        if (disconnectNextTurn == true) {
            disconnectNextTurn = null
            pause(20)
            dropConnection()
        }
    }

    /** A reply after tool results, decided only by what the tools returned. */
    private fun answerOutputs() {
        val results = synchronized(lock) { outputs.toList().also { outputs.clear() } }
        if (results.isEmpty()) {
            message("好的。")
            return
        }
        val failed = results.firstOrNull { !it.second.optBoolean("ok", true) }
        if (failed != null) {
            message(if (ignoreErrors) "好的，已经为你调好了。" else "抱歉，没有成功，${failed.second.optString("message").ifBlank { "操作失败了" }}")
            return
        }
        val (tool, output) = results.last()
        message(
            when {
                output.optString("screen") == "destination_list" -> "找到${output.optInt("count")}个地点，请说第几个。"
                output.optString("screen") == "route_list" -> "有${output.optInt("count")}条路线，请说第几条。"
                output.optString("status") == "navigation_started" || output.optString("screen") == "navigating" -> "导航已开始。"
                tool == "describe_camera_view" -> output.optString("answer")
                tool == "exit_navigation_mode" -> "好的，已经结束。"
                tool == "end_conversation" -> "好的，有需要再叫我。"
                else -> "好的，已完成。"
            },
        )
    }

    /** App-sent text: the false-claim guard's follow-up, or an app prompt. */
    private fun answerText(text: String) {
        val t = turn
        when {
            "不要调用任何工具" in text -> message("这个操作没有执行，暂时不支持。")
            text.startsWith("用户刚才说") && t != null && t.calls.isNotEmpty() -> emitCalls(t.calls, duplicate = false)
            else -> message("好的。")
        }
    }

    private fun message(text: String) {
        repeat(3) {
            send(JSONObject().put("type", "response.audio.delta").put("delta", AUDIO).toString())
            pause(1)
        }
        send(JSONObject().put("type", "response.audio_transcript.done").put("transcript", text).toString())
        send("""{"type":"response.audio.done"}""")
        finishResponse(listOf("message"))
    }

    /** Streams audio for [durationMs] (as long as the scenario needs to interrupt it), or until cancelled. */
    private fun longMessage(durationMs: Long) {
        synchronized(lock) { cancelledResponse = false }
        val end = System.nanoTime() + durationMs * 1_000_000
        while (System.nanoTime() < end) {
            if (synchronized(lock) { cancelledResponse }) return
            send(JSONObject().put("type", "response.audio.delta").put("delta", AUDIO).toString())
            pause(20)
        }
        send(JSONObject().put("type", "response.audio_transcript.done").put("transcript", "珠海是一座美丽的海滨城市。").toString())
        send("""{"type":"response.audio.done"}""")
        finishResponse(listOf("message"))
    }

    /** Round-trip latency: once per response, before its first event. */
    private fun startResponse() {
        if (latencyMs > 0) {
            latencySlept.addAndGet(latencyMs)
            pause(latencyMs)
        }
        synchronized(lock) { responseActive = true }
        send(JSONObject().put("type", "response.created").put("response", JSONObject().put("id", "resp_${ids.incrementAndGet()}")).toString())
    }

    private fun finishResponse(kinds: List<String>) {
        val output = JSONArray()
        kinds.forEach { kind ->
            val item = JSONObject().put("type", kind.substringBefore(':'))
            if (':' in kind) item.put("call_id", kind.substringAfter(':'))
            output.put(item)
        }
        synchronized(lock) { responseActive = false }
        if (synchronized(lock) { socket } != null) responsesDone.incrementAndGet()
        send(JSONObject().put("type", "response.done").put("response", JSONObject().put("status", "completed").put("output", output)).toString())
    }

    // ---- transport ---------------------------------------------------------------------------

    private fun schedule(block: () -> Unit) {
        pendingEmissions.incrementAndGet()
        out.execute {
            try {
                block()
            } finally {
                pendingEmissions.decrementAndGet()
            }
        }
    }

    /** A long reply streams on its own thread so an interruption can arrive meanwhile. */
    private fun scheduleLong(block: () -> Unit) {
        pendingEmissions.incrementAndGet()
        Thread({
            try {
                block()
            } finally {
                pendingEmissions.decrementAndGet()
            }
        }, "scripted-long-reply").apply { isDaemon = true }.start()
    }

    private fun pause(ms: Long) {
        if (ms > 0) Thread.sleep(ms)
    }

    private fun send(text: String) {
        synchronized(lock) { socket }?.send(text)
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            connections.incrementAndGet()
            synchronized(lock) {
                socket = webSocket
                responseActive = false
                openCalls.clear()
            }
            webSocket.send("""{"type":"session.created","session":{"model":"qianfan-realtime-flex-v1"}}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (synchronized(lock) { socket } !== webSocket) return
            val msg = JSONObject(text)
            when (msg.optString("type")) {
                "session.update" -> {
                    lastInstructions = msg.optJSONObject("session")?.optString("instructions").orEmpty()
                    webSocket.send("""{"type":"session.updated","session":{"model":"qianfan-realtime-flex-v1"}}""")
                }
                "conversation.item.create" -> {
                    val item = msg.getJSONObject("item")
                    when (item.optString("type")) {
                        "function_call_output" -> {
                            val name = synchronized(lock) { openCalls.remove(item.optString("call_id")) } ?: return
                            val output = runCatching { JSONObject(item.optString("output")) }.getOrDefault(JSONObject())
                            synchronized(lock) { outputs += name to output }
                        }
                        "message" -> {
                            val content = item.optJSONArray("content")
                            val text2 = (0 until (content?.length() ?: 0)).joinToString("") { content!!.getJSONObject(it).optString("text") }
                            synchronized(lock) { pendingUserText += text2 }
                        }
                    }
                }
                "response.create" -> schedule {
                    val busy = synchronized(lock) { responseActive }
                    if (busy) {
                        send("""{"type":"error","error":{"code":"invalid_request_error","message":"Conversation already has an active response in progress: resp_x. Wait until the response is finished before creating a new one."}}""")
                        return@schedule
                    }
                    startResponse()
                    val text2 = synchronized(lock) { pendingUserText.removeFirstOrNull() }
                    val t = turn
                    val hasOutputs = synchronized(lock) { outputs.isNotEmpty() }
                    when {
                        text2 != null -> answerText(text2)
                        // The client's retry after an empty response: now the model acts.
                        !hasOutputs && t != null && t.behavior == ModelBehavior.EMPTY_RESPONSE && !callsEmitted -> answerTurn(t)
                        else -> answerOutputs()
                    }
                }
                "response.cancel" -> schedule {
                    val wasActive = synchronized(lock) {
                        val a = responseActive
                        responseActive = false
                        cancelledResponse = true
                        a
                    }
                    if (wasActive) {
                        responsesDone.incrementAndGet()
                        send("""{"type":"response.done","response":{"status":"cancelled","output":[]}}""")
                    }
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }
    }

    private companion object {
        val AUDIO: String = Base64.getEncoder().encodeToString(ByteArray(PcmAudioCapture.FRAME_BYTES))
    }
}
