package com.novadrive.app

import com.novadrive.app.nav.ChoiceMatch
import com.novadrive.app.nav.DestinationCandidate
import com.novadrive.app.nav.DestinationQuery
import com.novadrive.app.nav.NavigationChoice
import com.novadrive.app.nav.NavigationChoiceResolver
import com.novadrive.app.voice.ActionClaimGuard
import com.novadrive.app.voice.PhantomTurnGate
import com.novadrive.app.voice.SpeechUplinkGate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The product's promises, written as what an outside observer can check — not as which class was
 * called. Each test names the behaviour and the invariant it protects; the implementation may be
 * restructured freely as long as these still hold.
 *
 * Where a promise can only be settled on a phone (audio actually heard, map actually moved), the
 * test says so and `ACCEPTANCE_TESTS.md` carries the device evidence.
 */
class ObservableContractsTest {
    private fun poi(name: String, distanceMeters: Int? = null) = DestinationCandidate(
        id = name,
        name = name,
        address = "珠海市",
        district = "香洲区",
        latitude = 22.13,
        longitude = 113.54,
        distanceMeters = distanceMeters,
    )

    // ---- navigation: 「导航去附近的麦当劳」 ----

    @Test
    fun askingForANearbyBrandSearchesForTheBrand() {
        // The spoken words are not a POI name. Searching them verbatim found nothing on device.
        assertEquals("麦当劳", DestinationQuery.searchKeyword("附近的麦当劳"))
        assertTrue(DestinationQuery.isNearbyRequest("附近的麦当劳"))
    }

    @Test
    fun choosingABranchByNameReachesThatBranch() {
        val onScreen = listOf(poi("麦当劳(横琴中央汇店)", 709), poi("麦当劳(创新方商场)", 2300))
        val picked = NavigationChoiceResolver.pickDestination(onScreen, NavigationChoice.Name("选择麦当劳创新方商场"))
        assertEquals(2, (picked as ChoiceMatch.Picked).position)
        assertEquals("麦当劳(创新方商场)", picked.item.name)
    }

    @Test
    fun choosingByOrdinalReachesThatPosition() {
        val onScreen = listOf(poi("麦当劳(横琴中央汇店)"), poi("麦当劳(创新方商场)"))
        val picked = NavigationChoiceResolver.pickDestination(onScreen, NavigationChoice.Index(2))
        assertEquals("麦当劳(创新方商场)", (picked as ChoiceMatch.Picked).item.name)
    }

    @Test
    fun aNameThatMatchesNothingOnScreenIsNeverGuessed() {
        val onScreen = listOf(poi("麦当劳(横琴中央汇店)"), poi("麦当劳(横琴口岸店)"))
        val picked = NavigationChoiceResolver.pickDestination(onScreen, NavigationChoice.Name("麦当劳珠海站店"))
        assertEquals("NO_MATCH", (picked as ChoiceMatch.Rejected).code)
    }

    @Test
    fun twoPlausibleBranchesAreHandedBackToTheDriver() {
        val onScreen = listOf(poi("麦当劳(珠海站北店)"), poi("麦当劳(珠海站南店)"))
        val picked = NavigationChoiceResolver.pickDestination(onScreen, NavigationChoice.Name("麦当劳珠海站店"))
        assertEquals("AMBIGUOUS", (picked as ChoiceMatch.Rejected).code)
    }

    @Test
    fun aRefusalCarriesWhatToSayNext() {
        // INVARIANT I-2: the driver must be given something to do, without relying on the model
        // remembering a tool description across a conversation reset.
        listOf("NO_MATCH", "AMBIGUOUS", "OUT_OF_RANGE", "NO_OPTIONS_ON_SCREEN").forEach { code ->
            assertTrue(ToolFailureAdvice.forCode(code)?.isNotBlank() == true, "$code needs advice")
        }
    }

    // ---- unsupported action: 「把音量调大一点」 ----

    @Test
    fun anUnsupportedRequestIsRecognisedBeforeTheModelAnswers() {
        assertTrue(ActionClaimGuard.isUnsupportedRequest("把音量调大一点"))
        assertTrue(ActionClaimGuard.isUnsupportedRequest("打开车窗"))
        assertFalse(ActionClaimGuard.isUnsupportedRequest("空调温度调高一点"))
    }

    @Test
    fun theForbiddenIntermediateClaimIsRecognisedAsAClaim() {
        // 「正在调整音量」 is exactly what must never be spoken for a request with no tool.
        assertTrue(ActionClaimGuard.claimsDone("正在调整音量"))
        assertTrue(ActionClaimGuard.claimsDone("音量已经调大了"))
        // An honest refusal is not a claim, and is spoken.
        assertFalse(ActionClaimGuard.claimsDone("抱歉，我无法调节音量。"))
    }

    // ---- unavailable real-time information: 「今天天气怎么样」 ----

    @Test
    fun aWeatherAnswerThatDoesNotDeclineIsTreatedAsFabricated() {
        assertTrue(ActionClaimGuard.isRealtimeInfoRequest("今天天气怎么样？"))
        assertTrue(ActionClaimGuard.fabricatesRealtimeInfo("今天北京天气晴转多云，气温20到28度。"))
        assertFalse(ActionClaimGuard.fabricatesRealtimeInfo("抱歉，我无法获取实时天气信息。"))
    }

    @Test
    fun theWeatherCorrectionForbidsInventingDetail() {
        val correction = ActionClaimGuard.realtimeInfoCorrection("今天天气怎么样？")
        assertTrue(correction.contains("不要给出任何城市、温度或预报"))
    }

    // ---- stray audio: noise must not become a turn, and never an action ----

    @Test
    fun anImpulseNeverReachesTheModel() {
        val gate = SpeechUplinkGate()
        val frameBytes = com.novadrive.app.voice.PcmAudioCapture.FRAME_BYTES
        val loudTap = ByteArray(frameBytes) { if (it % 2 == 0) 0xFF.toByte() else 0x3F }
        val quiet = ByteArray(frameBytes)
        val sent = listOf(quiet, loudTap, quiet, quiet).flatMap { gate.offer(it).send }
        assertTrue(sent.isEmpty(), "a single-frame impulse must not be uploaded")
    }

    @Test
    fun aContentlessReplyToDoubtfulAudioIsNotSpoken() {
        val verdict = PhantomTurnGate.judge(
            PhantomTurnGate.Turn(
                hadToolCall = false,
                contextAwaitingAnswer = false,
                audio = SpeechUplinkGate.Segment(durationMs = 400, voicedFrames = 3, peak = 9_000),
                assistantText = "没听清，再说一遍。",
                hadUserTranscript = false,
            ),
        )
        assertTrue(verdict is PhantomTurnGate.Verdict.Drop)
    }

    @Test
    fun aTurnThatAskedForAnActionIsAlwaysSpoken() {
        // INVARIANT I-4: suppression is about audio. It must never hide that something ran.
        val verdict = PhantomTurnGate.judge(
            PhantomTurnGate.Turn(
                hadToolCall = true,
                contextAwaitingAnswer = false,
                audio = SpeechUplinkGate.Segment(durationMs = 300, voicedFrames = 2, peak = 9_000),
                assistantText = "音乐已开始播放。",
                hadUserTranscript = false,
            ),
        )
        assertEquals(PhantomTurnGate.Verdict.Speak, verdict)
    }

    @Test
    fun aDriverQuestionIsAnsweredEvenWithNoToolCall() {
        // The suppression rule must not become "no tool call means silence".
        val verdict = PhantomTurnGate.judge(
            PhantomTurnGate.Turn(
                hadToolCall = false,
                contextAwaitingAnswer = false,
                audio = SpeechUplinkGate.Segment(durationMs = 1_900, voicedFrames = 17, peak = 6_000),
                assistantText = "前面第二个路口右转就到了。",
                hadUserTranscript = true,
            ),
        )
        assertEquals(PhantomTurnGate.Verdict.Speak, verdict)
    }
}
