package com.novadrive.app.voice

import android.content.Context
import com.novadrive.ingress.realtime.RealtimeSessionConfig
import com.novadrive.ingress.realtime.VoiceCatalog
import com.novadrive.ingress.realtime.VoiceSessionCallbacks
import com.novadrive.ingress.realtime.VoiceSessionController as CoreVoiceSessionController
import com.novadrive.ingress.realtime.VoiceUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class VoiceSessionController(
    context: Context,
    player: PcmAudioPlayer,
    client: BackendVoiceClient,
    private val onUiState: (VoiceUiState, String?) -> Unit,
    private val onTranscript: (String) -> Unit,
    private val onError: (String, String) -> Unit,
) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)
    private val provider = BackendRealtimeProvider(client)
    private val microphone = AndroidMicrophonePort(onError = { code -> onError(code, "microphone failed") })
    private val playback = AndroidPlaybackPort(player, AudioFocusController(context))
    private val callbacks =
        VoiceSessionCallbacks(
            onUiState = onUiState,
            onTranscript = onTranscript,
            onError = onError,
        )
    private var active: CoreVoiceSessionController =
        CoreVoiceSessionController(
            provider = provider,
            microphone = microphone,
            playback = playback,
            scope = scope,
            callbacks = callbacks,
        )

    val sessionActiveNow: Boolean get() = active.sessionActiveNow

    fun start(backendUrl: String, model: String) {
        BackendRealtimeProvider.lastBackendUrl = backendUrl
        provider.attachBackendUrl(backendUrl)
        val providerId =
            runCatching { VoiceCatalog.providerForModel(model) }.getOrDefault(VoiceCatalog.DEFAULT_PROVIDER)
        val config = RealtimeSessionConfig(provider = providerId, model = model)
        active.stop()
        active =
            CoreVoiceSessionController(
                provider = provider,
                microphone = microphone,
                playback = playback,
                scope = scope,
                config = config,
                callbacks = callbacks,
            )
        active.start()
    }

    fun stop() {
        active.stop()
    }

    fun release() {
        active.release()
        scope.cancel()
    }
}
