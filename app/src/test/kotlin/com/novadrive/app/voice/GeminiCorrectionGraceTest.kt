package com.novadrive.app.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GeminiCorrectionGraceTest {
    @Test
    fun aSpokenCommandWaitsTheConfiguredGrace() = assertEquals(20_000L, GeminiCorrectionGrace.graceMs(true, 20_000L))

    @Test
    fun aChatTurnWaitsOnlyAMoment() = assertEquals(1_500L, GeminiCorrectionGrace.graceMs(false, 20_000L))

    @Test
    fun aShorterConfiguredGraceIsKeptForChat() = assertEquals(200L, GeminiCorrectionGrace.graceMs(false, 200L))
}
