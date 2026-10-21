package com.novadrive.app.ui

import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.FakeRealtimeVoiceProvider
import com.novadrive.ingress.realtime.InMemoryMicrophonePort
import com.novadrive.ingress.realtime.InMemoryPlaybackPort
import com.novadrive.ingress.realtime.RealtimeSessionConfig
import com.novadrive.ingress.realtime.VoiceCatalog
import com.novadrive.ingress.realtime.VoiceProviderId
import com.novadrive.ingress.realtime.VoiceSessionCallbacks
import com.novadrive.ingress.realtime.VoiceSessionController
import com.novadrive.ingress.realtime.VoiceUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * B-035 in a simulated session: the real session core ([VoiceSessionController], its state
 * machine and playback epochs) produces the turn states and transcript lines exactly as on the
 * phone, and the bubble follows the same rules [AssistantOverlayView] applies: [recentTranscript]
 * for the text, [TranscriptBubbleFade] polled once a second for the fade, held while playback is
 * audible or a question waits on screen. Replaced: the model (scripted events), the speaker
 * ([InMemoryPlaybackPort], drained by the script), the Android view and its main-thread timer.
 */
class TranscriptBubbleSessionSimulationTest {
    private class Sim(scope: CoroutineScope) {
        val provider = FakeRealtimeVoiceProvider()
        val playback = InMemoryPlaybackPort()
        var questionOnScreen = false
        var nowMs = 0L
        var state = VoiceUiState.DISCONNECTED
        var bubble: String = PLACEHOLDER
        private val fade = TranscriptBubbleFade()
        /** When the bubble went back to the placeholder, if it did. */
        var clearedAtMs: Long? = null

        val controller = VoiceSessionController(
            provider = provider,
            microphone = InMemoryMicrophonePort(),
            playback = playback,
            scope = scope,
            config = RealtimeSessionConfig(VoiceProviderId.FAKE, VoiceCatalog.FAKE_MODEL),
            callbacks = VoiceSessionCallbacks(
                onUiState = { s, _ -> state = s },
                onTranscript = { line ->
                    bubble = recentTranscript(if (bubble == PLACEHOLDER) "" else bubble, line)
                    fade.lineShown()
                    clearedAtMs = null
                },
            ),
        )

        fun emit(event: DomainVoiceEvent) = provider.emit(event)

        /** Lets [ms] of wall time pass, polling the fade as the overlay does. */
        fun advance(ms: Long) {
            val end = nowMs + ms
            while (nowMs < end) {
                nowMs += TRANSCRIPT_FADE_POLL_MS
                val held = playback.playbackActive || questionOnScreen
                if (fade.tick(nowMs, state, held)) {
                    bubble = PLACEHOLDER
                    clearedAtMs = nowMs
                }
            }
        }

        /** The speaker finished what was queued (the playout ended). */
        fun playoutEnds() = playback.played.clear()

        /** One full exchange: the driver asks, 小诺 answers with audio that is still playing at ResponseDone. */
        fun exchange(question: String, answer: String) {
            emit(DomainVoiceEvent.SpeechStarted)
            advance(2_000)
            emit(DomainVoiceEvent.SpeechStopped)
            emit(DomainVoiceEvent.UserTranscript(question, final = true))
            advance(1_000)
            emit(DomainVoiceEvent.ResponseStarted)
            emit(DomainVoiceEvent.AudioDelta("AAAA"))
            emit(DomainVoiceEvent.AssistantTranscript(answer, final = true))
            advance(1_000)
            emit(DomainVoiceEvent.AudioDone)
            emit(DomainVoiceEvent.ResponseDone("completed"))
        }
    }

    @Test
    fun theOwnersExchangeLeavesTheMapAboutTenSecondsAfterPlayoutEnds() =
        runTest(UnconfinedTestDispatcher()) {
            val sim = Sim(this)
            sim.controller.start()
            sim.emit(DomainVoiceEvent.SessionReady(VoiceCatalog.FAKE_MODEL, interruptResponse = true))
            sim.exchange("你能做什么?", "哼，本姑娘能帮你导航…")
            assertEquals("你: 你能做什么?\n小诺: 哼，本姑娘能帮你导航…", sim.bubble)
            assertEquals(VoiceUiState.LISTENING, sim.state, "the reply is done on the server")
            assertTrue(sim.playback.playbackActive, "but the speaker is still saying it")

            // 小诺 is still audible for 6 s after ResponseDone: the bubble must stay.
            sim.advance(6_000)
            assertEquals("你: 你能做什么?\n小诺: 哼，本姑娘能帮你导航…", sim.bubble)
            val playoutEndedAt = sim.nowMs
            sim.playoutEnds()

            sim.advance(9_000)
            assertTrue(sim.bubble.contains("本姑娘"), "still there 9 s after the playout")
            sim.advance(3_000)
            assertEquals(PLACEHOLDER, sim.bubble, "gone (B-035)")
            val after = sim.clearedAtMs!! - playoutEndedAt
            assertTrue(after in TRANSCRIPT_FADE_MS..TRANSCRIPT_FADE_MS + 2 * TRANSCRIPT_FADE_POLL_MS, "cleared ${after}ms after playout")
            sim.controller.stop()
        }

    @Test
    fun aFollowUpBeforeTheFadeKeepsTheNewExchangeForItsOwnFullPeriod() =
        runTest(UnconfinedTestDispatcher()) {
            val sim = Sim(this)
            sim.controller.start()
            sim.emit(DomainVoiceEvent.SessionReady(VoiceCatalog.FAKE_MODEL, interruptResponse = true))
            sim.exchange("你能做什么?", "我能帮你导航。")
            sim.playoutEnds()
            sim.advance(8_000)
            // The driver speaks again before the first exchange fades: no clear while they talk.
            sim.emit(DomainVoiceEvent.SpeechStarted)
            sim.advance(15_000)
            assertTrue(sim.bubble.contains("我能帮你导航"), "never cleared while the driver is speaking")
            sim.emit(DomainVoiceEvent.SpeechStopped)
            sim.emit(DomainVoiceEvent.UserTranscript("去励骏庞都", final = true))
            sim.emit(DomainVoiceEvent.ResponseStarted)
            sim.emit(DomainVoiceEvent.AudioDelta("AAAA"))
            sim.emit(DomainVoiceEvent.AssistantTranscript("好的，正在规划路线。", final = true))
            sim.emit(DomainVoiceEvent.ResponseDone("completed"))
            sim.playoutEnds()
            sim.advance(9_000)
            assertEquals("你: 去励骏庞都\n小诺: 好的，正在规划路线。", sim.bubble, "the older wait never clears the newer exchange")
            sim.advance(3_000)
            assertEquals(PLACEHOLDER, sim.bubble)
            sim.controller.stop()
        }

    @Test
    fun aQuestionWaitingOnScreenKeepsTheExchange() =
        runTest(UnconfinedTestDispatcher()) {
            val sim = Sim(this)
            sim.controller.start()
            sim.emit(DomainVoiceEvent.SessionReady(VoiceCatalog.FAKE_MODEL, interruptResponse = true))
            sim.exchange("去励骏庞都", "找到三条路线，选哪一条？")
            sim.playoutEnds()
            sim.questionOnScreen = true // the route list waits for the driver's pick
            sim.advance(60_000)
            assertTrue(sim.bubble.contains("选哪一条"), "a waiting question is never cleared")
            sim.questionOnScreen = false // picked; the list closed
            sim.advance(12_000)
            assertEquals(PLACEHOLDER, sim.bubble)
            sim.controller.stop()
        }

    @Test
    fun aBargeInDoesNotClearTheBubbleUnderTheDriver() =
        runTest(UnconfinedTestDispatcher()) {
            val sim = Sim(this)
            sim.controller.start()
            sim.emit(DomainVoiceEvent.SessionReady(VoiceCatalog.FAKE_MODEL, interruptResponse = true))
            sim.exchange("讲个故事", "从前有一座山，山里有座庙……")
            // The driver talks over the long reply: the session flushes playback (barge-in).
            sim.emit(DomainVoiceEvent.SpeechStarted)
            sim.advance(12_000)
            assertTrue(sim.bubble.contains("从前有一座山"), "never cleared while the driver is speaking")
            sim.emit(DomainVoiceEvent.SpeechStopped)
            sim.emit(DomainVoiceEvent.ResponseDone("cancelled"))
            sim.advance(12_000)
            assertEquals(PLACEHOLDER, sim.bubble, "a silent session clears afterwards")
            sim.controller.stop()
        }

    private companion object {
        const val PLACEHOLDER = "<placeholder>"
    }
}
