package com.novadrive.app.voice

import android.content.Context
import com.novadrive.app.BaiduApiConfig
import com.novadrive.app.NavigationState
import com.novadrive.app.BaiduRuntimeProvider
import com.novadrive.app.resolvedOutputSampleRateHz
import com.novadrive.evaluation.EventType
import com.novadrive.evaluation.Telemetry
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.ProviderCapabilities
import com.novadrive.ingress.realtime.RealtimeAudioConfig
import com.novadrive.ingress.realtime.RealtimeSessionConfig
import com.novadrive.ingress.realtime.RealtimeVoiceProvider
import com.novadrive.ingress.realtime.ToolDispatchResult
import com.novadrive.ingress.realtime.VoiceCatalog
import com.novadrive.ingress.realtime.VoiceProviderId
import com.novadrive.ingress.realtime.VoiceSessionCallbacks
import com.novadrive.ingress.realtime.VoiceSessionController as CoreVoiceSessionController
import com.novadrive.ingress.realtime.VoiceUiState
import com.novadrive.ingress.realtime.WorkInjection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class VoiceSessionController(
    private val appContext: Context,
    private val player: PcmAudioPlayer,
    private val onUiState: (VoiceUiState, String?) -> Unit,
    private val onTranscript: (String) -> Unit,
    private val onError: (String, String) -> Unit,
    private val onToolCall: ((DomainVoiceEvent.ToolCall) -> ToolDispatchResult)? = null,
    private val onListeningState: (ListeningState) -> Unit = {},
    /** When a picker is on screen and the utterance matches one candidate, dispatch locally. */
    private val onLocalNavigationPick: ((com.novadrive.app.nav.NavigationChoice) -> Unit)? = null,
    /** SPEC-010: true when the utterance named one on-screen control and it was taken locally. */
    private val onScreenAffordance: ((String) -> Boolean)? = null,
    timeouts: ListeningTimeouts = ListeningTimeouts(),
) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)
    private val microphone = AndroidMicrophonePort(onError = { code -> onError(code, "microphone failed") })
    private val audioFocus = AudioFocusController(appContext)
    // Reply audio is only played while listening is ACTIVE: after 「关闭小诺」 the cancelled reply
    // must not start talking.
    // Replies are spoken only in ACTIVE: after 「闭嘴」 or 「休眠」 the cancelled reply stays silent.
    private val playback = AndroidPlaybackPort(player, audioFocus) { lifecycle.speaks }
    @Volatile private var lastUiState: VoiceUiState = VoiceUiState.DISCONNECTED
    @Volatile private var playbackSpeaking = false
    @Volatile private var lastConfig: BaiduApiConfig? = null

    private val callbacks =
        VoiceSessionCallbacks(
            onUiState = { state, error ->
                lastUiState = state
                onUiState(state, error)
                // A reconnect keeps the driver armed; a terminal error must not pretend to.
                when (state) {
                    VoiceUiState.RECONNECTING -> lifecycle.onConnectionLost()
                    VoiceUiState.ERROR -> lifecycle.onSessionFailed("session_error")
                    else -> Unit
                }
                updateBusy()
            },
            onTranscript = onTranscript,
            onError = onError,
            onToolCall = onToolCall,
            onUserFinalTranscript = { text -> onUserUtterance(text) },
            onSessionLog = { line -> com.novadrive.app.DebugVoiceLog.log(line) },
            qualifyPlayoutBargeIn = { bargeInQualified() },
            bargeInDiagnostics = {
                val flush = bargeInQualified()
                VoiceAec.instance?.let { aec ->
                    AecMetrics.logBargeIn(
                        aec.streamDelayMs,
                        aec.snapshotStats(),
                        flush = flush,
                        suppressed = !flush,
                    )
                }
                mapOf(
                    "uplinkGateOpen" to microphone.uplinkGateOpen,
                    "recentSpeech" to microphone.recentSpeech,
                    "lastRms" to microphone.lastFrameRms,
                    "aec_enabled" to VoiceAudioSession.aecEnabled,
                    "aec_backend" to VoiceAudioSession.aecBackend,
                    "sessions_match" to VoiceAudioSession.sessionsMatch(),
                )
            },
        )

    /**
     * The listening lifecycle (ACTIVE / SILENT_WAIT / SLEEP / DEEP_IDLE): the one authority on
     * whether microphone audio may go to the cloud and whether replies are spoken.
     * See [ListeningLifecycle].
     */
    val lifecycle: ListeningLifecycle = ListeningLifecycle(
        scope = scope,
        controls = object : ListeningControls {
            override fun setCloudUpload(enabled: Boolean) = active.setCaptureSuspended(!enabled)
            override fun cancelAssistantReply() = active.cancelCurrentResponse()
            override fun closeCloudSession() = closeSession()
            override fun openCloudSession(): Boolean {
                val config = lastConfig ?: return false
                return runCatching { openSession(config) }.isSuccess
            }
        },
        timeouts = timeouts,
        nowMs = { android.os.SystemClock.elapsedRealtime() },
        onTransition = { from, to, reason, streamedMs ->
            com.novadrive.app.DebugVoiceLog.log("listening $from->$to reason=$reason cloudStreamingMs=$streamedMs")
            Telemetry.record(
                when (to) {
                    ListeningState.ACTIVE -> EventType.LISTENING_ACTIVE
                    ListeningState.SILENT_WAIT -> EventType.LISTENING_SILENT_WAIT
                    ListeningState.SLEEP -> EventType.LISTENING_SLEEP
                    ListeningState.DEEP_IDLE -> EventType.LISTENING_DEEP_IDLE
                },
                detail = "from=$from reason=$reason cloudStreamingMs=$streamedMs",
            )
            if (reason == "inactivity_timeout") Telemetry.record(EventType.INACTIVITY_TIMEOUT)
            if (reason == "silent_wait_timeout") Telemetry.record(EventType.SILENT_WAIT_TIMEOUT)
            if (to == ListeningState.SLEEP || to == ListeningState.DEEP_IDLE) {
                com.novadrive.app.nav.NavigationLocalPickGuard.invalidate()
                com.novadrive.app.nav.NavigationPickSession.clear()
                com.novadrive.app.nav.EmbeddedNavigation.currentOrNull()?.onListeningSuspended()
            }
            onListeningState(to)
            com.novadrive.app.wake.WakeWordController.reconcile(appContext)
        },
    )

    val listeningState: ListeningState get() = lifecycle.state.value
    private var provider: RealtimeVoiceProvider? = null
    private var active = newCore(IdleRealtimeProvider, RealtimeSessionConfig())

    val sessionActiveNow: Boolean get() = active.sessionActiveNow

    /**
     * A session the core gave up on. It still reports itself active — `failTerminal` stops capture
     * and playback but does not clear the flag — so callers that only check [sessionActiveNow]
     * would keep talking to a dead session. See `VoiceSessionGateway.start`.
     */
    val sessionFailedNow: Boolean get() = active.machine.state == VoiceUiState.ERROR

    private val guidanceListener: (Boolean) -> Unit = { speaking ->
        // SPEC-012: guidance is an arbiter input. R1: while Amap speaks the reply is held (queued,
        // not dropped) and the uplink closes; the microphone reads the uplink per frame.
        val arbiter = SpeechAuthority.arbiter
        arbiter.onGuidanceSpeaking(speaking)
        SpeechAuthority.uplinkClosed()
        if (speaking) {
            player.pausePlayback()
            SpeechAuthority.notePaused()
        } else {
            SpeechAuthority.syncPlaybackHold() // R2, but never through a workload or focus hold
        }
    }

    init {
        player.setOnPlaybackStateChanged { speaking ->
            playbackSpeaking = speaking
            updateBusy()
        }
        player.setOnPlaybackActiveChanged { active ->
            notifyPlaybackActiveForVad(active)
        }
        com.novadrive.app.nav.NavigationGuidanceVoice.addListener(guidanceListener)
    }

    /** Starts a new realtime session and makes listening ACTIVE. */
    fun startBaidu(apiConfig: BaiduApiConfig, reason: String = "start") {
        openSession(apiConfig)
        lifecycle.onSessionStarted(reason)
    }

    /**
     * Wake word, UI or an app prompt: resume listening (or restart the countdown). The wake word
     * also cuts off a reply in progress when the driver speaks over model audio.
     */
    fun activateListening(reason: String): Boolean {
        if (reason == "wake_word" && playbackSpeaking) {
            com.novadrive.app.DebugVoiceLog.log("wake_interrupts_reply")
            Telemetry.record(EventType.INTERRUPT_DETECTED, detail = "wake_word")
            active.cancelCurrentResponse()
        }
        return lifecycle.activate(reason)
    }

    /** 「闭嘴」: the reply stops now; the conversation and listening continue (SILENT_WAIT). */
    fun shutUp(reason: String) {
        Telemetry.record(EventType.SHUT_UP, detail = reason)
        lifecycle.silence(reason)
    }

    /** 「休眠」 / UI: stop listening now (SLEEP). */
    fun sleep(reason: String) {
        Telemetry.record(EventType.SLEEP_REQUESTED, detail = reason)
        lifecycle.sleep(reason)
    }

    /** The model ended the conversation: SLEEP once its short goodbye has played. */
    fun sleepAfterReply(reason: String) = lifecycle.sleepAfterReply(reason)

    private fun openSession(apiConfig: BaiduApiConfig) {
        lastConfig = apiConfig
        active.stop()
        provider?.close()
        VoiceAec.release()
        VoiceAec.open()
        val sharedSessionId = VoiceAudioSession.allocate()
        player.configureAudioSession(sharedSessionId)
        microphone.configureAudioSession(sharedSessionId)
        val outputRate = apiConfig.settings.resolvedOutputSampleRateHz()
        player.configureSampleRate(outputRate)
        val selected: RealtimeVoiceProvider = when (apiConfig.settings.runtimeProvider) {
            BaiduRuntimeProvider.FLEX ->
                BaiduFlexProvider(apiConfig, { microphone.measuredSegment() }, ::bargeInQualified)
            BaiduRuntimeProvider.LITE -> BaiduDirectRealtimeProvider(apiConfig)
        }
        val providerId = if (apiConfig.settings.runtimeProvider == BaiduRuntimeProvider.FLEX) {
            VoiceProviderId.BAIDU_FLEX
        } else VoiceProviderId.BAIDU
        provider = selected
        active = newCore(
            selected,
            RealtimeSessionConfig(
                provider = providerId,
                model = apiConfig.settings.model,
                audio = RealtimeAudioConfig(16_000, outputRate),
            ),
        )
        active.start()
        startSessionDiagnostics()
    }

    private fun notifyPlaybackActiveForVad(active: Boolean) {
        (provider as? BaiduFlexProvider)?.onPlaybackActiveChanged(active)
    }

    private var diagJob: Job? = null

    /**
     * Every 5 s while a session runs: is the mic producing frames, are they dropped (gated by
     * our own playback, or muted), and does the session think it is streaming. Counts only.
     */
    private fun startSessionDiagnostics() {
        diagJob?.cancel()
        diagJob = scope.launch {
            while (active.sessionActiveNow) {
                delay(SESSION_DIAG_INTERVAL_MS)
                com.novadrive.app.DebugVoiceLog.log(
                    "session_diag state=${active.machine.state} listening=${lifecycle.state.value} captureSuspended=${active.captureSuspendedNow} " +
                        "gated=${microphone.gated} guidanceGated=${microphone.guidanceGated} muted=${microphone.muted} " +
                        "captured=${microphone.capturedFrames.get()} droppedGated=${microphone.droppedGated.get()} " +
                        "droppedMuted=${microphone.droppedMuted.get()} droppedGuidance=${microphone.droppedGuidance.get()} peak=${microphone.takePeak()} " +
                        "gain=${"%.2f".format(microphone.currentGain)}",
                )
            }
        }
    }


    fun stop() {
        NavigationState.reset()
        active.stop()
        VoiceAec.release()
        lifecycle.onSessionStopped("stopped")
    }

    /** DEEP_IDLE: close the connection; nothing reconnects until the next start. */
    private fun closeSession() {
        active.stop()
        provider?.close()
        provider = null
        playbackSpeaking = false
        VoiceAec.release()
    }

    /**
     * Busy = a user turn or its answer is in progress. Assistant speech counts only as the answer
     * to a turn; navigation guidance, UI and vehicle events never reach here.
     */
    private fun updateBusy() {
        val state = lastUiState
        val busy = playbackSpeaking || active.hasPendingWork() || state in BUSY_STATES
        lifecycle.onBusyChanged(busy)
    }

    private val commandRouter by lazy {
        VoiceCommandRouter(
            lifecycle = lifecycle,
            context = {
                val phase = com.novadrive.app.nav.EmbeddedNavigation.currentOrNull()?.state()?.value
                ListeningIntent.Context(
                    pickerOpen = phase == com.novadrive.app.nav.NavigationPhase.AWAITING_DESTINATION_SELECTION ||
                        phase == com.novadrive.app.nav.NavigationPhase.AWAITING_ROUTE_SELECTION,
                    taskPending = active.hasPendingWork(),
                )
            },
            log = { com.novadrive.app.DebugVoiceLog.log(it) },
            onDriverRequest = { SpeechAuthority.arbiter.onDriverRequest() },
        )
    }

    /** The driver's finished utterance: listening-control phrases first (see [VoiceCommandRouter]). */
    private fun onUserUtterance(text: String) {
        com.novadrive.app.nav.NavigationLocalPickGuard.onUserTranscript()
        val decision = commandRouter.onUserUtterance(text)
        if (decision != ListeningIntent.Decision.PASS_TO_MODEL || !ListeningIntent.isMeaningful(text)) return
        if (onScreenAffordance?.invoke(text) == true) {
            active.cancelCurrentResponse()
            return
        }
        tryLocalNavigationPick(text)
    }

    /**
     * Speech over playback is a barge-in only with time-scoped post-AEC evidence (Astra P4). The
     * same answer goes to the playback owner (flush or not) and to the turn (candidate or not).
     */
    private fun bargeInQualified(): Boolean =
        VoiceAudioSession.aecBackend != "webrtc" || microphone.recentSpeech

    private fun tryLocalNavigationPick(text: String) {
        val pick = onLocalNavigationPick ?: return
        val nav = com.novadrive.app.nav.EmbeddedNavigation.currentOrNull() ?: return
        val turnKey = com.novadrive.app.nav.NavigationLocalPickGuard.nextTurnKey()
        val listKey = nav.currentListKey()
        val phase = nav.state().value
        val destinations = nav.destinationCandidates.value
        val routes = nav.routeCandidates.value
        // 「对」 answering 「你是说第二个X吗」 selects that row here, exactly as a tap would; any other
        // answer withdraws the question and the model takes the utterance as usual.
        val confirmation = nav.activeConfirmation()
        if (confirmation != null) nav.withdrawConfirmation()
        val confirmed = confirmation?.takeIf {
            com.novadrive.app.nav.NavigationPhoneticConfirmation.isAffirmative(text)
        }
        val choice = if (confirmed != null) {
            com.novadrive.app.nav.NavigationChoice.Index(confirmed.position)
        } else {
            com.novadrive.app.nav.NavigationPickerIntercept.resolve(
                text,
                phase,
                destinations,
                routes,
            )
        }
        if (choice != null) {
            com.novadrive.app.DebugVoiceLog.log("nav_voice_local_pick")
            active.cancelCurrentResponse()
            com.novadrive.app.nav.NavigationPickSession.begin(listKey, turnKey)
            pick(choice)
            return
        }
        when (phase) {
            com.novadrive.app.nav.NavigationPhase.AWAITING_DESTINATION_SELECTION -> {
                when (
                    val match = com.novadrive.app.nav.NavigationChoiceResolver.pickDestination(
                        destinations,
                        com.novadrive.app.nav.NavigationChoice.Name(text),
                    )
                ) {
                    is com.novadrive.app.nav.ChoiceMatch.Rejected ->
                        when (match.code) {
                            "AMBIGUOUS" ->
                                com.novadrive.app.nav.NavigationLocalPickGuard.record(
                                    listKey,
                                    turnKey,
                                    com.novadrive.app.nav.NavigationLocalPickGuard.Outcome.AMBIGUOUS,
                                )
                            "NO_MATCH" ->
                                com.novadrive.app.nav.NavigationLocalPickGuard.record(
                                    listKey,
                                    turnKey,
                                    com.novadrive.app.nav.NavigationLocalPickGuard.Outcome.NO_MATCH,
                                )
                        }
                    else -> Unit
                }
            }
            else -> Unit
        }
    }

    /**
     * Debug speech harness (SPEC-004 A-live): streams 16 kHz mono PCM16 into the live session in
     * real time, as if spoken into the microphone, then trailing silence so server VAD ends the
     * turn. Live microphone frames are suppressed meanwhile. Debuggable builds only.
     */
    fun injectTestSpeech(pcm16le: ByteArray) {
        if (!com.novadrive.app.DebugVoiceLog.isEnabled) return
        scope.launch {
            microphone.suppressLive = true
            try {
                val frame = TEST_FRAME_BYTES
                var offset = 0
                val silence = ByteArray(frame)
                val padded = pcm16le.size + TEST_TRAILING_SILENCE_FRAMES * frame
                com.novadrive.app.DebugVoiceLog.log("test_speech_start bytes=${pcm16le.size}")
                while (offset < padded) {
                    val chunk = if (offset < pcm16le.size) {
                        pcm16le.copyOfRange(offset, minOf(offset + frame, pcm16le.size))
                    } else {
                        silence
                    }
                    // Through the real uplink gate: an injected impulse must be rejected exactly
                    // as a tap on the dashboard is, or the harness would prove nothing about it.
                    microphone.gateForInjection(chunk).forEach { active.injectAudioFrame(it) }
                    offset += frame
                    delay(TEST_FRAME_MS)
                }
                com.novadrive.app.DebugVoiceLog.log("test_speech_end")
            } finally {
                microphone.suppressLive = false
            }
        }
    }

    /** Debug A/B switch for the input gain (speech harness only). */
    fun setInputGainEnabled(enabled: Boolean) {
        if (com.novadrive.app.DebugVoiceLog.isEnabled) microphone.inputGainEnabled = enabled
    }

    /** Asks the model to respond to [text]; queued until the session is connected. */
    fun sendText(text: String) {
        active.sendText(text)
    }

    fun release() {
        lifecycle.onSessionStopped("released")
        NavigationState.reset()
        active.release()
        provider?.close()
        provider = null
        microphone.gated = false
        com.novadrive.app.nav.NavigationGuidanceVoice.removeListener(guidanceListener)
        SpeechAuthority.arbiter.onGuidanceSpeaking(false)
        scope.cancel()
    }

    private fun newCore(provider: RealtimeVoiceProvider, config: RealtimeSessionConfig): CoreVoiceSessionController =
        CoreVoiceSessionController(
            provider = provider,
            microphone = microphone,
            playback = playback,
            scope = scope,
            config = config,
            callbacks = callbacks,
        )

    companion object {
        private val BUSY_STATES = setOf(
            VoiceUiState.USER_SPEAKING,
            VoiceUiState.THINKING,
            VoiceUiState.SPEAKING,
            VoiceUiState.CONNECTING,
            VoiceUiState.RECONNECTING,
        )
        private const val SESSION_DIAG_INTERVAL_MS = 5_000L
        private const val TEST_FRAME_BYTES = PcmAudioCapture.FRAME_BYTES
        private const val TEST_FRAME_MS = 100L
        private const val TEST_TRAILING_SILENCE_FRAMES = 15
    }
}

private object IdleRealtimeProvider : RealtimeVoiceProvider {
    override val providerId: String = "idle"
    override val capabilities: ProviderCapabilities = VoiceCatalog.capabilities(VoiceProviderId.FAKE)
    override suspend fun connect(config: RealtimeSessionConfig) = Unit
    override suspend fun disconnect() = Unit
    override fun sendAudio(pcm16le: ByteArray) = Unit
    override suspend fun cancelAssistantResponse() = DomainVoiceEvent.Interrupted("idle")
    override suspend fun injectWorkResult(result: WorkInjection) = DomainVoiceEvent.ToolUnsupported("idle")
    override fun connect(model: String) = Unit
    override fun close() = Unit
}
