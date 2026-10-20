package com.novadrive.app.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GuidanceSparingCancelTest {
    @Test
    fun openGuidanceIsSpared() {
        var cancels = 0
        GuidanceSparingCancel({ true }, { cancels++ }).cancelUnlessGuidance()
        assertEquals(0, cancels)
    }

    @Test
    fun noGuidanceCancelsOnce() {
        var cancels = 0
        GuidanceSparingCancel({ false }, { cancels++ }).cancelUnlessGuidance()
        assertEquals(1, cancels)
    }

    @Test
    fun guidanceStateIsReadAtEachCall() {
        var guidance = true
        var cancels = 0
        val sut = GuidanceSparingCancel({ guidance }, { cancels++ })
        sut.cancelUnlessGuidance()
        guidance = false
        sut.cancelUnlessGuidance()
        assertEquals(1, cancels)
    }
}
