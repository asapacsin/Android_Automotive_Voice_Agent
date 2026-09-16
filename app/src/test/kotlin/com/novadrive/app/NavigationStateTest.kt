package com.novadrive.app

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class NavigationStateTest {
    @BeforeEach
    fun clearState() {
        NavigationState.reset()
    }

    @AfterEach
    fun clearListener() {
        NavigationState.onNavigatingChanged = null
    }

    @Test
    fun notNavigatingDoesNotMuteSpeech() {
        assertFalse(NavigationState.shouldMuteSpeech(1_000L))
    }

    @Test
    fun beginMutesSpeech() {
        NavigationState.begin()
        assertTrue(NavigationState.shouldMuteSpeech(1_000L))
    }

    @Test
    fun allowConfirmationUnmutesInsideWindow() {
        val now = 5_000L
        NavigationState.begin()
        NavigationState.allowConfirmation(now)
        assertFalse(NavigationState.shouldMuteSpeech(now + 1_000L))
    }

    @Test
    fun allowConfirmationMutesAfterWindowExpires() {
        val now = 5_000L
        NavigationState.begin()
        NavigationState.allowConfirmation(now)
        assertTrue(NavigationState.shouldMuteSpeech(now + 11_000L))
    }

    @Test
    fun beginThenResetUnmutesSpeech() {
        NavigationState.begin()
        NavigationState.reset()
        assertFalse(NavigationState.shouldMuteSpeech())
    }

    @Test
    fun resetClearsNavigatingAndConfirmationWindow() {
        val now = 5_000L
        NavigationState.begin()
        NavigationState.allowConfirmation(now)
        NavigationState.reset()
        assertFalse(NavigationState.navigating)
        assertFalse(NavigationState.shouldMuteSpeech(now + 1_000L))
        assertFalse(NavigationState.shouldMuteSpeech(now + 11_000L))
    }

    @Test
    fun beginFiresOnNavigatingChangedOnce() {
        val seen = mutableListOf<Boolean>()
        NavigationState.onNavigatingChanged = { seen += it }
        NavigationState.begin()
        NavigationState.begin()
        assertEquals(listOf(true), seen)
    }

    @Test
    fun resetFiresOnNavigatingChangedOnce() {
        val seen = mutableListOf<Boolean>()
        NavigationState.begin()
        NavigationState.onNavigatingChanged = { seen += it }
        NavigationState.reset()
        NavigationState.reset()
        assertEquals(listOf(false), seen)
    }

    @Test
    fun throwingListenerDoesNotPreventStateChange() {
        NavigationState.onNavigatingChanged = { throw RuntimeException("listener") }
        NavigationState.begin()
        assertTrue(NavigationState.navigating)
        NavigationState.reset()
        assertFalse(NavigationState.navigating)
    }
}
