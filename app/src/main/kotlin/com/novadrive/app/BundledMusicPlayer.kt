package com.novadrive.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object BundledMusicPlayer {
    @Volatile
    private var player: MediaPlayer? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    private val playingState = MutableStateFlow(false)

    /** Real playback state, so the bottom bar reflects voice-started music too. */
    val playing: StateFlow<Boolean> = playingState.asStateFlow()

    val isPlaying: Boolean
        get() = synchronized(this) { player?.isPlaying == true }

    /** ⏮ — there is one bundled track, so "previous" restarts it from the beginning. */
    fun restart(context: Context): Boolean =
        synchronized(this) {
            val current = player
            if (current != null) {
                runCatching { current.seekTo(0) }.isSuccess
            } else {
                play(context)
            }
        }

    fun play(context: Context): Boolean =
        synchronized(this) {
            try {
                if (player?.isPlaying == true) return true
                stopLocked()
                val created = MediaPlayer()
                try {
                    created.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    )
                    context.applicationContext.resources.openRawResourceFd(R.raw.bach_air_usaf).use { fd ->
                        created.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                    }
                    created.prepare()
                    created.isLooping = true
                    created.setVolume(0.45f, 0.45f)
                    requestMediaFocus(context)
                    created.start()
                    player = created
                    playingState.value = true
                    true
                } catch (failure: Exception) {
                    try {
                        created.release()
                    } catch (_: Exception) {
                    }
                    throw failure
                }
            } catch (_: Exception) {
                stopLocked()
                false
            }
        }

    fun stop() {
        synchronized(this) { stopLocked() }
    }

    fun toggle(context: Context): Boolean =
        if (isPlaying) {
            stop()
            false
        } else {
            play(context)
        }

    private fun requestMediaFocus(context: Context) {
        runCatching {
            val attrs =
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            val request =
                AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener { }
                    .build()
            val manager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager = manager
            focusRequest = request
            manager.requestAudioFocus(request)
        }
    }

    private fun stopLocked() {
        runCatching {
            val held = focusRequest
            val manager = audioManager
            if (held != null && manager != null) {
                manager.abandonAudioFocusRequest(held)
            }
        }
        focusRequest = null
        audioManager = null
        playingState.value = false
        val current = player
        player = null
        if (current == null) return
        try {
            current.stop()
        } catch (_: Exception) {
        }
        try {
            current.release()
        } catch (_: Exception) {
        }
    }
}
