package com.novadrive.app.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.novadrive.app.DebugVoiceLog
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
 * PCM16 mono playback. The rate is configured per session from what the provider actually
 * returns (`resolvedOutputSampleRateHz`), not assumed: Baidu Flex and Lite differ.
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
    @Volatile private var audioSessionId: Int = AudioManager.AUDIO_SESSION_ID_GENERATE
    private val stateLock = Any()
    @Volatile private var speaking = false
    @Volatile private var playbackPaused = false
    @Volatile private var acceptEpoch = 0
    @Volatile private var framesWritten: Long = 0
    private val playbackStateListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private val playbackActiveListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    @Volatile private var lastNotifiedPlaybackActive = false

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

    fun setOnPlaybackActiveChanged(listener: ((Boolean) -> Unit)?) {
        if (listener == null) {
            playbackActiveListeners.clear()
        } else {
            playbackActiveListeners.add(listener)
        }
    }

    fun configureSampleRate(sampleRateHz: Int) {
        require(sampleRateHz in 8_000..48_000) { "AUDIO_SAMPLE_RATE_INVALID" }
        if (running.get()) return
        this.sampleRateHz = sampleRateHz
    }

    fun configureAudioSession(sessionId: Int) {
        if (running.get()) return
        audioSessionId = sessionId
    }

    val playbackSessionId: Int
        get() = track?.audioSessionId ?: audioSessionId

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
                val builder =
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
                VoiceAudioSession.applyToTrackBuilder(builder, audioSessionId)
                builder.build()
            } catch (_: Exception) {
                running.set(false)
                onError("AUDIO_PLAYBACK_FAILED")
                return
            }
        playbackPaused = false
        acceptEpoch = 0
        framesWritten = 0
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
        VoiceAudioSession.recordPlaybackSession(player.audioSessionId)
        DebugVoiceLog.log(
            "playback_start playbackSessionId=${player.audioSessionId} captureSessionId=${VoiceAudioSession.captureSessionId} " +
                "sessions_match=${VoiceAudioSession.sessionsMatch()}",
        )
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
                    if (written > 0) {
                        framesWritten += written / 2
                    }
                }
            }
    }

    fun enqueue(pcm16le: ByteArray, epoch: Int) {
        if (!running.get() || epoch != acceptEpoch) return
        queue.offer(pcm16le)
        emitSpeaking(true)
        notifyPlaybackActiveIfChanged()
    }

    val queuedFrames: Int
        get() {
            val queued = queue.sumOf { it.size / 2 }
            val track = track ?: return queued
            return try {
                val head = track.playbackHeadPosition.toLong()
                val buffered = (framesWritten - head).coerceAtLeast(0)
                (queued + buffered).toInt()
            } catch (_: Exception) {
                queued
            }
        }

    val playbackActive: Boolean
        get() = queuedFrames > 0 || speaking

    @Volatile
    var duckCount: Int = 0
        private set

    fun duck() {
        duckCount++
        try {
            track?.setVolume(0.2f)
        } catch (_: Exception) {
        }
    }

    @Volatile
    var unduckCount: Int = 0
        private set

    fun unduck() {
        unduckCount++
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
        framesWritten = 0
        acceptEpoch += 1
        track?.pause()
        track?.flush()
        if (!playbackPaused) {
            track?.play()
        }
        emitSpeaking(false)
        notifyPlaybackActiveIfChanged()
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
        acceptEpoch = 0
        framesWritten = 0
        val toJoin = worker
        worker = null
        BoundedThreadCleanup.terminate(toJoin)
        emitSpeaking(false)
        notifyPlaybackActiveIfChanged()
    }

    private fun notifyPlaybackActiveIfChanged() {
        val now = playbackActive
        if (now == lastNotifiedPlaybackActive) return
        lastNotifiedPlaybackActive = now
        val listeners = playbackActiveListeners.toTypedArray()
        for (listener in listeners) {
            try {
                listener(now)
            } catch (_: Exception) {
            }
        }
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
        notifyPlaybackActiveIfChanged()
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
    /** False unless the lifecycle is ACTIVE: a reply after 「闭嘴」 or 「休眠」 is not played. */
    private val playbackAllowed: () -> Boolean = { true },
) : PlaybackPort {
    override val queuedFrames: Int
        get() = player.queuedFrames

    override val playbackActive: Boolean
        get() = player.playbackActive

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
            when (focusAction(change)) {
                FocusAction.DUCK -> {
                    // A permitted confirmation during navigation must stay at full volume even when
                    // Amap guidance transiently ducks other streams.
                    if (NavigationState.navigating && !NavigationState.shouldMuteSpeech()) Unit
                    else player.duck()
                }
                FocusAction.PAUSE -> player.pausePlayback()
                FocusAction.STOP -> {
                    player.pausePlayback()
                    player.flush()
                }
                FocusAction.RESUME -> {
                    player.unduck()
                    player.resumePlayback()
                }
                FocusAction.NOTHING -> Unit
            }
        } catch (_: Exception) {
        }
    }

    override fun start() {
        player.start()
    }

    @Volatile private var droppingReply = false
    @Volatile private var droppingForNavigation = false

    override fun enqueue(pcm16le: ByteArray, epoch: Int) {
        if (NavigationState.shouldMuteSpeech()) {
            if (!droppingForNavigation) {
                droppingForNavigation = true
                com.novadrive.app.DebugVoiceLog.log("reply_audio_not_played reason=navigation_unprompted")
            }
            return
        }
        droppingForNavigation = false
        NavigationState.extendWhileSpeaking()
        if (!playbackAllowed()) {
            if (!droppingReply) {
                droppingReply = true
                com.novadrive.app.DebugVoiceLog.log("reply_audio_not_played reason=not_active")
            }
            return
        }
        droppingReply = false
        // A permitted reply during navigation may follow guidance ducking; restore full volume.
        player.unduck()
        player.enqueue(pcm16le, epoch)
    }

    override fun flush() {
        player.flush()
        com.novadrive.evaluation.Telemetry.record(com.novadrive.evaluation.EventType.AUDIO_STOPPED)
    }

    override fun stop() {
        player.stop()
    }
}

/** What losing or regaining audio focus should do to reply playback. */
enum class FocusAction { DUCK, PAUSE, STOP, RESUME, NOTHING }

/**
 * The focus mapping, separated from the player so it can be tested at all.
 *
 * `AndroidPlaybackPort` holds a concrete `PcmAudioPlayer` built on `AudioTrack`, so nothing about
 * this decision could be exercised on the JVM while it lived inside the `when`. The four cases are
 * not interchangeable: ducking under a navigation prompt and flushing a reply because a phone call
 * arrived are different promises to the driver.
 */
fun focusAction(change: Int): FocusAction = when (change) {
    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> FocusAction.DUCK
    // Transient: the reply is still wanted, so it pauses rather than being thrown away.
    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> FocusAction.PAUSE
    // Permanent - a call, another assistant. What was queued is no longer worth saying.
    AudioManager.AUDIOFOCUS_LOSS -> FocusAction.STOP
    AudioManager.AUDIOFOCUS_GAIN -> FocusAction.RESUME
    else -> FocusAction.NOTHING
}
