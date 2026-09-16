package com.novadrive.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

/**
 * Audio focus / routing seam. Bluetooth SCO and hardware AEC are manual-test-only
 * on device; this class only requests speech-communication focus and compiles.
 */
class AudioFocusController(
    context: Context,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var request: AudioFocusRequest? = null
    @Volatile private var held = false
    var lastFocusState: Int = AudioManager.AUDIOFOCUS_NONE
        private set
    var onFocusChanged: ((Int) -> Unit)? = null

    @Synchronized
    fun requestSpeechFocus() {
        if (held) return
        val attrs =
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        if (Build.VERSION.SDK_INT >= 26) {
            val focusRequest =
                AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener { handleFocusChange(it) }
                    .build()
            request = focusRequest
            lastFocusState = audioManager.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            lastFocusState =
                audioManager.requestAudioFocus(
                    { handleFocusChange(it) },
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
                )
        }
        held = true
    }

    private fun handleFocusChange(change: Int) {
        lastFocusState = change
        onFocusChanged?.invoke(change)
    }

    @Synchronized
    fun abandon() {
        if (!held) return
        val heldRequest = request
        if (heldRequest != null && Build.VERSION.SDK_INT >= 26) {
            audioManager.abandonAudioFocusRequest(heldRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        request = null
        held = false
    }

    fun communicationDeviceLabel(): String {
        if (Build.VERSION.SDK_INT >= 31) {
            val device = audioManager.communicationDevice ?: return "default"
            return device.productName?.toString() ?: device.type.toString()
        }
        @Suppress("DEPRECATION")
        return if (audioManager.isBluetoothScoOn) "bluetooth-sco-unverified" else "default"
    }
}
