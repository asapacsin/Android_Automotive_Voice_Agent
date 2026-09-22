package com.novadrive.app.voice

import android.media.AudioManager
import com.novadrive.app.NavigationState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Permitted replies during navigation must restore duck volume before enqueueing PCM.
 */
class NavigationConfirmationAudioTest {
    private val player = PcmAudioPlayer { }

    @BeforeEach
    fun setup() {
        NavigationState.reset()
        player.duck()
    }

    @AfterEach
    fun teardown() = NavigationState.reset()

    @Test
    fun permittedReplyUnducksBeforeEnqueue() {
        NavigationState.begin()
        NavigationState.allowConfirmation()
        val port = AndroidPlaybackPort(player) { true }
        port.enqueue(byteArrayOf(1, 2, 3, 4), 0)
        assertTrue(player.unduckCount >= 1, "confirmation audio must restore full volume")
    }

    @Test
    fun mutedNavigationReplyDoesNotUnduck() {
        NavigationState.begin()
        val before = player.unduckCount
        val port = AndroidPlaybackPort(player) { true }
        port.enqueue(byteArrayOf(1, 2, 3, 4), 0)
        assertEquals(before, player.unduckCount)
    }

    @Test
    fun guidanceDuckIsIgnoredDuringConfirmationWindow() {
        NavigationState.begin()
        NavigationState.allowConfirmation()
        val port = AndroidPlaybackPort(player) { true }
        val before = player.duckCount
        port.applyFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertEquals(before, player.duckCount, "confirmation reply must not be ducked under guidance")
    }

    @Test
    fun guidanceDuckStillAppliesOutsideConfirmationWindow() {
        NavigationState.begin()
        val port = AndroidPlaybackPort(player) { true }
        val before = player.duckCount
        port.applyFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertEquals(before + 1, player.duckCount)
    }
}
