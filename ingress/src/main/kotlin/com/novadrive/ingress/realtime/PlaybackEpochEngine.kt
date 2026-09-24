package com.novadrive.ingress.realtime

import java.io.ByteArrayOutputStream
import java.util.ArrayDeque

data class QueuedPlaybackPcm(
    val pcm: ByteArray,
    val epoch: Int,
)

enum class PlaybackEnqueueResult {
    Accepted,
    RejectedEpoch,
    RejectedStopped,
    OverflowFailed,
}

/**
 * JVM-testable playback epoch, queue, remainder, and final-fragment rules shared with
 * [com.novadrive.app.voice.PcmAudioPlayer]. No Android audio APIs.
 */
class PlaybackEpochEngine(
    private var sampleRateHz: Int = 16_000,
) {
    var acceptEpoch: Int = 0
        private set
    var completedThroughEpoch: Int = Int.MIN_VALUE
        private set
    private val queue = ArrayDeque<QueuedPlaybackPcm>()
    private val sliceRemainder = ByteArrayOutputStream()
    private var remainderEpoch: Int? = null

    /** Device slice awaiting a (possibly partial) write; test seam only. */
    var pendingSlice: ByteArray? = null
    var pendingSliceOffset: Int = 0

    /** Accepted byte prefixes in write order (render-reference seam). */
    val renderReference = mutableListOf<ByteArray>()

    fun configureSampleRate(sampleRateHz: Int) {
        require(sampleRateHz in 8_000..48_000)
        this.sampleRateHz = sampleRateHz
    }

    fun resetForStart(epoch: Int) {
        acceptEpoch = epoch
        completedThroughEpoch = epoch - 1
        queue.clear()
        sliceRemainder.reset()
        remainderEpoch = null
        pendingSlice = null
        pendingSliceOffset = 0
        renderReference.clear()
    }

    fun beginReply(epoch: Int) {
        acceptEpoch = epoch
    }

    fun complete(epoch: Int) {
        if (epoch != acceptEpoch) return
        completedThroughEpoch = maxOf(completedThroughEpoch, epoch)
    }

    fun flush(epoch: Int) {
        queue.clear()
        sliceRemainder.reset()
        remainderEpoch = null
        pendingSlice = null
        pendingSliceOffset = 0
        acceptEpoch = epoch
        completedThroughEpoch = maxOf(completedThroughEpoch, epoch - 1)
    }

    fun failReply(epoch: Int) {
        queue.clear()
        sliceRemainder.reset()
        remainderEpoch = null
        pendingSlice = null
        pendingSliceOffset = 0
        completedThroughEpoch = maxOf(completedThroughEpoch, epoch)
    }

    fun enqueue(
        pcm: ByteArray,
        epoch: Int,
        running: Boolean,
    ): PlaybackEnqueueResult {
        if (!running) return PlaybackEnqueueResult.RejectedStopped
        if (epoch != acceptEpoch || epoch <= completedThroughEpoch) {
            return PlaybackEnqueueResult.RejectedEpoch
        }
        val queuedBytes = queue.sumOf { it.pcm.size }
        val remainderBytes = sliceRemainder.size()
        val unwrittenSliceBytes = ((pendingSlice?.size ?: 0) - pendingSliceOffset).coerceAtLeast(0)
        if (
            AppPlaybackQueuePolicy.wouldExceedLimit(
                queuedBytes = queuedBytes,
                remainderBytes = remainderBytes,
                unwrittenSliceBytes = unwrittenSliceBytes,
                incomingBytes = pcm.size,
                sampleRateHz = sampleRateHz,
            )
        ) {
            failReply(epoch)
            return PlaybackEnqueueResult.OverflowFailed
        }
        queue.addLast(QueuedPlaybackPcm(pcm, epoch))
        return PlaybackEnqueueResult.Accepted
    }

    fun acceptsEpoch(
        epoch: Int,
        running: Boolean,
    ): Boolean = running && epoch == acceptEpoch && epoch > completedThroughEpoch

    /**
     * Build the next 10-ms device slice from queued PCM. Zero-pads only when the remainder belongs
     * to a completed epoch (explicit provider completion).
     */
    fun fillSlice(
        sliceBytes: Int,
        slice: ByteArray,
    ): Boolean {
        while (sliceRemainder.size() < sliceBytes) {
            val next = queue.firstOrNull()
            if (
                sliceRemainder.size() > 0 &&
                remainderEpoch != null &&
                remainderEpoch!! <= completedThroughEpoch &&
                next?.epoch != remainderEpoch
            ) {
                break
            }
            if (queue.isEmpty()) break
            val frame = queue.removeFirst()
            if (remainderEpoch != null && remainderEpoch != frame.epoch) {
                sliceRemainder.reset()
            }
            remainderEpoch = frame.epoch
            sliceRemainder.write(frame.pcm)
        }
        if (
            sliceRemainder.size() < sliceBytes &&
            (sliceRemainder.size() == 0 || remainderEpoch == null || remainderEpoch!! > completedThroughEpoch)
        ) {
            return false
        }
        val data = sliceRemainder.toByteArray()
        slice.fill(0)
        System.arraycopy(data, 0, slice, 0, minOf(data.size, sliceBytes))
        sliceRemainder.reset()
        if (data.size > sliceBytes) {
            sliceRemainder.write(data, sliceBytes, data.size - sliceBytes)
        } else {
            remainderEpoch = null
        }
        return true
    }

    fun stagePendingSlice(sliceBytes: Int, slice: ByteArray): Boolean {
        if (pendingSlice != null) return true
        if (!fillSlice(sliceBytes, slice)) return false
        pendingSlice = slice.copyOf()
        pendingSliceOffset = 0
        return true
    }

    /** Positive short write: retain suffix and record accepted prefix for render reference. */
    fun acceptShortWrite(written: Int): ByteArray? {
        val pending = pendingSlice ?: return null
        if (written <= 0 || written % 2 != 0) return null
        val remaining = pending.size - pendingSliceOffset
        if (written > remaining) return null
        val accepted = pending.copyOfRange(pendingSliceOffset, pendingSliceOffset + written)
        renderReference.add(accepted)
        pendingSliceOffset += written
        if (pendingSliceOffset >= pending.size) {
            pendingSlice = null
            pendingSliceOffset = 0
        }
        return accepted
    }

    fun queuedBytes(): Int =
        queue.sumOf { it.pcm.size } +
            sliceRemainder.size() +
            ((pendingSlice?.size ?: 0) - pendingSliceOffset).coerceAtLeast(0)
}

/** Returns false when a prior worker is still alive and the device must not restart. */
fun mayStartAudioWorker(
    previousWorkerAlive: Boolean,
    running: Boolean,
): Boolean = !(previousWorkerAlive && !running)
