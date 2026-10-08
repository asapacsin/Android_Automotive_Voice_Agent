package com.novadrive.app.voice

import com.novadrive.app.DebugVoiceLog
import com.novadrive.app.GeminiApiConfig
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.ProviderCapabilities
import com.novadrive.ingress.realtime.RealtimeEvent
import com.novadrive.ingress.realtime.RealtimeSessionConfig
import com.novadrive.ingress.realtime.RealtimeVoiceProvider
import com.novadrive.ingress.realtime.ToolResult
import com.novadrive.ingress.realtime.VoiceCatalog
import com.novadrive.ingress.realtime.VoiceProviderId
import com.novadrive.ingress.realtime.WorkInjection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking

/** Gemini Live behind the provider seam (ADR-010). Not selected here; composition chooses it. */
class GeminiLiveProvider(
    private val apiConfig: GeminiApiConfig,
    private val client: GeminiLiveClient = GeminiLiveClient(),
    /** ADR-016: when set, this voice speaks Gemini's words; the client must emit SpeechText. */
    private val revoicer: AssistantVoiceRevoicer? = null,
    /** The microphone's judgement that the onset is speech, not a cough or road noise. */
    private val speechEvidence: () -> Boolean = { true },
    /** SPEC-020: the closed uplink segment, read at the end of speech as wait-cue turn evidence. */
    private val endOfSpeechSegment: () -> SpeechUplinkGate.Segment? = { null },
) : RealtimeVoiceProvider {
    /** Secondary constructor: the microphone's measurement of the audio that caused each turn. */
    constructor(
        apiConfig: GeminiApiConfig,
        lastAudioSegment: () -> SpeechUplinkGate.Segment?,
        speechEvidence: () -> Boolean = { true },
        assistantVoice: AssistantVoice? = null,
        /** SPEC-020: false in SILENT_WAIT or sleep, when no wait cue may be spoken. */
        repliesSpoken: () -> Boolean = { true },
    ) : this(
        apiConfig,
        GeminiLiveClient(
            lastAudioSegment = lastAudioSegment,
            speechEvidence = speechEvidence,
            speechTextEvents = assistantVoice != null,
        ),
        assistantVoice?.let { AssistantVoiceRevoicer(it, style = { com.novadrive.app.SpeakingStyleState.current }, quiet = { !repliesSpoken() }) },
        speechEvidence,
        lastAudioSegment,
    )

    override val providerId = "google.gemini.live.direct"
    override val capabilities: ProviderCapabilities = VoiceCatalog.capabilities(VoiceProviderId.GEMINI_LIVE)
    override fun events(): Flow<RealtimeEvent> = revoicer?.revoice(client.events()) ?: client.events()
    override suspend fun connect(config: RealtimeSessionConfig) {
        VoiceCatalog.requireAllowed(VoiceProviderId.GEMINI_LIVE, config.model)
        client.connect(apiConfig)
    }
    override fun connect(model: String) = runBlocking { connect(RealtimeSessionConfig(VoiceProviderId.GEMINI_LIVE, model)) }
    override suspend fun disconnect() = client.disconnect()
    override fun sendAudio(pcm16le: ByteArray) = client.sendAudio(pcm16le)

    /** Gemini has no client-side cancel: the turn is only marked, its held output dropped locally. */
    override suspend fun cancelAssistantResponse(): DomainVoiceEvent {
        client.markClientCancelled(); revoicer?.cancelCurrentReply("client_cancel")
        // Only app-side cancels reach here for Gemini (no clientResponseCancel): the app handled this
        // utterance itself (a pick, an affordance, the wake word, 闭嘴), so no wait cue for it (SPEC-020).
        revoicer?.endWaitCueTurn("client_cancel")
        return DomainVoiceEvent.Interrupted("client_local_only")
    }
    override suspend fun cancelActiveResponse(): DomainVoiceEvent = cancelAssistantResponse()
    override suspend fun injectWorkResult(result: WorkInjection): DomainVoiceEvent {
        client.sendToolResult(result.callId, result.output)
        revoicer?.onToolResultDelivered()  // Gemini answers in the same response, with no new ResponseStarted
        return DomainVoiceEvent.WorkResult(result.callId, result.output)
    }
    override suspend fun sendText(text: String) = client.sendUserText(text)
    override fun sendPrompt(text: String, promptId: String): Boolean = client.sendPrompt(text, promptId)
    override fun discardPendingAudio() = client.discardPendingAudio()
    override fun resumeListening() = client.resumeListening()
    override fun onLocalSpeechActivity(active: Boolean) {
        // ADR-016: a reply the voice has not started playing yet is not audible, so the driver's
        // speech cannot be its echo — it is a new turn and the unplayed reply is dropped. While
        // playback runs, the session's barge-in decision applies instead (onPlaybackFlushed).
        // A raw onset can be a cough or road noise, so only speech evidence drops the reply.
        if (active && !playbackActive && revoicer != null && speechEvidence()) {
            revoicer.cancelCurrentReply("driver_onset_unplayed")
        }
        // SPEC-020: Gemini's end of speech is local only. The gate closed after HANGOVER_MS of silence,
        // and the snapshot is the segment it just closed.
        if (active) revoicer?.onDriverSpeech(true)
        else revoicer?.onDriverSpeech(false, SpeechUplinkGate.HANGOVER_MS.toLong(), endOfSpeechSegment()?.isSuspicious() == true)
        if (!active && revoicer == null) DebugVoiceLog.log("wait_cue_skipped reason=no_assistant_voice")
        client.onLocalSpeechActivity(active)
    }
    override fun interrupt() = runBlocking { cancelAssistantResponse() }
    override fun sendToolResult(result: ToolResult) = runBlocking {
        injectWorkResult(WorkInjection(result.callId, result.ok, result.output))
    }
    override fun close() = client.close()

    @Volatile private var playbackActive = false

    override fun onPlaybackActiveChanged(active: Boolean) {
        playbackActive = active
        client.onPlaybackActiveChanged(active)
    }

    /** The session flushed the reply (its barge-in, a cancel, an interrupt, a reconnect). */
    override fun onPlaybackFlushed() {
        revoicer?.cancelCurrentReply("playback_flushed")
    }

    /** Whether an assistant voice speaks for this provider (ADR-016). */
    internal val revoicing: Boolean get() = revoicer != null
}
