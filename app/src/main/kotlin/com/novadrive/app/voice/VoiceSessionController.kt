package com.novadrive.app.voice

import android.content.Context
import com.novadrive.app.BaiduApiConfig
import com.novadrive.app.NavigationState
import com.novadrive.app.BaiduRuntimeProvider
import com.novadrive.app.ConnectionMode
import com.novadrive.app.QwenApiConfig
import com.novadrive.app.VoiceAppSettings
import com.novadrive.app.resolvedOutputSampleRateHz
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
) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)
    private val microphone = AndroidMicrophonePort(onError = { code -> onError(code, "microphone failed") })
    private val audioFocus = AudioFocusController(context)
    private val playback = AndroidPlaybackPort(player, audioFocus)
    private var ungateJob: Job? = null
    private var ungateGeneration = 0
    private val callbacks =
        VoiceSessionCallbacks(
            onUiState = onUiState,
            onTranscript = onTranscript,
            onError = onError,
            onToolCall = onToolCall,
        )
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
        }
        com.novadrive.app.nav.NavigationGuidanceVoice.addListener(guidanceListener)
    }

    fun startBaidu(apiConfig: BaiduApiConfig) {
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
                    "session_diag state=${active.machine.state} streaming=${active.machine.streamingAudio} " +
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
