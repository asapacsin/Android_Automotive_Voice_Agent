package com.novadrive.ingress.realtime

/**
 * Elapsed-time helpers for migrating capture cadence without changing per-100-ms behaviour.
 */
object AudioFrameTiming {
    const val LEGACY_FRAME_MS = 100
    const val CAPTURE_FRAME_MS = 10
    const val CAPTURE_FRAME_BYTES_16K = 320

    fun scaledRiseFactor(
        riseFactorPer100Ms: Double,
        frameMs: Int = CAPTURE_FRAME_MS,
    ): Double = Math.pow(riseFactorPer100Ms, frameMs.toDouble() / LEGACY_FRAME_MS)

    fun scaledNoiseAdapt(
        adaptPer100Ms: Double,
        frameMs: Int = CAPTURE_FRAME_MS,
    ): Double = adaptPer100Ms * frameMs / LEGACY_FRAME_MS

    fun heldOutboundMessagesForDuration(
        durationMs: Int,
        frameMs: Int = CAPTURE_FRAME_MS,
    ): Int = ((durationMs + frameMs - 1) / frameMs).coerceAtLeast(1)
}
