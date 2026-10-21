package com.novadrive.app.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SpeakableTextTest {
    @Test
    fun aPlaceholderIsNeverReadAloud() {
        assertNull(speakableText("<no speech>{pause}"))
        assertNull(speakableText("  {pause} "))
        assertNull(speakableText("。"))
    }

    @Test
    fun wordsAreKeptAndMarkupRemoved() {
        assertEquals("好的，空调开了。", speakableText("好的，空调开了。"))
        assertEquals("好的。", speakableText("{pause}好的。<breath>"))
    }
}