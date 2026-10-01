package com.novadrive.app.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** P44: a CONFIG card leaves once its cause is fixed; nothing else is cleared by it. */
class ShownErrorCardTest {
    @Test
    fun aFixedConfigProblemClearsTheConfigCard() {
        val card = ShownErrorCard()
        card.show("CONFIG")
        assertTrue(card.clear("CONFIG"))
        assertNull(card.code)
    }

    @Test
    fun aValidConfigDoesNotHideAnUnrelatedError() {
        val card = ShownErrorCard()
        card.show("NETWORK")
        assertFalse(card.clear("CONFIG"))
    }

    @Test
    fun nothingShownMeansNothingToClear() {
        assertFalse(ShownErrorCard().clear("CONFIG"))
    }

    @Test
    fun aTranscriptLineReplacesTheCard() {
        val card = ShownErrorCard()
        card.show("CONFIG")
        card.overwritten()
        assertFalse(card.clear("CONFIG"))
    }

    @Test
    fun aNewerErrorReplacesTheConfigCard() {
        val card = ShownErrorCard()
        card.show("CONFIG")
        card.show("SESSION")
        assertFalse(card.clear("CONFIG"))
        assertTrue(card.clear("SESSION"))
    }

    @Test
    fun aSecondClearIsANoOp() {
        val card = ShownErrorCard()
        card.show("CONFIG")
        card.clear("CONFIG")
        assertFalse(card.clear("CONFIG"))
    }
}
