package com.novadrive.app

import com.novadrive.app.voice.SpeechArbiter
import com.novadrive.app.voice.SpeechAuthority
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * `NavigationState` is now only the navigating flag; the P1 window it used to hold lives in
 * `SpeechArbiter` (SPEC-012 step 3). These are the same cases, asked of the process arbiter that
 * `NavigationState` informs, on a controlled clock.
 */
class NavigationStateTest {
    private var now = 0L

    @BeforeEach
    fun clearState() {
        SpeechAuthority.resetForTest { now }
        NavigationState.reset()
    }

    @AfterEach
    fun clearListener() {
        NavigationState.onNavigatingChanged = null
        NavigationState.reset()
        SpeechAuthority.resetForTest()
    }

    private fun mutedAt(t: Long): Boolean { now = t; return SpeechAuthority.arbiter.navigationMuted() }
    private fun at(t: Long, event: SpeechArbiter.() -> Unit) { now = t; SpeechAuthority.arbiter.event() }

    @Test
    fun notNavigatingDoesNotMuteSpeech() {
        assertFalse(mutedAt(1_000L))
    }

    @Test
    fun beginMutesSpeech() {
        NavigationState.begin()
        assertTrue(mutedAt(1_000L))
    }

    @Test
    fun allowConfirmationUnmutesInsideWindow() {
        val now = 5_000L
        NavigationState.begin()
        at(now) { onConfirmation() }
        assertFalse(mutedAt(now + 1_000L))
    }

    @Test
    fun allowConfirmationMutesAfterWindowExpires() {
        val now = 5_000L
        NavigationState.begin()
        at(now) { onConfirmation() }
        assertTrue(mutedAt(now + 11_000L))
    }

    @Test
    fun theAnswerToTheDriversQuestionIsSpokenDuringNavigation() {
        NavigationState.begin()
        assertTrue(mutedAt(20_000L), "unprompted speech stays muted")
        at(20_000L) { onDriverRequest() }
        assertFalse(mutedAt(20_500L))
    }

    @Test
    fun aPermittedReplyIsNeverCutOffMidSentence() {
        NavigationState.begin()
        at(0L) { onDriverRequest() }
        // A long reply: frames keep arriving past the original 10 s window.
        for (t in 0L..25_000L step 500L) {
            assertFalse(mutedAt(t), "t=$t")
            at(t) { onReplyAudio() }
        }
        // After it ends, the window closes and unprompted speech is muted again.
        assertTrue(mutedAt(36_000L))
    }

    @Test
    fun speakingCannotOpenAClosedWindow() {
        NavigationState.begin()
        at(20_000L) { onReplyAudio() }
        assertTrue(mutedAt(20_001L))
    }

    @Test
    fun beginThenResetUnmutesSpeech() {
        NavigationState.begin()
        NavigationState.reset()
        assertFalse(mutedAt(now))
    }

    @Test
    fun resetClearsNavigatingAndConfirmationWindow() {
        val now = 5_000L
        NavigationState.begin()
        at(now) { onConfirmation() }
        NavigationState.reset()
        assertFalse(NavigationState.navigating)
        assertFalse(mutedAt(now + 1_000L))
        assertFalse(mutedAt(now + 11_000L))
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
