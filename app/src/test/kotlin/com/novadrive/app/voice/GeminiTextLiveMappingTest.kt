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
 * Opt-in TEXT_LIVE measurement against the REAL Gemini Live API (FUZZY-TEXT-LIVE-001,
 * MUSIC-TEXT-LIVE-001). Runs only with NOVA_GEMINI_TEXT_LIVE=1 and GEMINI_API_KEY set; the key
 * goes only to the client and is never printed.
 *
 * Each phrase is sent as a text turn in a fresh session opened with the app's own client, tool
 * catalog and persona. Context rows send their setup phrase first and answer its call with a
 * simulated success. The FIRST tool call of the measured turn (name + arguments) is recorded;
 * every call is answered so the turn ends. The table (phrase ids, tool names, arguments) is
 * written to build/text-live/; no transcript of the model's reply is kept (I-8).
 *
 * This test records results; it does not assert the pass criterion, so a FAIL is reported, not hidden.
 *
 *   NOVA_GEMINI_TEXT_LIVE=1 ./gradlew :app:testDebugUnitTest --tests '*GeminiTextLiveMapping*' --rerun
 */
@EnabledIfEnvironmentVariable(named = "NOVA_GEMINI_TEXT_LIVE", matches = "1")
class GeminiTextLiveMappingTest {
    private var client: GeminiLiveClient? = null
    private val t0 = System.nanoTime()
    private fun now(): Double = (System.nanoTime() - t0) / 1e6
    private val events = Collections.synchronizedList(mutableListOf<Pair<Double, DomainVoiceEvent>>())

    private class Case(
        val id: String,
        val phrase: String,
        val expected: String,
        val setup: List<String> = emptyList(),
        val check: (DomainVoiceEvent.ToolCall?) -> Boolean,
    )

    @AfterEach
    fun tearDown() {
        client?.disconnect()
        NavigationState.onNavigatingChanged = null
        NavigationState.reset()
    }

    @Test
    fun mapsFuzzyAndMusicPhrasesThroughTheAppClient(): Unit = runBlocking {
        val key = System.getenv("GEMINI_API_KEY").orEmpty()
        assertTrue(key.isNotBlank()) { "GEMINI_API_KEY is not set" }
        val model = System.getenv("TEXT_LIVE_MODEL") ?: VoiceCatalog.GEMINI_LIVE_FAST
        val out = File(System.getProperty("user.dir"), "build/text-live").apply { mkdirs() }
        val report = StringBuilder("model=$model\n")
        listOf("FUZZY" to FUZZY, "MUSIC" to MUSIC, "MUSIC_CONTROL" to MUSIC_CONTROL).forEach { (set, cases) ->
            var pass = 0
            cases.forEach { c ->
                val (call, error) = try { runCase(model, key, c) to null } catch (e: Throwable) { null to (e::class.simpleName + ":" + (e.message ?: "").take(80)) }
                val ok = error == null && c.check(call)
                if (ok) pass++
                val got = error ?: call?.let { "${it.name}${it.arguments}" } ?: "no call"
                val line = "$set | ${c.id} | expected ${c.expected} | got $got | ${if (ok) "PASS" else "MISS"}"
                println("text_live $line")
                report.append(line).append('\n')
            }
            val summary = "$set total $pass/${cases.size}"
            println("text_live $summary")
            report.append(summary).append('\n')
        }
        File(out, "results.txt").writeText(report.toString())
    }

    private suspend fun runCase(model: String, key: String, c: Case): DomainVoiceEvent.ToolCall? = kotlinx.coroutines.coroutineScope {
        events.clear()
        val live = GeminiLiveClient(readyTimeoutMs = 15_000, contextHint = { null }, contextAwaitingAnswer = { false })
        client = live
        val collector = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            live.events().collect { events += now() to it.payload }
        }
        try {
            live.connect(GeminiApiConfig(GeminiAppSettings(consentAccepted = true, model = model), key, PersonaProfiles.DEFAULT_INSTRUCTIONS))
            val readyBy = now() + 5_000
            while (now() < readyBy && synchronized(events) { events.none { it.second is DomainVoiceEvent.SessionReady } }) Thread.sleep(20)
            c.setup.forEach { turn(live, it) }
            turn(live, c.phrase)
        } finally {
            live.disconnect()
            client = null
            collector.cancel()
        }
    }

    /** One text turn; every call answered with a simulated success; returns the turn's first call. */
    private fun turn(live: GeminiLiveClient, text: String): DomainVoiceEvent.ToolCall? {
        val mark = now()
        live.sendUserText(text)
        val answered = mutableSetOf<String>()
        var first: DomainVoiceEvent.ToolCall? = null
        var lastAt = mark
        val deadline = now() + 30_000
        while (now() < deadline) {
            val own = snapshot(mark)
            own.map { it.second }.filterIsInstance<DomainVoiceEvent.ToolCall>().filter { it.callId !in answered }.forEach { call ->
                if (first == null) first = call
                answered += call.callId
                live.sendToolResult(call.callId, resultFor(call))
                lastAt = now()
            }
            val done = snapshot(lastAt).any { it.second is DomainVoiceEvent.ResponseDone }
            val lastEvent = synchronized(events) { events.lastOrNull()?.first } ?: 0.0
            if (done && now() - lastEvent > QUIET_MS) break
            Thread.sleep(20)
        }
        return first
    }

    private fun snapshot(after: Double) = synchronized(events) { events.filter { it.first > after } }

    private fun resultFor(call: DomainVoiceEvent.ToolCall): String = when (call.name) {
        "control_seat" -> """{"ok":true,"tool":"control_seat","seat_height":4,"announce":"座椅调好了。"}"""
        "control_window" -> """{"ok":true,"tool":"control_window","announce":"车窗调好了。"}"""
        "run_scenario" -> """{"ok":true,"tool":"run_scenario","status":"done","announce":"好了。"}"""
        "play_music" -> """{"ok":true,"tool":"play_music","status":"requested_unverified"}"""
        else -> """{"ok":true,"tool":"${call.name}"}"""
    }

    private companion object {
        const val QUIET_MS = 1_500.0

        fun DomainVoiceEvent.ToolCall?.is_(name: String, vararg args: Pair<String, (String?) -> Boolean>): Boolean =
            this != null && this.name == name && args.all { (k, p) -> p(arguments[k]) }

        fun num(s: String?): Double? = s?.toDoubleOrNull()
        val allWindows: (String?) -> Boolean = { it == null || it == "all" }

        val FUZZY = listOf(
            Case("FZ-01", "有蚊子", "run_scenario{mosquito}") { it.is_("run_scenario", "name" to { v -> v == "mosquito" }) },
            Case("FZ-02", "蚊子出去了", "run_scenario{mosquito_done}", setup = listOf("有蚊子")) {
                it.is_("run_scenario", "name" to { v -> v == "mosquito_done" })
            },
            Case("FZ-03", "座位有点高", "control_seat{adjust_height,-1}") {
                it.is_("control_seat", "action" to { v -> v == "adjust_height" }, "value" to { v -> (num(v) ?: 0.0) < 0 })
            },
            Case("FZ-04", "座位有点低", "control_seat{adjust_height,+1}") {
                it.is_("control_seat", "action" to { v -> v == "adjust_height" }, "value" to { v -> (num(v) ?: 0.0) > 0 })
            },
            Case("FZ-05", "再低一点", "control_seat{adjust_height,-1}", setup = listOf("座位有点高")) {
                it.is_("control_seat", "action" to { v -> v == "adjust_height" }, "value" to { v -> (num(v) ?: 0.0) < 0 })
            },
            Case("FZ-06", "把车窗打开一半", "control_window{set,all,50}") {
                it.is_("control_window", "action" to { v -> v == "set" }, "window" to allWindows, "value" to { v -> num(v) == 50.0 })
            },
            Case("FZ-07", "开一点主驾车窗", "control_window{adjust,driver,+}") {
                it.is_("control_window", "action" to { v -> v == "adjust" }, "window" to { v -> v == "driver" }, "value" to { v -> v == null || (num(v) ?: 0.0) > 0 })
            },
            Case("FZ-08", "关窗", "control_window{close,all}") {
                it.is_("control_window", "action" to { v -> v == "close" }, "window" to allWindows)
            },
            Case("FZ-09", "有点闷", "run_scenario{stuffy}") { it.is_("run_scenario", "name" to { v -> v == "stuffy" }) },
            Case("FZ-10", "好困", "run_scenario{drowsy}") { it.is_("run_scenario", "name" to { v -> v == "drowsy" }) },
            Case("FZ-11", "说话能不能嗲一点", "set_speaking_style{sweet}") { it.is_("set_speaking_style", "style" to { v -> v == "sweet" }) },
            Case("FZ-12", "别嗲了，正常一点", "set_speaking_style{default}") { it.is_("set_speaking_style", "style" to { v -> v == "default" }) },
            Case("FZ-13", "打开天窗", "no call") { it == null },
            Case("FZ-14", "再低一点", "no call (clarify)", setup = listOf("座位有点高", "空调温度调到24度")) { it == null },
            Case("FZ-15", "傲娇一点", "set_speaking_style{tsundere}") { it.is_("set_speaking_style", "style" to { v -> v == "tsundere" }) },
            Case("FZ-16", "說話傲嬌一點", "set_speaking_style{tsundere}") { it.is_("set_speaking_style", "style" to { v -> v == "tsundere" }) },
            Case("FZ-17", "温柔一点", "set_speaking_style{gentle}") { it.is_("set_speaking_style", "style" to { v -> v == "gentle" }) },
            Case("FZ-18", "元气一点", "set_speaking_style{lively}") { it.is_("set_speaking_style", "style" to { v -> v == "lively" }) },
            Case("FZ-19", "说话霸道一点", "no call (style not offered)") { it == null },
        )

        private fun music(id: String, phrase: String, expected: String, vararg any: Pair<String, String>) =
            Case(id, phrase, expected) { c ->
                c != null && c.name == "play_music" &&
                    listOf("title", "artist", "album_or_work", "mood", "query").any { !c.arguments[it].isNullOrBlank() } &&
                    (any.isEmpty() || any.any { (k, token) -> c.arguments[k].orEmpty().contains(token) })
            }

        val MUSIC = listOf(
            music("M-01", "放点梶浦由记的，空之境界里很燃的那首", "play_music artist~梶浦 or work~空之境界", "artist" to "梶浦", "album_or_work" to "空之境界", "artist" to "Kajiura", "title" to "oblivious", "title" to "sprinter"),
            music("M-02", "来点开车提神的", "play_music mood/query"),
            music("M-03", "放周杰伦的晴天", "play_music title~晴天", "title" to "晴天"),
            music("M-04", "来首适合下雨天的歌", "play_music mood/query"),
            music("M-05", "放一首陈奕迅的十年", "play_music title~十年", "title" to "十年"),
            music("M-06", "我想听进击的巨人的片头曲", "play_music work~进击的巨人 or title", "album_or_work" to "进击的巨人", "album_or_work" to "Attack", "title" to "红莲", "title" to "紅蓮", "title" to "Guren"),
            music("M-07", "放点安静的钢琴曲", "play_music mood/query"),
            music("M-08", "来首王菲的红豆", "play_music title~红豆", "title" to "红豆", "title" to "紅豆"),
            music("M-09", "放千与千寻里的那首主题曲", "play_music work~千与千寻 or title", "album_or_work" to "千与千寻", "album_or_work" to "千と千尋", "album_or_work" to "Spirited", "title" to "永远同在", "title" to "いつも何度でも", "title" to "Always"),
            music("M-10", "放一首八十年代的粤语老歌", "play_music mood/query"),
        )

        val MUSIC_CONTROL = listOf(
            Case("M-CTL", "放首歌", "control_music{play}") { it.is_("control_music", "action" to { v -> v == "play" }) },
        )
    }
}
