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
        assertEquals("第一句话很长。", voice.spoken.first().first)
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
        assertEquals("坏的一句。", voice.spoken.first().first)
        assertEquals("下一个回复。", voice.spoken.last().first)
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

    /** A voice whose clauses starting with "慢" block until cancelled; records cancellations. */
    private class SlowVoice : AssistantVoice {
        val started = Collections.synchronizedList(mutableListOf<String>())
        val cancelled = Collections.synchronizedList(mutableListOf<String>())
        val inFlight = CompletableDeferred<Unit>()
        override suspend fun synthesize(text: String, style: SpeakingStyle, onPcm: suspend (ByteArray) -> Unit) {
            started += text
            if (text.startsWith("慢")) {
                inFlight.complete(Unit)
                try { awaitCancellation() } finally { cancelled += text }
            }
            onPcm(VOICE_PCM)
        }
    }

    private class Live(val revoicer: AssistantVoiceRevoicer, val upstream: Channel<RealtimeEvent>) {
        val seen: MutableList<DomainVoiceEvent> = Collections.synchronizedList(mutableListOf())
        fun send(event: DomainVoiceEvent) = upstream.trySend(RealtimeEvent(0L, event)).getOrThrow()
        suspend fun waitFor(timeoutMs: Long = 3_000, condition: (List<DomainVoiceEvent>) -> Boolean) =
            withTimeout(timeoutMs) { while (!condition(seen.toList())) kotlinx.coroutines.delay(5) }
    }

    private fun live(voice: AssistantVoice, block: suspend Live.() -> Unit) = runBlocking {
        val upstream = Channel<RealtimeEvent>(Channel.UNLIMITED)
        val it = Live(revoicer(voice), upstream)
        val job = async(Dispatchers.Default) { it.revoicer.revoice(upstream.consumeAsFlow()).collect { e -> it.seen += e.payload } }
        it.block()
        upstream.close()
        withTimeout(5_000) { job.await() }
    }

    @Test
    fun aToolCallIsNotDelayedByAClauseBeingSynthesised() {
        val voice = SlowVoice()
        val call = DomainVoiceEvent.ToolCall("c1", "control_climate", mapOf("action" to "power_on"))
        live(voice) {
            send(DomainVoiceEvent.ResponseStarted)
            send(DomainVoiceEvent.SpeechText("慢慢说。"))
            withTimeout(3_000) { voice.inFlight.await() }
            send(call)
            waitFor { call in it }
            assertTrue(seen.none { it.isVoice() }, "the clause is still in flight")
            revoicer.cancelCurrentReply("test")
        }
    }

    @Test
    fun anOpenGuidanceTurnVoidedStopsItsWords() {
        val voice = SlowVoice()
        val opened = DomainVoiceEvent.AppPromptTurn("g1", DomainVoiceEvent.AppPromptTurn.Phase.OPENED)
        val voided = DomainVoiceEvent.AppPromptTurn("g1", DomainVoiceEvent.AppPromptTurn.Phase.VOIDED)
        live(voice) {
            send(DomainVoiceEvent.ResponseStarted)
            send(opened)
            send(DomainVoiceEvent.SpeechText("慢行，前方。"))
            send(DomainVoiceEvent.SpeechText("排队的一句。"))
            send(DomainVoiceEvent.SpeechText("半句"))
            withTimeout(3_000) { voice.inFlight.await() }
            send(voided)
            waitFor { voided in it }
            send(DomainVoiceEvent.ResponseDone("completed"))
            waitFor { DomainVoiceEvent.ResponseDone("completed") in it }
        }
        assertEquals("慢行，", voice.started.first())
        assertTrue("半句" !in voice.started, "the half clause is never synthesised")
        assertEquals(listOf("慢行，"), voice.cancelled.toList())
    }

    @Test
    fun aPromptVoidedBeforeItOpenedDoesNotCutTheReplyBeingSpoken() {
        val voice = FakeVoice()
        val out = run(
            voice,
            DomainVoiceEvent.ResponseStarted,
            DomainVoiceEvent.SpeechText("这句要说完。"),
            DomainVoiceEvent.AppPromptTurn("g9", DomainVoiceEvent.AppPromptTurn.Phase.VOIDED),
            DomainVoiceEvent.AudioDone,
            DomainVoiceEvent.ResponseDone("completed"),
        )
        assertEquals(listOf("这句要说完。"), voice.spoken.map { it.first })
        assertEquals(1, out.count { it.isVoice() })
    }

    @Test
    fun cancelCurrentReplyDropsQueuedWordsAndTheNextReplyIsNotHeldBack() {
        val voice = SlowVoice()
        live(voice) {
            send(DomainVoiceEvent.ResponseStarted)
            send(DomainVoiceEvent.SpeechText("慢的一句。"))
            send(DomainVoiceEvent.SpeechText("排队的一句。"))
            withTimeout(3_000) { voice.inFlight.await() }
            revoicer.cancelCurrentReply("barge_in")
            send(DomainVoiceEvent.AudioDone)
            send(DomainVoiceEvent.ResponseDone("completed"))
            send(DomainVoiceEvent.ResponseStarted)
            send(DomainVoiceEvent.SpeechText("新回复。"))
            send(DomainVoiceEvent.AudioDone)
            waitFor(1_000) { list -> list.count { it == DomainVoiceEvent.ResponseStarted } == 2 && list.any { it.isVoice() } }
        }
        assertEquals("慢的一句。", voice.started.first())
        assertEquals("新回复。", voice.started.last())
        assertEquals(listOf("慢的一句。"), voice.cancelled.toList())
    }

    @Test
    fun aLeftoverHalfClauseIsNotSpokenIntoTheNextReply() {
        val voice = FakeVoice()
        run(
            voice,
            DomainVoiceEvent.ResponseStarted,
            DomainVoiceEvent.SpeechText("没说完"),
            DomainVoiceEvent.ResponseStarted,
            DomainVoiceEvent.SpeechText("新的。"),
            DomainVoiceEvent.AudioDone,
            DomainVoiceEvent.ResponseDone("completed"),
        )
        assertEquals(listOf("新的。"), voice.spoken.map { it.first })
    }

    /** A voice that records start/end order and in-flight count; per-clause delay before and after its PCM. */
    private class TimedVoice(private val delayMs: (String) -> Long) : AssistantVoice {
        val log: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val inFlight = java.util.concurrent.atomic.AtomicInteger(0)
        val maxInFlight = java.util.concurrent.atomic.AtomicInteger(0)
        override suspend fun synthesize(text: String, style: SpeakingStyle, onPcm: suspend (ByteArray) -> Unit) {
            val n = inFlight.incrementAndGet()
            maxInFlight.accumulateAndGet(n) { a, b -> maxOf(a, b) }
            log += "start:$text"
            try {
                kotlinx.coroutines.delay(delayMs(text))
                onPcm(byteArrayOf(text.length.toByte(), 0))
                log += "end:$text"
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    private fun voiced(out: List<DomainVoiceEvent>) = out.filterIsInstance<DomainVoiceEvent.AudioDelta>()
        .map { Base64.getDecoder().decode(it.pcm16leBase64)[0].toInt() }

    @Test
    fun theNextClauseIsRequestedBeforeThePreviousOneReturns() {
        val voice = TimedVoice { if (it.startsWith("一")) 300 else 10 }
        run(voice, DomainVoiceEvent.ResponseStarted, DomainVoiceEvent.SpeechText("一二三。"), DomainVoiceEvent.SpeechText("四五。"),
            DomainVoiceEvent.AudioDone, DomainVoiceEvent.ResponseDone("completed"))
        assertTrue(voice.log.indexOf("start:四五。") < voice.log.indexOf("end:一二三。"), voice.log.toString())
    }

    @Test
    fun clauseOrderHoldsWhenTheSecondFinishesFirst() {
        val voice = TimedVoice { if (it.startsWith("一")) 300 else 10 }
        val out = run(voice, DomainVoiceEvent.ResponseStarted, DomainVoiceEvent.SpeechText("一二三。"), DomainVoiceEvent.SpeechText("四五。"),
            DomainVoiceEvent.AudioDone, DomainVoiceEvent.ResponseDone("completed"))
        assertTrue(voice.log.indexOf("end:四五。") < voice.log.indexOf("end:一二三。"))
        assertEquals(listOf(4, 3), voiced(out))
    }

    @Test
    fun aCancelDuringTheFirstClauseEmitsNothingOfEither() {
        val voice = SlowVoice()
        live(voice) {
            send(DomainVoiceEvent.ResponseStarted)
            send(DomainVoiceEvent.SpeechText("慢的一句。"))
            send(DomainVoiceEvent.SpeechText("快的一句。"))
            withTimeout(3_000) { voice.inFlight.await() }
            waitFor { true }
            withTimeout(3_000) { while ("快的一句。" !in voice.started) kotlinx.coroutines.delay(5) }
            revoicer.cancelCurrentReply("barge_in")
            send(DomainVoiceEvent.AudioDone)
            send(DomainVoiceEvent.ResponseDone("completed"))
            waitFor { DomainVoiceEvent.ResponseDone("completed") in it }
        }
        assertEquals(listOf("慢的一句。"), voice.cancelled.toList())
    }

    @Test
    fun aFailedFirstClauseKeepsTheBufferedSecondSilent() {
        val voice = object : AssistantVoice {
            override suspend fun synthesize(text: String, style: SpeakingStyle, onPcm: suspend (ByteArray) -> Unit) {
                if (text.startsWith("坏")) { kotlinx.coroutines.delay(200); throw AssistantVoiceException("AZURE_TTS_NETWORK") }
                onPcm(VOICE_PCM)
            }
        }
        val out = run(voice, DomainVoiceEvent.ResponseStarted, DomainVoiceEvent.SpeechText("坏的一句。"), DomainVoiceEvent.SpeechText("好的一句。"),
            DomainVoiceEvent.AudioDone, DomainVoiceEvent.ResponseDone("completed"))
        assertEquals(listOf("AZURE_TTS_NETWORK"), failures.toList())
        assertTrue(out.none { it is DomainVoiceEvent.AudioDelta })
    }

    @Test
    fun atMostTwoRequestsAreInFlightPerReply() {
        val voice = TimedVoice { 100 }
        val out = run(voice, DomainVoiceEvent.ResponseStarted, DomainVoiceEvent.SpeechText("一。"), DomainVoiceEvent.SpeechText("二二。"),
            DomainVoiceEvent.SpeechText("三三三。"), DomainVoiceEvent.SpeechText("四四四四。"), DomainVoiceEvent.AudioDone,
            DomainVoiceEvent.ResponseDone("completed"))
        assertEquals(2, voice.maxInFlight.get())
        assertEquals(listOf(2, 3, 4, 5), voiced(out))
    }

    @Test
    fun aSessionOpeningWarmsTheVoice() {
        var warmed = 0
        val voice = object : AssistantVoice {
            override suspend fun synthesize(text: String, style: SpeakingStyle, onPcm: suspend (ByteArray) -> Unit) = Unit
            override fun warmUp() { warmed++ }
        }
        run(voice, DomainVoiceEvent.ResponseStarted)
        assertEquals(1, warmed)
    }

    @Test
    fun theGapIsZeroWhenTheNextClauseIsReadyInTime() {
        // 4800 bytes = 100 ms of 24 kHz PCM16 mono.
        val until = playoutEndMs(1_000, 0.0, 4_800)
        assertEquals(1_100.0, until)
        assertEquals(0L, playoutGapMs(1_050, until))
        assertEquals(1_150.0, playoutEndMs(1_050, until, 2_400))
        assertEquals(40L, playoutGapMs(1_140, until))
    }

    private companion object {
        val VOICE_PCM = byteArrayOf(1, 0, 2, 0)
        val VOICE_B64: String = Base64.getEncoder().encodeToString(VOICE_PCM)
        const val GEMINI_B64 = "QUJD"
    }
}
