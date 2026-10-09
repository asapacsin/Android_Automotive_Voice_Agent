package com.novadrive.app.voice

import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.ResponseOutcome
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * SPEC-014 clause release (owner decision 2026-10-09), A2/A3/A4: a chat reply whose words stream
 * ahead of its audio is released clause by clause, never past the checked words; a claim in any
 * clause stops early release; without the capability, and for actions, nothing changes.
 */
class DriverTurnClauseReleaseTest {
    private class FakeHost : DriverTurnPipeline.Host {
        val emitted = mutableListOf<DomainVoiceEvent>()
        override fun emit(event: DomainVoiceEvent) { emitted += event }
        override fun sendCorrection(text: String, callMayFollow: Boolean) = Unit
        override val responseCancelledByClient: Boolean = false
        override val listeningSuspended: Boolean = false
    }

    private val host = FakeHost()
    private val goodAudio = SpeechUplinkGate.Segment(durationMs = 1_500, voicedFrames = 14, peak = 9_000)

    private fun pipeline(clauseRelease: Boolean = true) = DriverTurnPipeline(
        lastAudioSegment = { goodAudio },
        contextAwaitingAnswer = { false },
        speechEvidence = { true },
        host = host,
        clauseRelease = clauseRelease,
    ).also { built = it }

    private var built: DriverTurnPipeline? = null

    @AfterEach
    fun tearDown() { built?.onSessionEnded() }

    /** [ms] of 24 kHz 16-bit mono reply audio. */
    private fun audio(ms: Int) = DomainVoiceEvent.AudioDelta(Base64.getEncoder().encodeToString(ByteArray(ms * 48)))

    private fun emittedAudioMs() = host.emitted.filterIsInstance<DomainVoiceEvent.AudioDelta>()
        .sumOf { Base64.getDecoder().decode(it.pcm16leBase64).size / 48 }

    private fun chatTurn(p: DriverTurnPipeline, request: String = "今天过得怎么样") {
        p.beginDriverTurn(playbackOrSpeaking = false, responseInProgress = false)
        p.onUserTranscript(request)
        p.onResponseCreated()
    }

    @Test
    fun aCleanChatReplyIsReleasedClauseByClauseBoundedByItsCheckedWords() {
        val p = pipeline()
        chatTurn(p)
        repeat(6) { assertTrue(p.filter(audio(300)), "held until its words are checked") }
        assertEquals(0, host.emitted.size)
        p.appendAssistantText("还不错呀，") // 5 checked characters: under the first-release minimum
        assertEquals(0, emittedAudioMs(), "a first release under 900 ms would leave the player waiting")
        p.appendAssistantText("你呢") // no clause end yet: nothing more
        assertEquals(0, emittedAudioMs())
        p.appendAssistantText("？今天开车累不累。") // 14 checked -> 2100 ms
        assertEquals(1800, emittedAudioMs(), "all six chunks, still behind the checked words")
        assertTrue(p.filter(DomainVoiceEvent.AudioDone), "the end of the audio waits for the response end")
        p.settleResponse(ResponseOutcome(spoke = true))
        assertEquals(DomainVoiceEvent.AudioDone, host.emitted.last(), "the clean reply's remainder is released at the end")
    }

    @Test
    fun audioArrivingAfterItsWordsPlaysWithinTheBudgetAlreadyGranted() {
        val p = pipeline()
        chatTurn(p)
        p.appendAssistantText("好呀，我们聊聊。") // 8 checked -> 1200 ms
        p.filter(audio(500))
        p.filter(audio(500))
        p.filter(audio(500))
        assertEquals(1000, emittedAudioMs(), "the third chunk would run past the checked words")
    }

    @Test
    fun aClaimInTheFirstClauseReleasesNothingEarly() {
        val p = pipeline()
        chatTurn(p)
        repeat(4) { p.filter(audio(300)) }
        p.appendAssistantText("已经帮你打开空调了。")
        assertEquals(0, emittedAudioMs())
        p.settleResponse(ResponseOutcome(spoke = true))
        assertEquals(0, emittedAudioMs(), "the response end drops a claim nothing performed (I-1)")
    }

    @Test
    fun aLaterClaimClosesTheGateAndItsAudioIsNeverHeard() {
        val p = pipeline()
        chatTurn(p)
        repeat(10) { p.filter(audio(200)) }
        p.appendAssistantText("好的，我看看，") // 7 checked -> 1050 ms
        assertEquals(1000, emittedAudioMs())
        p.appendAssistantText("已经帮你打开空调了。")
        p.appendAssistantText("还有别的吗？")
        assertEquals(1000, emittedAudioMs(), "nothing after the claim is released early")
        p.settleResponse(ResponseOutcome(spoke = true))
        assertEquals(1000, emittedAudioMs(), "and the end verdict drops the rest")
    }

    @Test
    fun aClaimSplitAcrossClausesIsCaughtBeforeItsVerbIsHeard() {
        val p = pipeline()
        chatTurn(p)
        repeat(10) { p.filter(audio(100)) }
        p.appendAssistantText("空调，") // 3 checked: under the first-release minimum
        val before = emittedAudioMs()
        assertTrue(before <= 450)
        p.appendAssistantText("已经帮你打开了。")
        p.settleResponse(ResponseOutcome(spoke = true))
        assertEquals(before, emittedAudioMs(), "the claim's own words are never released")
    }

    @Test
    fun withoutStreamedWordsTheHoldIsUnchanged() {
        val p = pipeline(clauseRelease = false)
        chatTurn(p)
        repeat(4) { p.filter(audio(300)) }
        p.appendAssistantText("还不错呀，你呢？")
        assertEquals(0, emittedAudioMs(), "Baidu and Gemini hold the whole reply as before")
        p.settleResponse(ResponseOutcome(spoke = true))
        assertEquals(1200, emittedAudioMs())
    }

    @Test
    fun anAbilityAnswerIsReleasedClauseByClause() {
        val p = pipeline()
        chatTurn(p, request = "你会干啥")
        repeat(6) { p.filter(audio(300)) }
        p.appendAssistantText("我是小诺呀，") // 6 checked -> 900 ms
        assertEquals(900, emittedAudioMs(), "an ability answer starts before its list is complete")
        p.appendAssistantText("我能帮你导航、放音乐、调空调。")
        assertEquals(1800, emittedAudioMs())
    }

    /** A weather question whose lookup failed; the reply response starts after the result. */
    private fun failedWeatherReply(p: DriverTurnPipeline) {
        p.beginDriverTurn(playbackOrSpeaking = false, responseInProgress = false)
        p.onUserTranscript("今天天气怎么样")
        p.onResponseCreated()
        val call = DomainVoiceEvent.ToolCall("call_w", "query_live_info", mapOf("kind" to "weather"))
        p.onToolCallDispatched(call)
        p.settleResponse(ResponseOutcome(spoke = false, toolCallIds = listOf("call_w")))
        p.onToolResult("call_w", """{"ok":false,"error":"LIVE_INFO_UNAVAILABLE","tool":"query_live_info","kind":"weather"}""")
        p.onResponseCreated()
    }

    @Test
    fun anHonestNoDataReplyIsReleasedClauseByClause() {
        val p = pipeline()
        failedWeatherReply(p)
        repeat(6) { assertTrue(p.filter(audio(300)), "a no-data reply is held until its words are checked") }
        p.appendAssistantText("抱歉，天气暂时没查到，")
        assertEquals(1500, emittedAudioMs(), "11 checked characters -> 1650 ms")
    }

    @Test
    fun anInventedForecastClosesTheGateBeforeItIsHeard() {
        val p = pipeline()
        failedWeatherReply(p)
        repeat(10) { p.filter(audio(300)) }
        p.appendAssistantText("今天北京天气晴转多云，气温20到28度。")
        assertEquals(0, emittedAudioMs(), "data with no source behind it is never released early")
    }

    /** 「放首歌」 handed to the music app, which did not read back what is playing (SPEC-017). */
    private fun unconfirmedMusicReply(p: DriverTurnPipeline) {
        p.beginDriverTurn(playbackOrSpeaking = false, responseInProgress = false)
        p.onUserTranscript("放一首轻松的歌")
        p.onResponseCreated()
        val call = DomainVoiceEvent.ToolCall("call_m", "play_music", mapOf("mood" to "relaxed"))
        p.onToolCallDispatched(call)
        p.settleResponse(ResponseOutcome(spoke = false, toolCallIds = listOf("call_m")))
        p.onToolResult("call_m", """{"ok":true,"tool":"play_music","status":"requested_unverified"}""")
        p.onResponseCreated()
    }

    @Test
    fun aHandOffReplyIsReleasedClauseByClause() {
        val p = pipeline()
        unconfirmedMusicReply(p)
        repeat(6) { p.filter(audio(300)) }
        p.appendAssistantText("好的，已经让音乐软件去找了，")
        assertEquals(1800, emittedAudioMs(), "14 checked characters: all six chunks")
    }

    @Test
    fun aNowPlayingClaimAfterAnUnconfirmedHandOffIsNeverReleasedEarly() {
        val p = pipeline()
        unconfirmedMusicReply(p)
        repeat(6) { p.filter(audio(300)) }
        p.appendAssistantText("正在为你播放周杰伦的《晴天》。")
        assertEquals(0, emittedAudioMs())
        p.settleResponse(ResponseOutcome(spoke = true))
        assertEquals(0, emittedAudioMs(), "the end drops it (unconfirmed_music_claim)")
    }

    @Test
    fun anActionReplyStillWaitsForExecutionProof() {
        val p = pipeline()
        p.beginDriverTurn(playbackOrSpeaking = false, responseInProgress = false)
        p.onUserTranscript("帮我打开空调")
        p.onResponseCreated()
        repeat(4) { p.filter(audio(300)) }
        p.appendAssistantText("好的，这就去开。")
        assertEquals(0, emittedAudioMs(), "an action's reply is not clause-released")
    }
}
