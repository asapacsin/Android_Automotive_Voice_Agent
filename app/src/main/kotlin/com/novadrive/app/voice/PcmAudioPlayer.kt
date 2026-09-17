package com.novadrive.app.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.novadrive.app.NavigationState
import com.novadrive.ingress.realtime.AudioBufferGuard
import com.novadrive.ingress.realtime.BoundedThreadCleanup
import com.novadrive.ingress.realtime.PlaybackPort
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * PCM16 mono playback. Qwen produces 24 kHz audio; the backend compatibility path uses 16 kHz.
 * Hardware routing, Bluetooth SCO, and AEC remain manual-test-only.
 */
class PcmAudioPlayer(
    private val onError: (String) -> Unit,
) {
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val running = AtomicBoolean(false)
    private var track: AudioTrack? = null
    private var worker: Thread? = null
    @Volatile private var sampleRateHz: Int = PcmAudioCapture.SAMPLE_RATE
    private val stateLock = Any()
    @Volatile private var speaking = false
    @Volatile private var playbackPaused = false
    private val playbackStateListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    val isPlaying: Boolean
        get() =
            try {
                running.get() && (speaking || queue.isNotEmpty())
            } catch (_: Exception) {
                false
            }

    fun setOnPlaybackStateChanged(listener: ((Boolean) -> Unit)?) {
        if (listener == null) {
            playbackStateListeners.clear()
        } else {
            playbackStateListeners.add(listener)
        }
    }

    fun configureSampleRate(sampleRateHz: Int) {
        require(sampleRateHz in 8_000..48_000) { "AUDIO_SAMPLE_RATE_INVALID" }
        if (running.get()) return
        this.sampleRateHz = sampleRateHz
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val minBuf =
            try {
                AudioBufferGuard.requireValidMinBuffer(
                    AudioTrack.getMinBufferSize(
                        sampleRateHz,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                    ),
                    "AUDIO_PLAYBACK_FAILED",
                )
            } catch (_: IllegalArgumentException) {
                running.set(false)
                onError("AUDIO_PLAYBACK_FAILED")
                return
            }
        val player =
            try {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRateHz)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(maxOf(minBuf * 4, sampleRateHz * 2 /*bytes per sample*/ * 320 / 1000))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } catch (_: Exception) {
                running.set(false)
                onError("AUDIO_PLAYBACK_FAILED")
                return
            }
        playbackPaused = false
        try {
            player.play()
        } catch (_: Exception) {
            try {
                player.release()
            } catch (_: Exception) {
            }
            running.set(false)
            onError("AUDIO_PLAYBACK_FAILED")
            return
        }
        track = player
        worker =
            thread(name = "nova-pcm-play", isDaemon = true) {
                while (running.get()) {
                    val frame =
                        try {
                            queue.poll(20, TimeUnit.MILLISECONDS)
                        } catch (_: InterruptedException) {
                            break
                        }
                    if (frame == null) {
                        emitIdleIfDrained()
                        continue
                    }
                    val written = player.write(frame, 0, frame.size)
                    if (written < 0) {
                        onError("AUDIO_PLAYBACK_FAILED")
                        break
                    }
                }
            }
    }

    fun enqueue(pcm16le: ByteArray) {
        if (running.get()) {
            queue.offer(pcm16le)
            emitSpeaking(true)
        }
    }

    fun duck() {
        try {
            track?.setVolume(0.2f)
        } catch (_: Exception) {
        }
    }

    fun unduck() {
        try {
            track?.setVolume(1.0f)
        } catch (_: Exception) {
        }
    }

    fun pausePlayback() {
        try {
            playbackPaused = true
            track?.pause()
        } catch (_: Exception) {
        }
    }

    fun resumePlayback() {
        try {
            playbackPaused = false
            track?.play()
        } catch (_: Exception) {
        }
    }

    fun flush() {
        queue.clear()
        track?.pause()
        track?.flush()
        if (!playbackPaused) {
            track?.play()
        }
        emitSpeaking(false)
    }

    fun stop() {
        running.set(false)
        queue.clear()
        track?.run {
            try {
                pause()
                flush()
                stop()
            } catch (_: Exception) {
            }
            try {
                release()
            } catch (_: Exception) {
            }
        }
        track = null
        playbackPaused = false
        val toJoin = worker
        worker = null
        BoundedThreadCleanup.terminate(toJoin)
        emitSpeaking(false)
    }

    private fun emitSpeaking(nowSpeaking: Boolean) {
        synchronized(stateLock) {
            if (speaking == nowSpeaking) return
            speaking = nowSpeaking
        }
        dispatchPlaybackState(nowSpeaking)
    }

    private fun emitIdleIfDrained() {
        synchronized(stateLock) {
            if (!queue.isEmpty()) return
            if (!speaking) return
            speaking = false
        }
        dispatchPlaybackState(false)
    }

    private fun dispatchPlaybackState(nowSpeaking: Boolean) {
        val listeners = playbackStateListeners.toTypedArray()
        synchronized(stateLock) {
            if (speaking != nowSpeaking) return
        }
        for (listener in listeners) {
            try {
                listener(nowSpeaking)
            } catch (_: Exception) {
            }
        }
    }
}

class AndroidPlaybackPort(
    private val player: PcmAudioPlayer,
    private val focus: AudioFocusController? = null,
    /** False while listening is not ACTIVE: a reply to a cancelled turn is not played. */
    private val playbackAllowed: () -> Boolean = { true },
) : PlaybackPort {
    override val queuedFrames: Int
        get() = 0

    init {
        player.setOnPlaybackStateChanged { speaking ->
            if (speaking) {
                focus?.requestSpeechFocus()
            } else {
                focus?.abandon()
            }
        }
        // Single owner: AudioFocusController.onFocusChanged is one slot, not a listener list.
        focus?.onFocusChanged = { change -> applyFocusChange(change) }
    }

    fun applyFocusChange(change: Int) {
        try {
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> player.duck()
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> player.pausePlayback()
                AudioManager.AUDIOFOCUS_LOSS -> {
                    player.pausePlayback()
                    player.flush()
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    player.unduck()
                    player.resumePlayback()
                }
            }
        } catch (_: Exception) {
        }
    }

    override fun start() {
        player.start()
    }

    @Volatile private var droppingReply = false

    override fun enqueue(pcm16le: ByteArray) {
        if (NavigationState.shouldMuteSpeech()) return
        if (!playbackAllowed()) {
            if (!droppingReply) {
                droppingReply = true
                com.novadrive.app.DebugVoiceLog.log("reply_audio_not_played reason=${if (SpeechOutput.silent) "silent" else "not_listening"}")
            }
            return
        }
        droppingReply = false
        player.enqueue(pcm16le)
    }

    override fun flush() {
        player.flush()
        com.novadrive.evaluation.Telemetry.record(com.novadrive.evaluation.EventType.AUDIO_STOPPED)
    }

    override fun stop() {
        player.stop()
    }
}
