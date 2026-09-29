package com.novadrive.app.voice

import com.novadrive.app.GeminiApiConfig
import com.novadrive.app.GeminiAppSettings
import com.novadrive.app.NavigationState
import com.novadrive.app.PersonaProfiles
import com.novadrive.ingress.realtime.DomainVoiceEvent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.Collections

/**
 * Opt-in smoke test against the REAL Gemini Live API (SPEC-013; not device evidence).
 *
 * Runs only with NOVA_GEMINI_LIVE_SMOKE=1 and GEMINI_API_KEY in the environment, so no ordinary
 * build calls a paid API. The key is read from the environment, passed only to the client, and
 * never printed. Prints event kinds and timings only (no transcript, I-8).
 *
 *   NOVA_GEMINI_LIVE_SMOKE=1 ./gradlew :app:testDebugUnitTest --tests '*GeminiLiveSmokeTest*' -i
 */
@EnabledIfEnvironmentVariable(named = "NOVA_GEMINI_LIVE_SMOKE", matches = "1")
class GeminiLiveSmokeTest {
    private var client: GeminiLiveClient? = null

    @AfterEach
    fun tearDown() {
        client?.disconnect()
        NavigationState.onNavigatingChanged = null
        NavigationState.reset()
    }

    @Test
    fun realApiAcceptsSetupAndARequestedActionIsHeldUntilItsResult() = runBlocking {
        val key = System.getenv("GEMINI_API_KEY").orEmpty()
        assertTrue(key.isNotBlank()) { "GEMINI_API_KEY is not set" }
        val events = Collections.synchronizedList(mutableListOf<Pair<Long, DomainVoiceEvent>>())
        val live = GeminiLiveClient(http = tappedHttp(t0 = System.currentTimeMillis()), readyTimeoutMs = 15_000, contextHint = { null }, contextAwaitingAnswer = { false })
        client = live
        val t0 = System.currentTimeMillis()
        val collector = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            live.events().collect { events += (System.currentTimeMillis() - t0) to it.payload }
        }
        live.connect(GeminiApiConfig(GeminiAppSettings(enabled = true, consentAccepted = true), key, PersonaProfiles.DEFAULT_INSTRUCTIONS))
        println("smoke setup_ok ms=${System.currentTimeMillis() - t0}")

        val pcmPath = System.getenv("SMOKE_PCM")
        if (pcmPath != null) {
            // As the microphone would: local onset, 16 kHz frames in real time, offset, then silence.
            val pcm = java.io.File(pcmPath).readBytes()
            val frame = 640
            val silence = ByteArray(frame)
            live.onLocalSpeechActivity(true)
            val start = System.currentTimeMillis()
            var sent = 0
            var offset = 0
            while (offset < pcm.size + 75 * frame) {
                val chunk = if (offset < pcm.size) pcm.copyOfRange(offset, minOf(offset + frame, pcm.size)) else silence
                if (offset >= pcm.size && offset < pcm.size + frame) live.onLocalSpeechActivity(false)
                live.sendAudio(chunk)
                offset += frame
                sent++
                val due = start + sent * 20L
                val wait = due - System.currentTimeMillis()
                if (wait > 0) Thread.sleep(wait)
            }
            println("smoke audio_sent ms=${pcm.size / 32}")
        } else {
            live.sendUserText(System.getenv("SMOKE_TEXT") ?: "把空调打开")
        }
        val call = waitFor(events, 45_000) { it is DomainVoiceEvent.ToolCall } as DomainVoiceEvent.ToolCall?
        println("smoke tool_call=${call?.name} args_valid=${call?.arguments?.containsKey("_validation_error") == false}")
        val resultAt = System.currentTimeMillis() - t0
        if (call != null) {
            val output = if (call.name == "navigate_to") {
                """{"ok":true,"tool":"navigate_to","status":"candidates_shown","count":3,"next":"请说第几个"}"""
            } else {
                """{"ok":true,"tool":"${call.name}","power_on":true,"temperature_c":24}"""
            }
            live.sendToolResult(call.callId, output)
        }
        // The spoken result: a turn that finishes after the result was sent.
        val doneAfterResult = { synchronized(events) { events.any { (t, e) -> t > resultAt && e is DomainVoiceEvent.ResponseDone } } }
        val deadline = System.currentTimeMillis() + 45_000
        while (call != null && !doneAfterResult() && System.currentTimeMillis() < deadline) Thread.sleep(100)
        collector.cancel()

        val kinds = synchronized(events) { events.map { (t, e) -> "$t:${e::class.simpleName}" } }
        println("smoke events ${kinds.joinToString(" ")}")
        if (System.getenv("SHOW_TEXT") == "1") {
            synchronized(events) {
                events.filter { it.second is DomainVoiceEvent.AssistantTranscript }
                    .forEach { (t, e) -> println("smoke said@$t ${(e as DomainVoiceEvent.AssistantTranscript).text}") }
            }
        }
        val callAt = events.firstOrNull { it.second is DomainVoiceEvent.ToolCall }?.first
        val firstAudioAt = events.firstOrNull { it.second is DomainVoiceEvent.AudioDelta }?.first
        println("smoke tool_call_ms=$callAt first_emitted_audio_ms=$firstAudioAt")
        assertTrue(events.any { it.second is DomainVoiceEvent.SessionReady }) { "no SessionReady" }
        assertTrue(call != null) { "no tool call within 45 s" }
        // I-1 under Gemini: nothing released before the tool call may claim the action is done.
        // (A non-claim filler may legitimately be heard; a completed-action claim may not.)
        val claimsBeforeCall = synchronized(events) {
            events.filter { (t, e) ->
                callAt != null && t < callAt && e is DomainVoiceEvent.AssistantTranscript &&
                    (e.text.contains("已") || e.text.contains("打开了"))
            }.size
        }
        println("smoke released_claims_before_call=$claimsBeforeCall")
        assertTrue(claimsBeforeCall == 0) { "a completed-action claim was released before the tool call" }
    }

    /** SHOW_FRAMES=1: prints each server frame's shape (tool calls in full; audio as a count). */
    private fun tappedHttp(t0: Long): okhttp3.OkHttpClient {
        val base = okhttp3.OkHttpClient.Builder().readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS).build()
        if (System.getenv("SHOW_FRAMES") != "1") return base
        return object : okhttp3.OkHttpClient() {
            override fun newWebSocket(request: okhttp3.Request, listener: okhttp3.WebSocketListener): okhttp3.WebSocket =
                base.newWebSocket(request, object : okhttp3.WebSocketListener() {
                    override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) = listener.onOpen(webSocket, response)
                    override fun onMessage(webSocket: okhttp3.WebSocket, text: String) { shape(t0, text); listener.onMessage(webSocket, text) }
                    override fun onMessage(webSocket: okhttp3.WebSocket, bytes: okio.ByteString) { shape(t0, bytes.utf8()); listener.onMessage(webSocket, bytes) }
                    override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) = listener.onClosing(webSocket, code, reason)
                    override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) = listener.onClosed(webSocket, code, reason)
                    override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) = listener.onFailure(webSocket, t, response)
                })
        }
    }

    private fun shape(t0: Long, text: String) {
        val json = org.json.JSONObject(text)
        val at = System.currentTimeMillis() - t0
        when {
            json.has("toolCall") || json.has("toolCallCancellation") || json.has("voiceActivity") ->
                println("frame@$at ${text.replace("\n", "")}")
            json.has("serverContent") -> {
                val sc = json.getJSONObject("serverContent")
                val parts = sc.optJSONObject("modelTurn")?.optJSONArray("parts")
                val audio = (0 until (parts?.length() ?: 0)).count { parts!!.getJSONObject(it).has("inlineData") }
                val other = sc.keys().asSequence().filter { it != "modelTurn" }.map { k ->
                    if (k.endsWith("Transcription")) "$k=" + sc.getJSONObject(k).optString("text") else "$k=" + sc.get(k)
                }.toList()
                println("frame@$at serverContent audio=$audio $other")
            }
            else -> println("frame@$at keys=${json.keys().asSequence().toList()}")
        }
    }

    private fun waitFor(
        events: List<Pair<Long, DomainVoiceEvent>>,
        timeoutMs: Long,
        predicate: (DomainVoiceEvent) -> Boolean,
    ): DomainVoiceEvent? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            synchronized(events) { events.firstOrNull { predicate(it.second) } }?.let { return it.second }
            Thread.sleep(100)
        }
        return null
    }
}
