package com.novadrive.app.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.novadrive.ingress.realtime.AudioBufferGuard
import com.novadrive.ingress.realtime.BoundedThreadCleanup
import com.novadrive.ingress.realtime.PlaybackPort
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 16 kHz PCM16 playback. Start/buffer failures are reported via [onError].
 * Hardware routing, Bluetooth SCO, and AEC remain manual-test-only.
 */
class PcmAudioPlayer(
    private val onError: (String) -> Unit,
) {
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val running = AtomicBoolean(false)
    private var track: AudioTrack? = null
    private var worker: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val minBuf =
            try {
                AudioBufferGuard.requireValidMinBuffer(
                    AudioTrack.getMinBufferSize(
                        PcmAudioCapture.SAMPLE_RATE,
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
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(PcmAudioCapture.SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(minBuf * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } catch (_: Exception) {
                running.set(false)
                onError("AUDIO_PLAYBACK_FAILED")
                return
            }
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
                    val frame = queue.poll()
                    if (frame == null) {
                        try {
                            Thread.sleep(10)
                        } catch (_: InterruptedException) {
                            break
                        }
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
        }
    }

    fun flush() {
        queue.clear()
        track?.pause()
        track?.flush()
        track?.play()
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
        val toJoin = worker
        worker = null
        BoundedThreadCleanup.terminate(toJoin)
    }
}

class AndroidPlaybackPort(
    private val player: PcmAudioPlayer,
    private val focus: AudioFocusController? = null,
) : PlaybackPort {
    override val queuedFrames: Int
        get() = 0

    override fun start() {
        focus?.requestSpeechFocus()
        player.start()
    }

    override fun enqueue(pcm16le: ByteArray) {
        player.enqueue(pcm16le)
    }

    override fun flush() {
        player.flush()
    }

    override fun stop() {
        player.stop()
        focus?.abandon()
    }
}
