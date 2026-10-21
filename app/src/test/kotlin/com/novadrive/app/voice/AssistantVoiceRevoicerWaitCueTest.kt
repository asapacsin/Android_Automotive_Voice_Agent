package com.novadrive.app.voice

import com.novadrive.app.SpeakingStyle
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.DomainVoiceEvent.AppPromptTurn.Phase
import com.novadrive.ingress.realtime.RealtimeEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Base64

/** SPEC-020 A1–A3, A5: wait cues on a virtual clock. Audio carries its text so order is visible. */
class AssistantVoiceRevoicerWaitCueTest {
    private class Voice(
        val hold: Map<String, CompletableDeferred<Unit>> = emptyMap(),
        val failing: Set<String> = emptySet(),
    ) : AssistantVoice {
        val spoken = mutableListOf<String>()
        override suspend fun synthesize(text: String, style: SpeakingStyle, onPcm: suspend (ByteArray) -> Unit) {
            spoken += text
            hold[text]?.await()
            if (text in failing) throw AssistantVoiceException("TEST_FAIL")
            onPcm(text.toByteArray())
        }
    }

    private class Run(val scope: TestScope, val up: Channel<RealtimeEvent>, val revoicer: AssistantVoiceRevoicer) {
        suspend fun send(vararg events: DomainVoiceEvent) {
            events.forEach { up.send(RealtimeEvent(0L, it)) }
            scope.runCurrent()
        }
        fun after(ms: Long) { scope.advanceTimeBy(ms); scope.runCurrent() }
    }

    private fun heard(
        voice: Voice = Voice(),
        quiet: Boolean = false,
        failures: MutableList<String> = mutableListOf(),
        prefill: Boolean = false,
        block: suspend Run.() -> Unit,
    ): List<String> {
        val out = mutableListOf<RealtimeEvent>()
        runTest {
            val up = Channel<RealtimeEvent>(Channel.UNLIMITED)
            val revoicer = AssistantVoiceRevoicer(
                voice, style = { SpeakingStyle.TSUNDERE }, onFailure = { failures += it },
                clock = { testScheduler.currentTime }, quiet = { quiet }, prefillAcks = prefill,
            )
            val job = launch { revoicer.revoice(up.consumeAsFlow()).toList(out) }
            runCurrent()
            Run(this, up, revoicer).block()
            up.close()
            job.join()
        }
        return out.mapNotNull {
            when (val p = it.payload) {
                is DomainVoiceEvent.AudioDelta -> p.pcm16leBase64
                is DomainVoiceEvent.WaitCueAudio -> p.pcm16leBase64
                else -> null
            }?.let { b64 -> String(Base64.getDecoder().decode(b64)) }
        }
    }

    @Test
    fun prefill_theAcksAreSynthesisedAtOnsetAndTheCueComesFromTheCache() {
        val voice = Voice()
        var atOnset = emptyList<String>()
        val out = heard(voice, prefill = true) {
            send(DomainVoiceEvent.SpeechStarted)
            atOnset = voice.spoken.toList()
            send(DomainVoiceEvent.SpeechStopped)
            after(7_100)
        }
        assertEquals(listOf(WaitCues.PROGRESS, WaitCues.DELAY), atOnset, "synthesised while he is talking")
        assertEquals(listOf(WaitCues.PROGRESS), out, "the cue itself")
        assertEquals(2, voice.spoken.size, "from the cache: no second synthesis")
    }

    @Test
    fun prefill_nothingIsSynthesisedWhileQuiet() {
        val voice = Voice()
        heard(voice, quiet = true, prefill = true) { send(DomainVoiceEvent.SpeechStarted); after(100) }
        assertEquals(emptyList<String>(), voice.spoken)
    }

    @Test
    fun a1_chatAckAt1800msWhenNothingIsAudible() {
        val out = heard {
            send(DomainVoiceEvent.SpeechStopped)
            after(6_900)
        }
        assertEquals(emptyList<String>(), out, "nothing spoken before 7 s")
        assertEquals(listOf(WaitCues.PROGRESS), heard { send(DomainVoiceEvent.SpeechStopped); after(7_100) })
    }

    @Test
    fun a1_actionAckAt1000msWhenAToolCallWasSeen() {
        val out = heard {
            send(DomainVoiceEvent.SpeechStopped)
            after(300)
            send(DomainVoiceEvent.ToolCall("c1", "control_climate", emptyMap()))
            after(800)
        }
        assertEquals(emptyList<String>(), out, "a tool call does not speak at 1 s")
        assertEquals(listOf(WaitCues.PROGRESS), heard {
            send(DomainVoiceEvent.SpeechStopped, DomainVoiceEvent.ToolCall("c1", "control_climate", emptyMap()))
            after(7_100)
        })
    }

    @Test
    fun a1_toolCallBetweenTheThresholdsFiresTheActionAckOnArrival() {
        val out = mutableListOf<List<String>>()
        out += heard {
            send(DomainVoiceEvent.SpeechStopped)
            after(1_300)
            send(DomainVoiceEvent.ToolCall("c1", "control_climate", emptyMap()))
        }
        out += heard {
            send(DomainVoiceEvent.SpeechStopped)
            after(1_300)
            send(DomainVoiceEvent.ToolCall("c1", "control_climate", emptyMap()))
            after(6_000)
        }
        assertEquals(emptyList<String>(), out[0], "nothing at 1.3 s")
        assertEquals(listOf(WaitCues.PROGRESS), out[1], "one line at 7 s, not a second status")
    }

    @Test
    fun a1_noCueWhenTheReplyIsAudibleFirst() {
        val out = heard {
            send(DomainVoiceEvent.SpeechStopped)
            after(500)
            send(DomainVoiceEvent.ResponseStarted, DomainVoiceEvent.SpeechText("好的。"), DomainVoiceEvent.AudioDone)
            after(20_000)
        }
        assertEquals(listOf("好的。"), out)
    }

    @Test
    fun a2_providerSlowThenStillWaitingEachOnce() {
        val out = heard { send(DomainVoiceEvent.SpeechStopped); after(30_000) }
        assertEquals(listOf(WaitCues.PROGRESS, WaitCues.DELAY), out)
    }

    @Test
    fun a2_toolRunningUsesTheToolPhrase() {
        val out = heard {
            send(DomainVoiceEvent.SpeechStopped, DomainVoiceEvent.ResponseStarted, DomainVoiceEvent.ToolCall("c1", "navigate_to", emptyMap()))
            after(13_000)
        }
        assertEquals(listOf(WaitCues.PROGRESS, WaitCues.DELAY), out)
    }

    @Test
    fun a2_verifyingWhenAReplyStartedButNothingIsAudible() {
        val out = heard {
            send(DomainVoiceEvent.SpeechStopped)
            after(2_000)
            send(DomainVoiceEvent.ResponseStarted)
            after(5_100)
        }
        assertEquals(listOf(WaitCues.PROGRESS), out)
    }

    @Test
    fun a3_aReplyArrivingDuringACuePlaysAfterItInOrder() {
        val cue = CompletableDeferred<Unit>()
        val out = heard(Voice(mapOf(WaitCues.PROGRESS to cue))) {
            send(DomainVoiceEvent.SpeechStopped)
            after(7_000)
            send(DomainVoiceEvent.ResponseStarted, DomainVoiceEvent.SpeechText("今天晴。"), DomainVoiceEvent.SpeechText("气温二十度。"))
            cue.complete(Unit)
            scope.runCurrent()
            send(DomainVoiceEvent.AudioDone)
        }
        assertEquals(listOf(WaitCues.PROGRESS, "今天晴。", "气温二十度。"), out)
    }

    @Test
    fun a3_cancelStopsTheCueInFlightAndTheTimers() {
        val cue = CompletableDeferred<Unit>()
        val out = heard(Voice(mapOf(WaitCues.PROGRESS to cue))) {
            send(DomainVoiceEvent.SpeechStopped)
            after(7_000)
            revoicer.cancelCurrentReply("barge_in")
            cue.complete(Unit)
            after(20_000)
        }
        assertEquals(emptyList<String>(), out)
    }

    @Test
    fun interruptedAndDriverSpeechCancelTheTimers() {
        assertEquals(emptyList<String>(), heard { send(DomainVoiceEvent.SpeechStopped, DomainVoiceEvent.Interrupted("x")); after(20_000) })
        assertEquals(emptyList<String>(), heard { send(DomainVoiceEvent.SpeechStopped, DomainVoiceEvent.SpeechStarted); after(20_000) })
    }

    @Test
    fun localEndOfSpeechStartsTheClockOncePerTurn() {
        val out = heard {
            revoicer.onDriverSpeech(false)  // Gemini: forwarded by the provider adapter
            after(500)
            send(DomainVoiceEvent.SpeechStopped)  // the same turn reported again: no second clock
            after(6_600)
        }
        assertEquals(listOf(WaitCues.PROGRESS), out)
    }

    @Test
    fun a5_noCueWhenQuietOrAGuidancePromptIsOpen() {
        assertEquals(emptyList<String>(), heard(quiet = true) { send(DomainVoiceEvent.SpeechStopped); after(20_000) })
        assertEquals(emptyList<String>(), heard {
            send(DomainVoiceEvent.AppPromptTurn("p1", Phase.OPENED), DomainVoiceEvent.SpeechStopped)
            after(20_000)
        })
    }

    @Test
    fun cueAudioIsSynthesisedOncePerTextAndStyle() {
        val voice = Voice()
        val out = heard(voice) {
            send(DomainVoiceEvent.SpeechStopped); after(7_100)
            send(DomainVoiceEvent.SpeechStarted, DomainVoiceEvent.SpeechStopped); after(7_100)
        }
        assertEquals(listOf(WaitCues.PROGRESS, WaitCues.PROGRESS), out)
        assertEquals(1, voice.spoken.count { it == WaitCues.PROGRESS })
    }

    // R2: the turn remembers everything since the driver's onset; the clock runs from his last word.

    @Test
    fun r2_toolCallDuringSpeechGivesActionAckThenToolRunning() {
        val out = heard {
            revoicer.onDriverSpeech(true)
            send(DomainVoiceEvent.ToolCall("c1", "control_climate", emptyMap()))
            revoicer.onDriverSpeech(false)
            after(7_100)
        }
        assertEquals(listOf(WaitCues.PROGRESS), out)
    }

    @Test
    fun r2_replyStartedDuringSpeechGivesVerifyingNotProviderSlow() {
        val out = heard {
            revoicer.onDriverSpeech(true)
            send(DomainVoiceEvent.ResponseStarted)
            revoicer.onDriverSpeech(false)
            after(7_100)
        }
        assertEquals(listOf(WaitCues.PROGRESS), out)
    }

    @Test
    fun r2_replyAudibleDuringSpeechStartsNoClock() {
        val out = heard {
            revoicer.onDriverSpeech(true)
            send(DomainVoiceEvent.ResponseStarted, DomainVoiceEvent.SpeechText("好的。"), DomainVoiceEvent.AudioDone)
            revoicer.onDriverSpeech(false)
            after(20_000)
        }
        assertEquals(listOf("好的。"), out)
    }

    @Test
    fun r2_theClockIsBackdatedByTheSilenceAlreadyHeard() {
        val chat = mutableListOf<List<String>>()
        chat += heard { revoicer.onDriverSpeech(true); revoicer.onDriverSpeech(false, silenceMs = 1_200); after(5_799) }
        chat += heard { revoicer.onDriverSpeech(true); revoicer.onDriverSpeech(false, silenceMs = 1_200); after(5_800) }
        assertEquals(listOf(emptyList(), listOf(WaitCues.PROGRESS)), chat, "7 s from his last word")
        val action = heard {
            revoicer.onDriverSpeech(true)
            send(DomainVoiceEvent.ToolCall("c1", "control_climate", emptyMap()))
            revoicer.onDriverSpeech(false, silenceMs = 1_200)
            scope.runCurrent()
        }
        assertEquals(emptyList<String>(), action, "a tool call is not a reason to speak at once")
    }

    // R3: once her words are queued, no cue.

    @Test
    fun r3_noCueOnceReplyWordsAreQueuedEvenIfTheirAudioIsLate() {
        val held = CompletableDeferred<Unit>()
        val out = heard(Voice(mapOf("今天晴。" to held))) {
            send(DomainVoiceEvent.SpeechStopped)
            after(500)
            send(DomainVoiceEvent.ResponseStarted, DomainVoiceEvent.SpeechText("今天晴。"))
            after(3_000)
            held.complete(Unit)
            send(DomainVoiceEvent.AudioDone)
            after(20_000)
        }
        assertEquals(listOf("今天晴。"), out)
    }

    // R4: cues stay truthful.

    @Test
    fun r4_aResponseDoneWithNothingAudibleEndsTheCues() {
        val out = heard {
            send(DomainVoiceEvent.SpeechStopped, DomainVoiceEvent.ResponseStarted)
            after(2_000)
            send(DomainVoiceEvent.ResponseDone("completed"))
            after(20_000)
        }
        assertEquals(emptyList<String>(), out, "an empty finished turn does not get a status line")
    }

    @Test
    fun r4_suspiciousAudioWithoutProviderEventsGivesNoCue() {
        assertEquals(emptyList<String>(), heard { revoicer.onDriverSpeech(false, suspiciousAudio = true); after(20_000) })
    }

    @Test
    fun r4_lateEvidenceFiresTheDueAckOnArrival() {
        val out = mutableListOf<List<String>>()
        out += heard {
            revoicer.onDriverSpeech(false, suspiciousAudio = true)
            after(2_500)
            send(DomainVoiceEvent.ResponseStarted)
            after(4_600)
        }
        assertEquals(listOf(listOf(WaitCues.PROGRESS)), out)
    }

    // R6: a cue's failure is its own.

    @Test
    fun r6_aFailedCueNeitherFailsNorSilencesTheReply() {
        val failures = mutableListOf<String>()
        val out = heard(Voice(failing = setOf(WaitCues.PROGRESS)), failures = failures) {
            send(DomainVoiceEvent.SpeechStopped)
            after(7_100)
            send(DomainVoiceEvent.ResponseStarted, DomainVoiceEvent.SpeechText("好的。"), DomainVoiceEvent.AudioDone)
        }
        assertEquals(listOf("好的。"), out)
        assertEquals(emptyList<String>(), failures)
    }

    // R7: an utterance the app handled itself gets no cue; other cancels leave the new turn alone.

    @Test
    fun r7_aClientCancelEndsTheTurnBeforeItsClockStarts() {
        val out = heard {
            revoicer.onDriverSpeech(true)
            send(DomainVoiceEvent.ResponseStarted)
            revoicer.endWaitCueTurn("client_cancel")
            revoicer.onDriverSpeech(false, silenceMs = 1_200)
            after(20_000)
        }
        assertEquals(emptyList<String>(), out)
    }

    @Test
    fun r7_aPlaybackFlushAfterOnsetLeavesTheNewTurnAlive() {
        val out = heard {
            revoicer.onDriverSpeech(true)
            revoicer.cancelCurrentReply("playback_flushed")
            revoicer.onDriverSpeech(false, silenceMs = 1_200)
            after(7_200)
        }
        assertEquals(listOf(WaitCues.PROGRESS), out)
    }

    // R8: Gemini answers a tool result in the same response.

    @Test
    fun r8_aDeliveredToolResultLetsTurnDoneEndTheCues() {
        val out = heard {
            revoicer.onDriverSpeech(true)
            send(DomainVoiceEvent.ResponseStarted, DomainVoiceEvent.ToolCall("c1", "control_climate", emptyMap()))
            revoicer.onDriverSpeech(false)
            after(1_100)
            revoicer.onToolResultDelivered()
            send(DomainVoiceEvent.ResponseDone("completed"))
            after(20_000)
        }
        assertEquals(emptyList<String>(), out, "the turn ended before 7 s, so nothing is said")
    }
}
