package com.novadrive.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class NavigationAutoPickTest {
    @BeforeEach
    fun reset() {
        NavigationAutoPick.clear()
    }

    @Test
    fun pickIndexPrefersExactMatchOverStartsWith() {
        val texts = listOf("天安门广场(出入口)", "天安门广场")
        assertEquals(1, NavigationAutoPick.pickIndex(texts, "天安门广场"))
    }

    @Test
    fun pickIndexSkipsDestinationTitle() {
        val texts = listOf("输入终点", "天安门广场")
        assertEquals(1, NavigationAutoPick.pickIndex(texts, "天安门广场"))
    }

    @Test
    fun pickIndexReturnsNullWhenNothingMatches() {
        assertNull(NavigationAutoPick.pickIndex(listOf("输入终点", "故宫"), "天安门广场"))
    }

    @Test
    fun activeReturnsNullAfterExpiryAndClearsPending() {
        NavigationAutoPick.arm("天安门广场", 1_000L)
        assertNull(NavigationAutoPick.active(1_000L + NavigationAutoPick.WINDOW_MS))
        assertNull(NavigationAutoPick.pending)
    }

    @Test
    fun debouncedIsTrueWithinOneAndAHalfSecondsOfAdvance() {
        NavigationAutoPick.arm("天安门广场", 10_000L)
        NavigationAutoPick.advance(10_000L)
        assertTrue(NavigationAutoPick.debounced(10_000L + 1_499L))
        assertFalse(NavigationAutoPick.debounced(10_000L + 1_500L))
    }
}
