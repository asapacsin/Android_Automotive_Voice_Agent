package com.novadrive.app

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpeakingStyleTest {
    @AfterEach fun reset() {
        SpeakingStyleState.persist = {}
        SpeakingStyleState.restore(SpeakingStyle.DEFAULT)
    }

    @Test fun fromWire() {
        assertEquals(SpeakingStyle.SWEET, SpeakingStyle.fromWire("sweet"))
        assertEquals(SpeakingStyle.SWEET, SpeakingStyle.fromWire(" SWEET "))
        assertEquals(SpeakingStyle.DEFAULT, SpeakingStyle.fromWire("default"))
        assertEquals(SpeakingStyle.DEFAULT, SpeakingStyle.fromWire(null))
        assertEquals(SpeakingStyle.DEFAULT, SpeakingStyle.fromWire("spicy"))
    }

    @Test fun setPersistsRestoreDoesNot() {
        val saved = mutableListOf<SpeakingStyle>()
        SpeakingStyleState.persist = { saved += it }
        SpeakingStyleState.restore(SpeakingStyle.SWEET)
        assertEquals(SpeakingStyle.SWEET, SpeakingStyleState.current)
        assertEquals(emptyList<SpeakingStyle>(), saved)
        SpeakingStyleState.set(SpeakingStyle.DEFAULT)
        assertEquals(SpeakingStyle.DEFAULT, SpeakingStyleState.current)
        assertEquals(listOf(SpeakingStyle.DEFAULT), saved)
    }
}
