package com.novadrive.app.voice

/**
 * SPEC-018 A4: the current response is cancelled only when no guidance prompt is open, so
 * 闭嘴/休眠/wake word in ACTIVE spare an open guidance prompt.
 */
internal class GuidanceSparingCancel(
    private val guidanceActive: () -> Boolean,
    private val cancel: () -> Unit,
) {
    fun cancelUnlessGuidance() {
        if (!guidanceActive()) cancel()
    }
}
