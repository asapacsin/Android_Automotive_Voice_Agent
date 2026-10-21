package com.novadrive.app.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MediaSearchSpecsTest {
    private fun req(t: String? = null, a: String? = null, w: String? = null, m: String? = null, q: String? = null) =
        MusicRequest(t, a, w, m, q)

    @Test fun titleFocusesAudio() {
        val s = MediaSearchSpecs.build(req(t = "oblivious", a = "Kalafina", w = "空之境界"))
        assertEquals(MediaSearchSpecs.FOCUS_AUDIO, s.focus)
        assertEquals("oblivious Kalafina", s.query)
        assertEquals("oblivious", s.extras[MediaSearchSpecs.EXTRA_TITLE])
        assertEquals("Kalafina", s.extras[MediaSearchSpecs.EXTRA_ARTIST])
        assertEquals("空之境界", s.extras[MediaSearchSpecs.EXTRA_ALBUM])
        assertEquals(MediaSearchSpecs.FOCUS_AUDIO, s.extras["android.intent.extra.focus"])
        assertEquals("oblivious Kalafina", s.extras["query"])
    }

    @Test fun artistOnlyFocusesArtist() {
        val s = MediaSearchSpecs.build(req(a = "梶浦由記"))
        assertEquals(MediaSearchSpecs.FOCUS_ARTIST, s.focus)
        assertEquals("梶浦由記", s.query)
        assertNull(s.extras[MediaSearchSpecs.EXTRA_TITLE])
    }

    @Test fun workOnlyFocusesAlbum() {
        val s = MediaSearchSpecs.build(req(w = "空之境界"))
        assertEquals(MediaSearchSpecs.FOCUS_ALBUM, s.focus)
        assertEquals("空之境界", s.query)
    }

    @Test fun workAndArtistIsUnstructuredButComposed() {
        val s = MediaSearchSpecs.build(req(a = "梶浦由記", w = "空之境界"))
        assertNull(s.focus)
        assertEquals("空之境界 梶浦由記", s.query)
        assertFalse(s.extras.containsKey(MediaSearchSpecs.EXTRA_FOCUS))
    }

    @Test fun queryAndMoodFallbacks() {
        assertEquals("燃的动漫歌", MediaSearchSpecs.build(req(q = " 燃的动漫歌 ", m = "燃")).query)
        val mood = MediaSearchSpecs.build(req(m = "燃"))
        assertNull(mood.focus)
        assertEquals("燃", mood.query)
    }

    @Test fun queryIsCapped() {
        val s = MediaSearchSpecs.build(req(q = "x".repeat(200)))
        assertEquals(80, s.query.length)
    }

    @Test fun emptyRequest() {
        assertTrue(req(t = " ", q = "").isEmpty)
        assertFalse(req(m = "燃").isEmpty)
        assertEquals("taw", req(t = "a", a = "b", w = "c").fieldFlags())
    }
}
