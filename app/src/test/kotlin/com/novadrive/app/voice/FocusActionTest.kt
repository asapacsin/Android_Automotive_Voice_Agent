package com.novadrive.app.voice

import android.media.AudioManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What losing audio focus does to a reply in progress.
 *
 * The four cases are not interchangeable, and the difference is a promise to the driver: ducking
 * under a navigation prompt keeps the sentence, pausing for something transient keeps it for
 * later, and a permanent loss - a phone call arriving - means what was queued is no longer worth
 * saying when it comes back.
 *
 * This lived inside a `when` in AndroidPlaybackPort, which holds an AudioTrack-backed player, so
 * none of it could be exercised off-device.
 */
class FocusActionTest {
    @Test
    fun aDuckableLossDucksRatherThanStopping() {
        assertEquals(FocusAction.DUCK, focusAction(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK))
    }

    @Test
    fun aTransientLossPausesAndKeepsTheReply() {
        assertEquals(FocusAction.PAUSE, focusAction(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT))
    }

    @Test
    fun aPermanentLossStopsAndDiscardsTheReply() {
        // A reply resumed after a phone call would be answering a question from several minutes ago.
        assertEquals(FocusAction.STOP, focusAction(AudioManager.AUDIOFOCUS_LOSS))
    }

    @Test
    fun regainingFocusResumes() {
        assertEquals(FocusAction.RESUME, focusAction(AudioManager.AUDIOFOCUS_GAIN))
    }

    @Test
    fun anUnknownFocusChangeDoesNothing() {
        // Silently doing nothing beats guessing: a code we do not recognise is not a reason to
        // cut the driver off mid-sentence.
        assertEquals(FocusAction.NOTHING, focusAction(12345))
        assertEquals(FocusAction.NOTHING, focusAction(AudioManager.AUDIOFOCUS_NONE))
    }
}
