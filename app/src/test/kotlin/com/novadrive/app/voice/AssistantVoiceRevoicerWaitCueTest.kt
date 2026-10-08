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
        block: suspend Run.() -> Unit,
    ): List<String> {
        val out = mutableListOf<RealtimeEvent>()
        runTest {
            val up = Channel<RealtimeEvent>(Channel.UNLIMITED)
            val revoicer = AssistantVoiceRevoicer(
                voice, style = { SpeakingStyle.TSUNDERE }, onFailure = { failures += it },
                clock = { testScheduler.currentTime }, quiet = { quiet },
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
    fun a1_chatAckAt1800msWhenNothingIsAudible() {
        val out = heard {
            send(DomainVoiceEvent.SpeechStopped)
            after(1_700)
        }
        assertEquals(emptyList<String>(), out, "nothing before 1.8 s")
        assertEquals(listOf(WaitCues.ACK_CHAT), heard { send(DomainVoiceEvent.SpeechStopped); after(1_900) })
    }

    @Test
    fun a1_actionAckAt1000msWhenAToolCallWasSeen() {
        val out = heard {
            send(DomainVoiceEvent.SpeechStopped)
            after(300)
            send(DomainVoiceEvent.ToolCall("c1", "control_climate", emptyMap()))
            after(800)
        }
        assertEquals(listOf(WaitCues.ACK_ACTION), out)
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
            after(3_000)
        }
        assertEquals(listOf(WaitCues.ACK_ACTION), out[0], "C1 at 1.3 s, on the tool call's arrival")
        assertEquals(listOf(WaitCues.ACK_ACTION), out[1], "and no ACK_CHAT at 1.8 s")
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
        assertEquals(listOf(WaitCues.ACK_CHAT, WaitCues.PROVIDER_SLOW, WaitCues.STILL_WAITING), out)
    }

    @Test
    fun a2_toolRunningUsesTheToolPhrase() {
        val out = heard {
            send(DomainVoiceEvent.SpeechStopped, DomainVoiceEvent.ResponseStarted, DomainVoiceEvent.ToolCall("c1", "navigate_to", emptyMap()))
            after(13_000)
        }
        assertEquals(listOf(WaitCues.ACK_ACTION, WaitCues.TOOL_ROUTE, WaitCues.STILL_WAITING), out)
    }

    @Test
    fun a2_verifyingWhenAReplyStartedButNothingIsAudible() {
        val out = heard {
            send(DomainVoiceEvent.SpeechStopped)
            after(2_000)
            send(DomainVoiceEvent.ResponseStarted)
            after(4_000)
        }
        assertEquals(listOf(WaitCues.ACK_CHAT, WaitCues.VERIFYING), out)
    }

    @Test
    fun a3_aReplyArrivingDuringACuePlaysAfterItInOrder() {
        val cue = CompletableDeferred<Unit>()
        val out = heard(Voice(mapOf(WaitCues.ACK_CHAT to cue))) {
            send(DomainVoiceEvent.SpeechStopped)
            after(1_800)
            send(DomainVoiceEvent.ResponseStarted, DomainVoiceEvent.SpeechText("今天晴。"), DomainVoiceEvent.SpeechText("气温二十度。"))
            cue.complete(Unit)
            scope.runCurrent()
            send(DomainVoiceEvent.AudioDone)
        }
        assertEquals(listOf(WaitCues.ACK_CHAT, "今天晴。", "气温二十度。"), out)
    }

    @Test
    fun a3_cancelStopsTheCueInFlightAndTheTimers() {
        val cue = CompletableDeferred<Unit>()
        val out = heard(Voice(mapOf(WaitCues.ACK_CHAT to cue))) {
            send(DomainVoiceEvent.SpeechStopped)
            after(1_800)
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
            after(1_400)
        }
        assertEquals(listOf(WaitCues.ACK_CHAT), out)
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
            send(DomainVoiceEvent.SpeechStopped); after(2_000)
            send(DomainVoiceEvent.SpeechStarted, DomainVoiceEvent.SpeechStopped); after(2_000)
        }
        assertEquals(listOf(WaitCues.ACK_CHAT, WaitCues.ACK_CHAT), out)
        assertEquals(1, voice.spoken.count { it == WaitCues.ACK_CHAT })
    }

    // R2: the turn remembers everything since the driver's onset; the clock runs from his last word.

    @Test
    fun r2_toolCallDuringSpeechGivesActionAckThenToolRunning() {
        val out = heard {
            revoicer.onDriverSpeech(true)
            send(DomainVoiceEvent.ToolCall("c1", "control_climate", emptyMap()))
            revoicer.onDriverSpeech(false)
            after(6_000)
        }
        assertEquals(listOf(WaitCues.ACK_ACTION, WaitCues.TOOL_DEFAULT), out)
    }

    @Test
    fun r2_replyStartedDuringSpeechGivesVerifyingNotProviderSlow() {
        val out = heard {
            revoicer.onDriverSpeech(true)
            send(DomainVoiceEvent.ResponseStarted)
            revoicer.onDriverSpeech(false)
            after(6_000)
        }
        assertEquals(listOf(WaitCues.ACK_CHAT, WaitCues.VERIFYING), out)
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
        chat += heard { revoicer.onDriverSpeech(true); revoicer.onDriverSpeech(false, silenceMs = 1_200); after(599) }
        chat += heard { revoicer.onDriverSpeech(true); revoicer.onDriverSpeech(false, silenceMs = 1_200); after(600) }
        assertEquals(listOf(emptyList(), listOf(WaitCues.ACK_CHAT)), chat, "C2 600 ms after the end call")
        val action = heard {
            revoicer.onDriverSpeech(true)
            send(DomainVoiceEvent.ToolCall("c1", "control_climate", emptyMap()))
            revoicer.onDriverSpeech(false, silenceMs = 1_200)
            scope.runCurrent()
        }
        assertEquals(listOf(WaitCues.ACK_ACTION), action, "C1 at once")
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
        assertEquals(listOf(WaitCues.ACK_CHAT), out, "no C5, no C6")
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
        }
        assertEquals(listOf(listOf(WaitCues.ACK_CHAT)), out)
    }

    // R6: a cue's failure is its own.

    @Test
    fun r6_aFailedCueNeitherFailsNorSilencesTheReply() {
        val failures = mutableListOf<String>()
        val out = heard(Voice(failing = setOf(WaitCues.ACK_CHAT)), failures = failures) {
            send(DomainVoiceEvent.SpeechStopped)
            after(2_000)
            send(DomainVoiceEvent.ResponseStarted, DomainVoiceEvent.SpeechText("好的。"), DomainVoiceEvent.AudioDone)
        }
        assertEquals(listOf("好的。"), out)
        assertEquals(emptyList<String>(), failures)
    }
}
