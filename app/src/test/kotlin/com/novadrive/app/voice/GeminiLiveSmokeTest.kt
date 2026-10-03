package com.novadrive.app.voice

import com.novadrive.app.GeminiApiConfig
import com.novadrive.app.GeminiAppSettings
import com.novadrive.app.NavigationState
import com.novadrive.app.PersonaProfiles
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.VoiceCatalog
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import java.util.Collections

/**
 * Opt-in smoke test against the REAL Gemini Live API (SPEC-013; P6 of GEMINI_NATIVE_PLAN; cloud
 * evidence, not device evidence).
 *
 * Runs only with NOVA_GEMINI_LIVE_SMOKE=1 and GEMINI_API_KEY in the environment, so no ordinary
 * build calls a paid API. The key is read from the environment, passed only to the client, and
 * never printed. Prints event kinds, counts and timings only (no transcript, I-8).
 *
 * Per model it measures through the app's own [GeminiLiveClient]:
 *  - L1: end of speech -> emitted ToolCall, for 10 spoken commands (16 kHz clips from
 *    tools/speech-harness/speech, gitignored; override with SMOKE_SPEECH_DIR). Each call is
 *    answered with ok=true via sendToolResult.
 *  - L2: generationComplete frame on the socket -> first AudioDelta emitted in that turn
 *    (negative = audio streamed before generationComplete), for every turn that made no call
 *    (5 conversational prompts plus any command the model answered without a call).
 *  - L3: no completed-action claim in an AssistantTranscript emitted before the tool result;
 *    plus the raw counts of audio parts / output-transcript frames after generationComplete.
 *
 *   NOVA_GEMINI_LIVE_SMOKE=1 ./gradlew :app:testDebugUnitTest --tests '*GeminiLiveSmokeTest*' -i
 */
@EnabledIfEnvironmentVariable(named = "NOVA_GEMINI_LIVE_SMOKE", matches = "1")
class GeminiLiveSmokeTest {
    private var client: GeminiLiveClient? = null
    private val t0 = System.nanoTime()
    private fun now(): Double = (System.nanoTime() - t0) / 1e6

    private class Frame(val at: Double, val audio: Int, val outText: Boolean, val gc: Boolean, val done: Boolean)
    private class Turn(
        val clip: String, val endAt: Double, val callAt: Double?, val resultAt: Double?,
        val gcAt: Double?, val audioAt: Double?, val claimsBeforeResult: Int, val audioBeforeResult: Int,
    )
    private class Row(
        val model: String, val l1Ok: Int, val l1N: Int, val l1Ms: List<Double>, val l2Ms: List<Double>,
        val l3Violations: Int, val audioBeforeResult: Int, val audioAfterSettle: Int, val textAfterSettle: Int,
    )

    private val events = Collections.synchronizedList(mutableListOf<Pair<Double, DomainVoiceEvent>>())
    private val frames = Collections.synchronizedList(mutableListOf<Frame>())

    @AfterEach
    fun tearDown() {
        client?.disconnect()
        NavigationState.onNavigatingChanged = null
        NavigationState.reset()
    }

    @Test
    fun measuresL1L2L3PerModelThroughTheAppClient(): Unit = runBlocking {
        val key = System.getenv("GEMINI_API_KEY").orEmpty()
        assertTrue(key.isNotBlank()) { "GEMINI_API_KEY is not set" }
        val dir = File(System.getenv("SMOKE_SPEECH_DIR") ?: "../tools/speech-harness/speech")
        val allClips = COMMANDS + CHAT
        val missing = allClips.filterNot { File(dir, "$it.pcm").isFile }
        assertTrue(missing.isEmpty()) { "speech clips missing in ${dir.path}: $missing (set SMOKE_SPEECH_DIR)" }
        val models = (System.getenv("SMOKE_MODELS")?.split(",") ?: listOf(VoiceCatalog.GEMINI_LIVE_FAST, VoiceCatalog.GEMINI_LIVE_EXTENDED))
        val rows = models.map { runModel(it, key, dir) }

        println("smoke_table model | L1 ok<=2.5s | L1 median/max ms | L2 median/max ms (n) | L3 claims before result | audio before result | audio_after_settle | text_after_settle")
        rows.forEach { r ->
            println(
                "smoke_table ${r.model} | ${r.l1Ok}/${r.l1N} | ${median(r.l1Ms)}/${r.l1Ms.maxOrNull()?.toInt()} | " +
                    "${median(r.l2Ms)}/${r.l2Ms.maxOrNull()?.toInt()} (${r.l2Ms.size}) | ${r.l3Violations} | ${r.audioBeforeResult} | " +
                    "${r.audioAfterSettle} | ${r.textAfterSettle}",
            )
        }
        rows.forEach { r -> assertTrue(r.l3Violations == 0) { "${r.model}: a completed-action claim was released before its tool result" } }
        rows.firstOrNull { it.model == VoiceCatalog.GEMINI_LIVE_FAST }?.let { r ->
            assertTrue(r.l1Ok >= 9) { "${r.model}: L1 ${r.l1Ok}/${r.l1N} calls within 2.5 s (need >= 9)" }
            assertTrue(r.l2Ms.size >= 3) { "${r.model}: L2 measured only ${r.l2Ms.size} turns" }
            assertTrue((median(r.l2Ms) ?: Int.MAX_VALUE) <= 50) { "${r.model}: L2 median ${median(r.l2Ms)} ms > 50 ms" }
        }
    }

    private suspend fun runModel(model: String, key: String, dir: File): Row = kotlinx.coroutines.coroutineScope {
        events.clear()
        frames.clear()
        val live = GeminiLiveClient(http = tappedHttp(), readyTimeoutMs = 15_000, contextHint = { null }, contextAwaitingAnswer = { false })
        client = live
        val collector = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            live.events().collect { events += now() to it.payload }
        }
        val setupStart = now()
        live.connect(GeminiApiConfig(GeminiAppSettings(consentAccepted = true, model = model), key, PersonaProfiles.DEFAULT_INSTRUCTIONS))
        println("smoke model=$model setup_ok ms=${(now() - setupStart).toInt()}")
        val readyBy = now() + 5_000
        while (now() < readyBy && synchronized(events) { events.none { it.second is DomainVoiceEvent.SessionReady } }) Thread.sleep(20)
        assertTrue(synchronized(events) { events.any { it.second is DomainVoiceEvent.SessionReady } }) { "$model: no SessionReady" }

        val turns = (COMMANDS + CHAT).map { clip ->
            val turn = runTurn(live, clip, File(dir, "$clip.pcm").readBytes())
            println(
                "smoke turn model=$model clip=$clip call_ms=${turn.callAt?.let { (it - turn.endAt).toInt() }} " +
                    "gc_to_audio_ms=${if (turn.gcAt != null && turn.audioAt != null) (turn.audioAt - turn.gcAt).toInt() else null} " +
                    "claims_before_result=${turn.claimsBeforeResult} audio_before_result=${turn.audioBeforeResult}",
            )
            turn
        }
        live.disconnect()
        client = null
        collector.cancel()

        val cmd = turns.filter { it.clip in COMMANDS }
        val l1 = cmd.mapNotNull { t -> t.callAt?.let { it - t.endAt } }
        val l2 = turns.filter { it.callAt == null && it.gcAt != null && it.audioAt != null }.map { it.audioAt!! - it.gcAt!! }
        var settled = false
        var audioAfter = 0
        var textAfter = 0
        synchronized(frames) {
            frames.forEach { f ->
                if (settled) { audioAfter += f.audio; if (f.outText) textAfter++ }
                if (f.gc) settled = true
                if (f.done) settled = false
            }
        }
        Row(model, l1.count { it <= 2_500.0 }, cmd.size, l1, l2, turns.sumOf { it.claimsBeforeResult }, turns.sumOf { it.audioBeforeResult }, audioAfter, textAfter)
    }

    /** One driver turn: speech in real time, every call answered ok=true, until the reply is done. */
    private fun runTurn(live: GeminiLiveClient, clip: String, pcm: ByteArray): Turn {
        val mark = now()
        val endAt = speak(live, pcm)
        val answered = mutableSetOf<String>()
        var firstResultAt: Double? = null
        var lastResultAt = mark
        val deadline = now() + 60_000
        while (now() < deadline) {
            val pending = snapshot(mark).map { it.second }.filterIsInstance<DomainVoiceEvent.ToolCall>().filter { it.callId !in answered }
            pending.forEach { call ->
                answered += call.callId
                live.sendToolResult(call.callId, resultFor(call.name))
                if (firstResultAt == null) firstResultAt = now()
                lastResultAt = now()
            }
            // Done only when the reply after the last result has finished AND the socket is quiet,
            // so a slow model's late frames are not attributed to the next turn.
            val done = snapshot(lastResultAt).any { it.second is DomainVoiceEvent.ResponseDone }
            val lastFrame = synchronized(frames) { frames.lastOrNull()?.at } ?: 0.0
            val lastEvent = synchronized(events) { events.lastOrNull()?.first } ?: 0.0
            if (done && now() - maxOf(lastFrame, lastEvent) > QUIET_MS) break
            Thread.sleep(20)
        }
        val own = snapshot(mark)
        val callAt = own.firstOrNull { it.second is DomainVoiceEvent.ToolCall }?.first
        val cut = firstResultAt ?: Double.MAX_VALUE
        val firstDone = own.firstOrNull { it.second is DomainVoiceEvent.ResponseDone }?.first ?: Double.MAX_VALUE
        val gcAt = synchronized(frames) { frames.firstOrNull { it.at > mark && it.gc && it.at <= firstDone }?.at }
        val audioAt = own.firstOrNull { it.second is DomainVoiceEvent.AudioDelta && it.first <= firstDone }?.first
        val claims = if (firstResultAt == null) 0 else own.count { (t, e) ->
            t < cut && e is DomainVoiceEvent.AssistantTranscript && CLAIM_MARKERS.any { e.text.contains(it) }
        }
        val audioBefore = if (firstResultAt == null) 0 else own.count { (t, e) -> t < cut && e is DomainVoiceEvent.AudioDelta }
        return Turn(clip, endAt, callAt, firstResultAt, gcAt, audioAt, claims, audioBefore)
    }

    /** As the microphone would: onset, 20 ms frames in real time, offset, 1.5 s silence. Returns end of speech. */
    private fun speak(live: GeminiLiveClient, pcm: ByteArray): Double {
        val frame = 640
        val silence = ByteArray(frame)
        val lastVoiced = lastVoicedFrame(pcm, frame)
        var endAt = now()
        live.onLocalSpeechActivity(true)
        val start = System.nanoTime()
        var index = 0
        var offset = 0
        while (offset < pcm.size + 75 * frame) {
            val chunk = if (offset < pcm.size) pcm.copyOfRange(offset, minOf(offset + frame, pcm.size)) else silence
            if (offset >= pcm.size && offset < pcm.size + frame) live.onLocalSpeechActivity(false)
            live.sendAudio(chunk)
            if (index == lastVoiced) endAt = now()
            offset += frame
            index++
            val wait = (start + index * 20_000_000L - System.nanoTime()) / 1_000_000
            if (wait > 0) Thread.sleep(wait)
        }
        return endAt
    }

    /** Last 20 ms frame whose RMS is above a speech threshold (clips carry trailing silence). */
    private fun lastVoicedFrame(pcm: ByteArray, frame: Int): Int {
        var last = pcm.size / frame
        for (i in 0 until (pcm.size + frame - 1) / frame) {
            var sum = 0.0
            var n = 0
            var j = i * frame
            while (j + 1 < minOf(pcm.size, (i + 1) * frame)) {
                val s = (pcm[j].toInt() and 0xff) or (pcm[j + 1].toInt() shl 8)
                sum += s.toDouble() * s
                n++
                j += 2
            }
            if (n > 0 && Math.sqrt(sum / n) > 500.0) last = i
        }
        return last
    }

    private fun snapshot(after: Double) = synchronized(events) { events.filter { it.first > after } }

    private fun resultFor(name: String) = if (name == "navigate_to") {
        """{"ok":true,"tool":"navigate_to","status":"candidates_shown","count":3,"next":"请说第几个"}"""
    } else {
        """{"ok":true,"tool":"$name"}"""
    }

    private fun median(values: List<Double>): Int? = values.sorted().let { if (it.isEmpty()) null else it[it.size / 2].toInt() }

    /** Records every server frame's shape and arrival time; SHOW_FRAMES=1 prints it (no text). */
    private fun tappedHttp(): okhttp3.OkHttpClient {
        val base = okhttp3.OkHttpClient.Builder().readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS).build()
        return object : okhttp3.OkHttpClient() {
            override fun newWebSocket(request: okhttp3.Request, listener: okhttp3.WebSocketListener): okhttp3.WebSocket =
                base.newWebSocket(request, object : okhttp3.WebSocketListener() {
                    override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) = listener.onOpen(webSocket, response)
                    override fun onMessage(webSocket: okhttp3.WebSocket, text: String) { shape(text); listener.onMessage(webSocket, text) }
                    override fun onMessage(webSocket: okhttp3.WebSocket, bytes: okio.ByteString) { shape(bytes.utf8()); listener.onMessage(webSocket, bytes) }
                    override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) = listener.onClosing(webSocket, code, reason)
                    override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) = listener.onClosed(webSocket, code, reason)
                    override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) = listener.onFailure(webSocket, t, response)
                })
        }
    }

    private fun shape(text: String) {
        val at = now()
        val json = runCatching { org.json.JSONObject(text) }.getOrNull() ?: return
        val sc = json.optJSONObject("serverContent")
        if (sc != null) {
            val parts = sc.optJSONObject("modelTurn")?.optJSONArray("parts")
            val audio = (0 until (parts?.length() ?: 0)).count { parts!!.getJSONObject(it).has("inlineData") }
            val f = Frame(at, audio, sc.has("outputTranscription"), sc.optBoolean("generationComplete"), sc.optBoolean("turnComplete") || sc.optBoolean("interrupted"))
            frames += f
            if (System.getenv("SHOW_FRAMES") == "1") println("frame@${at.toInt()} audio=$audio keys=${sc.keys().asSequence().toList()}")
        } else if (System.getenv("SHOW_FRAMES") == "1") {
            println("frame@${at.toInt()} keys=${json.keys().asSequence().toList()}")
        }
    }

    private companion object {
        val COMMANDS = listOf("ac_on", "ac_off", "temp24", "temp_up", "fan_up", "music_on", "music_off", "volume_up", "nav_home", "nav_wanda")
        val CHAT = listOf("hello", "chat_q", "intro_q", "what_can_you_do", "can_you_talk")
        const val QUIET_MS = 2_500.0
        val CLAIM_MARKERS = listOf("已", "打开了", "关闭了", "调到了", "好了")
    }
}
