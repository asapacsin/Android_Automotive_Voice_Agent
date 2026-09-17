package com.novadrive.app.voice

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpeechOutputTest {
    @AfterEach
    fun restore() {
        SpeechOutput.setSilent(false, "test")
    }

    @Test
    fun silentModeIsSwitchedOnceAndListenersHearIt() {
        val seen = mutableListOf<Boolean>()
        val listener: (Boolean) -> Unit = { seen += it }
        SpeechOutput.addListener(listener)
        try {
            assertTrue(SpeechOutput.setSilent(true, "test"))
            assertFalse(SpeechOutput.setSilent(true, "test"), "already silent")
            assertTrue(SpeechOutput.silent)
            assertTrue(SpeechOutput.setSilent(false, "test"))
            assertEquals(listOf(true, false), seen)
        } finally {
            SpeechOutput.removeListener(listener)
        }
    }
}
