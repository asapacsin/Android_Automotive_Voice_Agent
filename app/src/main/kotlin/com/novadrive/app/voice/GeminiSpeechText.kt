package com.novadrive.app.voice

import com.novadrive.ingress.realtime.DomainVoiceEvent

/**
 * ADR-016 / SPEC-019 R1–R3: when an assistant voice speaks instead of Gemini's audio, the words
 * Gemini says leave the client as [DomainVoiceEvent.SpeechText], judged exactly like the audio
 * they replace — a driver turn's through the claim gate ([gate] true = held or dropped), a GUIDANCE
 * turn's unjudged but never once voided. Only words before generationComplete ([settled]) are
 * spoken: audio after the verdict is not played either. Called under the client's lock.
 */
internal class GeminiSpeechText(
    private val enabled: Boolean,
    private val emit: (DomainVoiceEvent) -> Unit,
    private val gate: (DomainVoiceEvent) -> Boolean,
    private val voided: () -> Boolean,
) {
    fun reply(text: String, settled: Boolean) {
        val event = event(text, settled) ?: return
        if (!gate(event)) emit(event)
    }

    fun guidance(text: String, settled: Boolean) {
        val event = event(text, settled) ?: return
        if (!voided()) emit(event)
    }

    private fun event(text: String, settled: Boolean): DomainVoiceEvent.SpeechText? =
        if (!enabled || text.isEmpty() || settled) null else DomainVoiceEvent.SpeechText(text)
}
