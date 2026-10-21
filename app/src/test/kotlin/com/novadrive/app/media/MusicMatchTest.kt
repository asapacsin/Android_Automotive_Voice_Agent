package com.novadrive.app.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MusicMatchTest {
    private fun np(t: String?, a: String?) = NowPlaying(t, a, true, "p")

    @Test fun cjkBracketsAndCase() {
        val r = MusicRequest("《oblivious》", null, null, null, null)
        assertTrue(MusicMatch.matches(r, np("Oblivious", "Kalafina")))
        assertEquals("oblivious", MusicMatch.normalise("「Ob livious」·、"))
    }

    @Test fun fullWidthNormalised() {
        val r = MusicRequest("ＯＢＬＩＶＩＯＵＳ", null, null, null, null)
        assertTrue(MusicMatch.matches(r, np("oblivious (TV size)", null)))
    }

    @Test fun artistOverlap() {
        val r = MusicRequest(null, "梶浦由記", null, null, null)
        assertTrue(MusicMatch.matches(r, np("光の旋律", "Kalafina / 梶浦由記")))
        assertFalse(MusicMatch.matches(MusicRequest(null, "Kalafina", null, null, null), np("x", "LiSA")))
    }

    @Test fun excludeWins() {
        val r = MusicRequest("oblivious", "Kalafina", null, null, null, excludeTitle = "oblivious")
        assertFalse(MusicMatch.matches(r, np("oblivious", "Kalafina")))
    }

    @Test fun blankNeverMatches() {
        assertFalse(MusicMatch.matches(MusicRequest(null, null, null, "燃", null), np(null, null)))
    }
}
