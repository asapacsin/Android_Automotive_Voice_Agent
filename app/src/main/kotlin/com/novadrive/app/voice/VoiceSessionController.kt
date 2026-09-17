package com.novadrive.app.voice

import android.content.Context
import com.novadrive.app.BaiduApiConfig
import com.novadrive.app.NavigationState
import com.novadrive.app.BaiduRuntimeProvider
import com.novadrive.app.ConnectionMode
import com.novadrive.app.QwenApiConfig
import com.novadrive.app.VoiceAppSettings
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
    context: Context,
    private val player: PcmAudioPlayer,
    private val onUiState: (VoiceUiState, String?) -> Unit,
    private val onTranscript: (String) -> Unit,
    private val onError: (String, String) -> Unit,
    private val onToolCall: ((DomainVoiceEvent.ToolCall) -> ToolDispatchResult)? = null,
    private val onListeningState: (ListeningState) -> Unit = {},
    timeouts: ListeningTimeouts = ListeningTimeouts(),
) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)
    private val microphone = AndroidMicrophonePort(onError = { code -> onError(code, "microphone failed") })
    private val audioFocus = AudioFocusController(context)
    // Reply audio is only played while listening is ACTIVE: after 「关闭小诺」 the cancelled reply
    // must not start talking.
    private val playback = AndroidPlaybackPort(player, audioFocus) { lifecycle.state.value == ListeningState.ACTIVE }
    private var ungateJob: Job? = null
    private var ungateGeneration = 0
    @Volatile private var lastUiState: VoiceUiState = VoiceUiState.DISCONNECTED
    @Volatile private var playbackSpeaking = false
    @Volatile private var lastConfig: BaiduApiConfig? = null

    private val callbacks =
        VoiceSessionCallbacks(
            onUiState = { state, error ->
                lastUiState = state
                onUiState(state, error)
                if (state == VoiceUiState.RECONNECTING || state == VoiceUiState.ERROR) lifecycle.onConnectionLost()
                updateBusy()
            },
            onTranscript = onTranscript,
            onError = onError,
            onToolCall = onToolCall,
            onUserFinalTranscript = { text -> onUserUtterance(text) },
        )

    /**
     * The listening lifecycle (ACTIVE / STANDBY / DEEP_IDLE): the one authority on whether
     * microphone audio may go to the cloud. See [ListeningLifecycle].
     */
    val lifecycle: ListeningLifecycle = ListeningLifecycle(
        scope = scope,
        controls = object : ListeningControls {
            override fun setCloudUpload(enabled: Boolean) = active.setCaptureSuspended(!enabled)
            override fun cancelAssistantReply() = active.cancelCurrentResponse()
            override fun startFreshConversation() = active.requestFreshConversation()
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
                    ListeningState.STANDBY -> EventType.LISTENING_STANDBY
                    ListeningState.DEEP_IDLE -> EventType.LISTENING_DEEP_IDLE
                },
                detail = "from=$from reason=$reason cloudStreamingMs=$streamedMs",
            )
            if (reason == "inactivity_timeout") Telemetry.record(EventType.INACTIVITY_TIMEOUT)
            onListeningState(to)
        },
    )

    val listeningState: ListeningState get() = lifecycle.state.value
    private var provider: RealtimeVoiceProvider? = null
    private var active = newCore(IdleRealtimeProvider, RealtimeSessionConfig())

    val sessionActiveNow: Boolean get() = active.sessionActiveNow

    private val guidanceGate =
        GuidanceMicGate(scope, onGateChanged = { closed ->
            microphone.guidanceGated = closed
            com.novadrive.app.DebugVoiceLog.log("nav_guidance_mic_gate closed=$closed")
        })
    private val guidanceListener: (Boolean) -> Unit = { speaking -> guidanceGate.onGuidanceSpeaking(speaking) }

    init {
        player.setOnPlaybackStateChanged { speaking ->
            onPlaybackSpeaking(speaking)
            playbackSpeaking = speaking
            updateBusy()
        }
        com.novadrive.app.nav.NavigationGuidanceVoice.addListener(guidanceListener)
    }

    /** Starts a new realtime session and makes listening ACTIVE. */
    fun startBaidu(apiConfig: BaiduApiConfig, reason: String = "start") {
        openSession(apiConfig)
        lifecycle.onSessionStarted(reason)
    }

    /** Wake word, UI or an app prompt: resume listening (or restart the countdown). */
    fun activateListening(reason: String): Boolean = lifecycle.activate(reason)

    /** UI: stop listening now (STANDBY). */
    fun standby(reason: String) {
        Telemetry.record(EventType.TERMINATE_LISTENING, detail = reason)
        lifecycle.terminate(reason)
    }

    /** The model ended the conversation: STANDBY once its short goodbye has played. */
    fun standbyAfterReply(reason: String) = lifecycle.standbyAfterReply(reason)

    private fun openSession(apiConfig: BaiduApiConfig) {
        lastConfig = apiConfig
        active.stop()
        provider?.close()
        val outputRate = apiConfig.settings.resolvedOutputSampleRateHz()
        player.configureSampleRate(outputRate)
        val selected: RealtimeVoiceProvider = when (apiConfig.settings.runtimeProvider) {
            BaiduRuntimeProvider.FLEX -> BaiduFlexProvider(apiConfig)
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

    fun start(settings: VoiceAppSettings, qwenConfig: QwenApiConfig? = null) {
        active.stop()
        provider?.close()
        val selected: RealtimeVoiceProvider
        val config: RealtimeSessionConfig
        if (settings.connectionMode == ConnectionMode.QWEN_DIRECT) {
            val direct = qwenConfig ?: throw IllegalArgumentException("QWEN_API_KEY_MISSING")
            player.configureSampleRate(QWEN_OUTPUT_SAMPLE_RATE)
            selected = QwenDirectRealtimeProvider(direct)
            config =
                RealtimeSessionConfig(
                    provider = VoiceProviderId.QWEN,
                    model = settings.qwenModel,
                    audio = RealtimeAudioConfig(
                        inputSampleRateHz = 16_000,
                        outputSampleRateHz = QWEN_OUTPUT_SAMPLE_RATE,
                    ),
                )
        } else {
            player.configureSampleRate(BACKEND_OUTPUT_SAMPLE_RATE)
            val client =
                BackendVoiceClient(
                    onEvent = {},
                    onStateLabel = {},
                    onRawError = onError,
                )
            selected = BackendRealtimeProvider(client).also { it.attachBackendUrl(settings.backendUrl) }
            config = RealtimeSessionConfig(provider = settings.backendProvider, model = settings.backendModel)
        }
        provider = selected
        active = newCore(selected, config)
        active.start()
    }

    fun stop() {
        NavigationState.reset()
        active.stop()
        lifecycle.onSessionStopped("stopped")
    }

    /** DEEP_IDLE: close the connection; nothing reconnects until the next start. */
    private fun closeSession() {
        active.stop()
        provider?.close()
        provider = null
        playbackSpeaking = false
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

    /**
     * The driver's finished utterance. Listening-control phrases are handled here, before the
     * model's reply matters (precedence in [ListeningIntent]).
     */
    private fun onUserUtterance(text: String) {
        val phase = com.novadrive.app.nav.EmbeddedNavigation.currentOrNull()?.state()?.value
        val context = ListeningIntent.Context(
            pickerOpen = phase == com.novadrive.app.nav.NavigationPhase.AWAITING_DESTINATION_SELECTION ||
                phase == com.novadrive.app.nav.NavigationPhase.AWAITING_ROUTE_SELECTION,
            taskPending = active.hasPendingWork(),
        )
        when (ListeningIntent.classify(text, context)) {
            ListeningIntent.Decision.TERMINATE_LISTENING -> {
                com.novadrive.app.DebugVoiceLog.log("listening_terminate source=voice")
                Telemetry.record(EventType.TERMINATE_LISTENING, detail = "voice")
                lifecycle.terminate("voice_command")
            }
            ListeningIntent.Decision.PASS_TO_MODEL ->
                if (ListeningIntent.isMeaningful(text)) lifecycle.onMeaningfulUserTurn()
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
                    active.injectAudioFrame(microphone.processForSend(chunk))
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
        ungateJob?.cancel()
        ungateJob = null
        microphone.gated = false
        com.novadrive.app.nav.NavigationGuidanceVoice.removeListener(guidanceListener)
        guidanceGate.reset()
        scope.cancel()
    }

    private fun onPlaybackSpeaking(speaking: Boolean) {
        synchronized(this) {
            ungateJob?.cancel()
            ungateJob = null
            if (speaking) {
                ungateGeneration++
                microphone.gated = true
            } else {
                val generation = ungateGeneration
                ungateJob =
                    scope.launch {
                        delay(PLAYBACK_UNGATE_DELAY_MS)
                        synchronized(this@VoiceSessionController) {
                            if (generation == ungateGeneration) {
                                microphone.gated = false
                            }
                        }
                    }
            }
        }
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
        private const val QWEN_OUTPUT_SAMPLE_RATE = 24_000
        private const val BACKEND_OUTPUT_SAMPLE_RATE = 16_000
        private const val PLAYBACK_UNGATE_DELAY_MS = 350L
        private const val SESSION_DIAG_INTERVAL_MS = 5_000L
        private const val TEST_FRAME_BYTES = 3_200 // 100 ms at 16 kHz mono PCM16
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
