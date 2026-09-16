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

    init {
        player.setOnPlaybackStateChanged { speaking ->
            onPlaybackSpeaking(speaking)
        }
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

    fun release() {
        NavigationState.reset()
        active.release()
        provider?.close()
        provider = null
        ungateJob?.cancel()
        ungateJob = null
        microphone.gated = false
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
