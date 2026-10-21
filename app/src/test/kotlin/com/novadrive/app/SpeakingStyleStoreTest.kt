package com.novadrive.app

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * SPEC-015 A6 / B6: the style survives restarts. [SpeakingStyleStore] needs an Android Context, so
 * the prefs are a map driven through the store's own encode/decode seam and [SpeakingStyleState].
 */
class SpeakingStyleStoreTest {
    private val prefs = mutableMapOf<String, String>()

    private fun load(): SpeakingStyle = SpeakingStyleStore.decode(prefs[SpeakingStyleStore.KEY])

    /** What MainActivity does at startup: restore, then wire persistence. */
    private fun startApp() {
        SpeakingStyleState.restore(load())
        SpeakingStyleState.persist = { prefs[SpeakingStyleStore.KEY] = SpeakingStyleStore.encode(it) }
    }

    @AfterEach fun reset() {
        SpeakingStyleState.persist = {}
        SpeakingStyleState.restore(SpeakingStyle.DEFAULT)
    }

    @Test fun everyStyleRoundTrips() {
        for (style in SpeakingStyle.entries) {
            assertEquals(style, SpeakingStyleStore.decode(SpeakingStyleStore.encode(style)))
            assertEquals(style.wireName, SpeakingStyleStore.encode(style))
        }
    }

    @Test fun nothingSavedOrUnknownLoadsDefault() {
        assertEquals(SpeakingStyle.DEFAULT, SpeakingStyleStore.decode(null))
        assertEquals(SpeakingStyle.DEFAULT, SpeakingStyleStore.decode(""))
        assertEquals(SpeakingStyle.DEFAULT, SpeakingStyleStore.decode("spicy"))
    }

    @Test fun styleSurvivesARestart() {
        startApp()
        assertEquals(SpeakingStyle.DEFAULT, SpeakingStyleState.current)
        SpeakingStyleState.set(SpeakingStyle.TSUNDERE)
        // process dies: in-memory state is lost, prefs remain
        SpeakingStyleState.persist = {}
        SpeakingStyleState.restore(SpeakingStyle.DEFAULT)
        startApp()
        assertEquals(SpeakingStyle.TSUNDERE, SpeakingStyleState.current)
        assertEquals(SpeakingStyle.TSUNDERE, load())
    }

    @Test fun restoreDoesNotRewriteAndOnlySetChangesTheSavedStyle() {
        prefs[SpeakingStyleStore.KEY] = "gentle"
        val writes = mutableListOf<SpeakingStyle>()
        startApp()
        val save = SpeakingStyleState.persist
        SpeakingStyleState.persist = { writes += it; save(it) }
        assertEquals(SpeakingStyle.GENTLE, SpeakingStyleState.current)
        assertEquals(emptyList<SpeakingStyle>(), writes)
        SpeakingStyleState.set(SpeakingStyle.DEFAULT)
        assertEquals(listOf(SpeakingStyle.DEFAULT), writes)
        assertEquals(SpeakingStyle.DEFAULT, load())
    }
}
