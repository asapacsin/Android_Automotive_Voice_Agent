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
    private class Voice(val hold: Map<String, CompletableDeferred<Unit>> = emptyMap()) : AssistantVoice {
        val spoken = mutableListOf<String>()
        override suspend fun synthesize(text: String, style: SpeakingStyle, onPcm: suspend (ByteArray) -> Unit) {
            spoken += text
            hold[text]?.await()
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
        block: suspend Run.() -> Unit,
    ): List<String> {
        val out = mutableListOf<RealtimeEvent>()
        runTest {
            val up = Channel<RealtimeEvent>(Channel.UNLIMITED)
            val revoicer = AssistantVoiceRevoicer(
                voice, style = { SpeakingStyle.TSUNDERE }, onFailure = {},
                clock = { testScheduler.currentTime }, quiet = { quiet },
            )
            val job = launch { revoicer.revoice(up.consumeAsFlow()).toList(out) }
            runCurrent()
            Run(this, up, revoicer).block()
            up.close()
            job.join()
        }
        return out.mapNotNull { (it.payload as? DomainVoiceEvent.AudioDelta)?.let { a -> String(Base64.getDecoder().decode(a.pcm16leBase64)) } }
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
}
