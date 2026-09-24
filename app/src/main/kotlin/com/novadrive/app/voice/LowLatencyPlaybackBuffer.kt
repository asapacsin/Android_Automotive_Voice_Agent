package com.novadrive.app.voice

import android.media.AudioTrack
import com.novadrive.app.DebugVoiceLog

/**
 * WebRTC-style adaptive [AudioTrack] buffer sizing: start at the platform minimum, grow by 10 ms
 * on underruns (at most five times), shrink by 10 ms after ten underrun-free 10 ms writes when it
 * has never had to grow.
 *
 * Two rules added 2026-09-24 (Astra P1):
 * - The size is what the platform **accepted**. `setBufferSizeInFrames` clamps to the track's
 *   capacity and granularity and returns the size it applied; the requested size used to be
 *   recorded instead, so [bufferMs] and every later step could be wrong. A grow the platform
 *   refuses caps growth rather than being retried on every underrun.
 * - Only an underrun while the app had audio to give counts. Between replies the app queue is
 *   empty and the track runs dry by design; that was counted on the next reply's first write and
 *   grew the buffer (up to 50 ms of added latency) for a gap no buffer size can fix. The player
 *   calls [onStarved] whenever it has nothing to write, which rebases the count. Network jitter
 *   mid-reply starves the queue the same way and is excluded for the same reason.
 */
class LowLatencyPlaybackBuffer(
    private val sampleRateHz: Int,
    platformMinBufferBytes: Int,
) {
    /** The two track operations this policy needs; [AudioTrack] in production. */
    interface Port {
        val underrunCount: Int

        /** Requests [frames]; returns the size applied, or a negative error. */
        fun setBufferSizeInFrames(frames: Int): Int
    }

    private val bytesPer10ms: Int = sampleRateHz * 2 / 100
    private val platformFloorBytes: Int = maxOf(platformMinBufferBytes, bytesPer10ms)
    private var currentBufferBytes: Int = platformFloorBytes
    private var lastUnderrunCount: Int = 0
    private var bufferIncreaseCount: Int = 0
    private var underrunFreeWrites: Int = 0
    private var growthCapped = false

    val bufferMs: Int
        get() = (currentBufferBytes * 1000) / (sampleRateHz * 2)

    /** Underruns counted against the buffer since the last [reset]; diagnostics only. */
    var countedUnderruns: Int = 0
        private set

    fun initialBufferBytes(): Int = currentBufferBytes

    fun afterWrite(player: AudioTrack) = afterWrite(player.asPort())

    fun afterWrite(port: Port) {
        val underruns = port.underrunCount
        if (underruns > lastUnderrunCount) {
            countedUnderruns += underruns - lastUnderrunCount
            lastUnderrunCount = underruns
            underrunFreeWrites = 0
            if (bufferIncreaseCount < MAX_INCREASES && !growthCapped) {
                grow(port)
            }
            return
        }
        underrunFreeWrites += 1
        if (underrunFreeWrites >= SHRINK_TICKS) {
            underrunFreeWrites = 0
            shrink(port)
        }
    }

    /** The app had nothing to write: the track running dry now is not the buffer's fault. */
    fun onStarved(player: AudioTrack) = onStarved(player.asPort())

    fun onStarved(port: Port) {
        lastUnderrunCount = port.underrunCount
        underrunFreeWrites = 0
    }

    fun reset(player: AudioTrack) = reset(player.asPort())

    fun reset(port: Port) {
        currentBufferBytes = platformFloorBytes
        lastUnderrunCount = port.underrunCount
        bufferIncreaseCount = 0
        underrunFreeWrites = 0
        countedUnderruns = 0
        growthCapped = false
        applyBufferSize(port, currentBufferBytes)
    }

    private fun grow(port: Port) {
        val before = currentBufferBytes
        if (!applyBufferSize(port, before + bytesPer10ms)) return
        if (currentBufferBytes <= before) {
            growthCapped = true
            logChange("grow_capped", port)
            return
        }
        bufferIncreaseCount += 1
        logChange("grow", port)
    }

    private fun shrink(port: Port) {
        if (bufferIncreaseCount > 0) return
        val next = maxOf(platformFloorBytes, currentBufferBytes - bytesPer10ms)
        if (next == currentBufferBytes) return
        if (!applyBufferSize(port, next)) return
        logChange("shrink", port)
    }

    /** Applies [bytes] and records what the platform actually set. */
    private fun applyBufferSize(port: Port, bytes: Int): Boolean {
        val applied = try {
            port.setBufferSizeInFrames(bytes / 2)
        } catch (_: Exception) {
            -1
        }
        if (applied <= 0) return false
        currentBufferBytes = applied * 2
        return true
    }

    private fun logChange(action: String, port: Port) {
        DebugVoiceLog.log(
            "playback_buffer_ms=${bufferMs} action=$action underrun_count=${port.underrunCount} " +
                "counted=$countedUnderruns increases=$bufferIncreaseCount",
        )
    }

    private companion object {
        const val MAX_INCREASES = 5
        const val SHRINK_TICKS = 10

        fun AudioTrack.asPort(): Port {
            val track = this
            return object : Port {
                override val underrunCount: Int get() = track.underrunCount
                override fun setBufferSizeInFrames(frames: Int): Int = track.setBufferSizeInFrames(frames)
            }
        }
    }
}
