package com.novadrive.app.nav.amap

import com.novadrive.app.nav.NavigationGuidanceVoice
import com.novadrive.app.voice.HostAudioTap
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/** SPEC-018 A7: the text panel and the host tap are fed from Amap's text callback. */
class AmapGuidanceVoiceTest {
    private val panel = mutableListOf<String>()
    private val host = mutableListOf<String>()

    private fun wire() {
        AmapGuidanceVoice.textReceiver = { panel += it }
        HostAudioTap.guidanceSink = { host += it }
    }

    @AfterEach
    fun tearDown() {
        AmapGuidanceVoice.textReceiver = null
        HostAudioTap.guidanceSink = null
        if (NavigationGuidanceVoice.speaking) NavigationGuidanceVoice.onPlayEnd()
    }

    @Test
    fun toggleOffPlayStartFeedsPanelAndHostTap() {
        wire()
        AmapGuidanceVoice.onPlayStart("前方左转")
        assertEquals(listOf("前方左转"), panel)
        assertEquals(listOf("前方左转"), host)
        AmapGuidanceVoice.onPlayEnd("前方左转")
    }

    @Test
    fun blankPlayStartFeedsNothing() {
        wire()
        AmapGuidanceVoice.onPlayStart("")
        AmapGuidanceVoice.onPlayStart(null)
        assertTrue(panel.isEmpty())
        assertTrue(host.isEmpty())
    }

    @Test
    fun navigationTextWithoutAnActiveRelayFeedsNothing() {
        wire()
        AmapGuidanceVoice.onNavigationText("前方左转")
        assertTrue(panel.isEmpty(), "toggle off: the play listener feeds the panel, not the text callback")
        assertTrue(host.isEmpty())
    }

    @Test
    fun noReceiversIsSafe() {
        AmapGuidanceVoice.onPlayStart("前方左转")
        AmapGuidanceVoice.onPlayEnd("前方左转")
    }

    @Test
    @Disabled("GAP: no test seam to install an active GuidanceRelay without AMapNavi (relay is private, set only by enable)")
    fun toggleOnNavigationTextFeedsPanelButNotHostTap() {
    }

    @Test
    @Disabled("GAP: no test seam to install an active GuidanceRelay without AMapNavi (relay is private, set only by enable)")
    fun toggleOnPlayStartDoesNotFeedPanelTwice() {
    }
}
