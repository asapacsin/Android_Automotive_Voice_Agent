package com.novadrive.app.voice

import com.novadrive.app.SpeakingStyle
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.RealtimeEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.Collections

/** ADR-016: the assistant voice speaks the released words, in order, and stops on an interrupt. */
class AssistantVoiceRevoicerTest {
    private class FakeVoice(
        private val block: suspend (String) -> Unit = {},
    ) : AssistantVoice {
        val spoken: MutableList<Pair<String, SpeakingStyle>> = Collections.synchronizedList(mutableListOf())

        override suspend fun synthesize(text: String, style: SpeakingStyle, onPcm: suspend (ByteArray) -> Unit) {
            spoken += text to style
            block(text)
            onPcm(VOICE_PCM)
        }
    }

    private val failures = Collections.synchronizedList(mutableListOf<String>())

    private fun revoicer(voice: AssistantVoice, style: SpeakingStyle = SpeakingStyle.TSUNDERE) =
        AssistantVoiceRevoicer(voice, style = { style }, onFailure = { failures += it }, clock = { 0L })

    private fun ev(event: DomainVoiceEvent) = RealtimeEvent(0L, event)

    private fun run(voice: AssistantVoice, vararg events: DomainVoiceEvent): List<DomainVoiceEvent> = runBlocking {
        withTimeout(5_000) { revoicer(voice).revoice(flowOf(*events.map(::ev).toTypedArray())).toList().map { it.payload } }
    }

    private fun DomainVoiceEvent.isVoice() = this is DomainVoiceEvent.AudioDelta && pcm16leBase64 == VOICE_B64
    private fun DomainVoiceEvent.isGemini() = this is DomainVoiceEvent.AudioDelta && pcm16leBase64 == GEMINI_B64

    @Test
    fun theReplyIsSpokenInTheVoiceAndGeminiAudioIsDiscarded() {
        val voice = FakeVoice()
        val out = run(
            voice,
            DomainVoiceEvent.ResponseStarted,
            DomainVoiceEvent.AudioDelta(GEMINI_B64),
            DomainVoiceEvent.SpeechText("好的，"),
            DomainVoiceEvent.AudioDelta(GEMINI_B64),
            DomainVoiceEvent.SpeechText("空调已经开了"),
            DomainVoiceEvent.AudioDone,
            DomainVoiceEvent.AssistantTranscript("好的，空调已经开了", final = true),
            DomainVoiceEvent.ResponseDone("completed"),
        )
        assertEquals(listOf("好的，" to SpeakingStyle.TSUNDERE, "空调已经开了" to SpeakingStyle.TSUNDERE), voice.spoken.toList())
        assertTrue(out.none { it.isGemini() })
        assertTrue(out.none { it is DomainVoiceEvent.SpeechText }, "words are consumed, not forwarded")
        val started = out.indexOf(DomainVoiceEvent.ResponseStarted)
        val voiced = out.indices.filter { out[it].isVoice() }
        assertEquals(2, voiced.size)
        assertTrue(voiced.all { it > started && it < out.indexOf(DomainVoiceEvent.AudioDone) })
        assertTrue(out.indexOf(DomainVoiceEvent.AudioDone) < out.indexOf(DomainVoiceEvent.ResponseDone("completed")))
    }

    @Test
    fun otherEventsKeepTheirOrder() {
        val call = DomainVoiceEvent.ToolCall("c1", "control_climate", mapOf("action" to "power_on"))
        val out = run(
            FakeVoice(),
            DomainVoiceEvent.UserTranscript("有点热", final = true),
            DomainVoiceEvent.ResponseStarted,
            call,
            DomainVoiceEvent.SpeechText("好的。"),
            DomainVoiceEvent.AudioDone,
            DomainVoiceEvent.ResponseDone("completed"),
        ).filterNot { it is DomainVoiceEvent.AudioDelta }
        assertEquals(
            listOf(
                DomainVoiceEvent.UserTranscript("有点热", final = true), DomainVoiceEvent.ResponseStarted, call,
                DomainVoiceEvent.AudioDone, DomainVoiceEvent.ResponseDone("completed"),
            ),
            out,
        )
    }

    @Test
    fun anInterruptCancelsTheClauseInFlightAndDiscardsTheRest() = runBlocking {
        val inFlight = CompletableDeferred<Unit>()
        val voice = FakeVoice { text -> if (text.startsWith("第一")) { inFlight.complete(Unit); awaitCancellation() } }
        val upstream = Channel<RealtimeEvent>(Channel.UNLIMITED)
        val result = async(Dispatchers.Default) { revoicer(voice).revoice(upstream.consumeAsFlow()).toList().map { it.payload } }
        upstream.send(ev(DomainVoiceEvent.ResponseStarted))
        upstream.send(ev(DomainVoiceEvent.SpeechText("第一句话很长。")))
        upstream.send(ev(DomainVoiceEvent.SpeechText("第二句。")))
        withTimeout(5_000) { inFlight.await() }
        upstream.send(ev(DomainVoiceEvent.SpeechStarted))
        upstream.send(ev(DomainVoiceEvent.Interrupted("server_vad")))
        upstream.send(ev(DomainVoiceEvent.ResponseDone("cancelled")))
        upstream.close()
        val out = withTimeout(5_000) { result.await() }
        assertEquals(listOf("第一句话很长。"), voice.spoken.map { it.first }, "the queued clause is never synthesised")
        assertTrue(out.none { it is DomainVoiceEvent.AudioDelta })
        assertTrue(out.indexOf(DomainVoiceEvent.SpeechStarted) < out.indexOf(DomainVoiceEvent.Interrupted("server_vad")))
        assertTrue(out.contains(DomainVoiceEvent.ResponseDone("cancelled")))
    }

    @Test
    fun bargeInEventsBypassASlowClause() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val voice = FakeVoice { release.await() }
        val upstream = Channel<RealtimeEvent>(Channel.UNLIMITED)
        val seen = Collections.synchronizedList(mutableListOf<DomainVoiceEvent>())
        val job = async(Dispatchers.Default) { revoicer(voice).revoice(upstream.consumeAsFlow()).collect { seen += it.payload } }
        upstream.send(ev(DomainVoiceEvent.ResponseStarted))
        upstream.send(ev(DomainVoiceEvent.SpeechText("慢慢说。")))
        upstream.send(ev(DomainVoiceEvent.SpeechStarted))
        withTimeout(5_000) { while (DomainVoiceEvent.SpeechStarted !in seen.toList()) kotlinx.coroutines.delay(5) }
        assertTrue(seen.none { it is DomainVoiceEvent.AudioDelta }, "the clause is still being synthesised")
        release.complete(Unit)
        upstream.close()
        withTimeout(5_000) { job.await() }
        assertTrue(seen.any { it.isVoice() })
    }

    @Test
    fun aFailureIsReportedOnceAndTheRestOfThatReplyIsNotSpoken() {
        val voice = FakeVoice { text -> if (text.startsWith("坏")) throw AssistantVoiceException("AZURE_TTS_AUTH") }
        val out = run(
            voice,
            DomainVoiceEvent.ResponseStarted,
            DomainVoiceEvent.SpeechText("坏的一句。"),
            DomainVoiceEvent.SpeechText("后面一句。"),
            DomainVoiceEvent.AudioDone,
            DomainVoiceEvent.AssistantTranscript("坏的一句。后面一句。", final = true),
            DomainVoiceEvent.ResponseDone("completed"),
            DomainVoiceEvent.ResponseStarted,
            DomainVoiceEvent.SpeechText("下一个回复。"),
            DomainVoiceEvent.AudioDone,
            DomainVoiceEvent.ResponseDone("completed"),
        )
        assertEquals(listOf("AZURE_TTS_AUTH"), failures.toList())
        assertEquals(listOf("坏的一句。", "下一个回复。"), voice.spoken.map { it.first })
        assertTrue(out.contains(DomainVoiceEvent.AssistantTranscript("坏的一句。后面一句。", final = true)), "the subtitle still shows")
        assertEquals(1, out.count { it.isVoice() })
    }

    @Test
    fun aGuidanceTurnIsSpokenBeforeItCompletes() {
        val voice = FakeVoice()
        val opened = DomainVoiceEvent.AppPromptTurn("g1", DomainVoiceEvent.AppPromptTurn.Phase.OPENED)
        val completed = DomainVoiceEvent.AppPromptTurn("g1", DomainVoiceEvent.AppPromptTurn.Phase.COMPLETED)
        val out = run(
            voice,
            DomainVoiceEvent.ResponseStarted,
            opened,
            DomainVoiceEvent.SpeechText("前方五百米右转"),
            DomainVoiceEvent.AppPromptTranscript("g1", "前方五百米右转"),
            DomainVoiceEvent.AudioDone,
            DomainVoiceEvent.ResponseDone("completed"),
            completed,
        )
        assertEquals(listOf("前方五百米右转"), voice.spoken.map { it.first })
        val voiced = out.indexOfFirst { it.isVoice() }
        assertTrue(voiced > out.indexOf(opened) && voiced < out.indexOf(DomainVoiceEvent.AudioDone))
    }

    private companion object {
        val VOICE_PCM = byteArrayOf(1, 0, 2, 0)
        val VOICE_B64: String = Base64.getEncoder().encodeToString(VOICE_PCM)
        const val GEMINI_B64 = "QUJD"
    }
}
