package com.novadrive.app.voice

import android.media.AudioTrack
import android.os.Build
import com.novadrive.app.DebugVoiceLog

/**
 * WebRTC-style adaptive [AudioTrack] buffer sizing: start at the platform minimum, grow by 10 ms
 * on underruns (at most five times), shrink by 10 ms after ten underrun-free 10 ms writes.
 */
class LowLatencyPlaybackBuffer(
    private val sampleRateHz: Int,
    platformMinBufferBytes: Int,
) {
    private val bytesPer10ms: Int = sampleRateHz * 2 / 100
    private val platformFloorBytes: Int = maxOf(platformMinBufferBytes, bytesPer10ms)
    private var currentBufferBytes: Int = platformFloorBytes
    private var lastUnderrunCount: Int = 0
    private var bufferIncreaseCount: Int = 0
    private var underrunFreeWrites: Int = 0

    val bufferMs: Int
        get() = (currentBufferBytes * 1000) / (sampleRateHz * 2)

    fun initialBufferBytes(): Int = currentBufferBytes

    fun afterWrite(player: AudioTrack) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val underruns = player.underrunCount
        if (underruns > lastUnderrunCount) {
            lastUnderrunCount = underruns
            underrunFreeWrites = 0
            if (bufferIncreaseCount < MAX_INCREASES) {
                grow(player)
            }
            return
        }
        underrunFreeWrites += 1
        if (underrunFreeWrites >= SHRINK_TICKS) {
            underrunFreeWrites = 0
            shrink(player)
        }
    }

    fun reset(player: AudioTrack) {
        currentBufferBytes = platformFloorBytes
        lastUnderrunCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) player.underrunCount else 0
        bufferIncreaseCount = 0
        underrunFreeWrites = 0
        applyBufferSize(player, currentBufferBytes)
    }

    private fun grow(player: AudioTrack) {
        val next = currentBufferBytes + bytesPer10ms
        if (!applyBufferSize(player, next)) return
        currentBufferBytes = next
        bufferIncreaseCount += 1
        logChange("grow", player)
    }

    private fun shrink(player: AudioTrack) {
        if (bufferIncreaseCount > 0) return
        val next = maxOf(platformFloorBytes, currentBufferBytes - bytesPer10ms)
        if (next == currentBufferBytes) return
        if (!applyBufferSize(player, next)) return
        currentBufferBytes = next
        logChange("shrink", player)
    }

    private fun applyBufferSize(player: AudioTrack, bytes: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val frames = bytes / 2
        return try {
            player.setBufferSizeInFrames(frames) >= 0
        } catch (_: Exception) {
            false
        }
    }

    private fun logChange(action: String, player: AudioTrack) {
        val underruns = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) player.underrunCount else 0
        DebugVoiceLog.log(
            "playback_buffer_ms=${bufferMs} action=$action underrun_count=$underruns " +
                "increases=$bufferIncreaseCount",
        )
    }

    private companion object {
        const val MAX_INCREASES = 5
        const val SHRINK_TICKS = 10
    }
}
