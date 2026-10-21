package com.novadrive.app.nav.amap

import com.novadrive.app.nav.Cancellable
import com.novadrive.app.nav.GuidanceArbiterView
import com.novadrive.app.nav.GuidanceRelay
import com.novadrive.app.nav.GuidanceSession
import com.novadrive.app.nav.GuidanceSpeaker
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
    private val sent = mutableListOf<String>()
    private val relayField = AmapGuidanceVoice::class.java.getDeclaredField("relay").apply { isAccessible = true }

    private fun relay() = GuidanceRelay(
        clock = { 0L },
        schedule = { _, _ -> Cancellable { } },
        session = object : GuidanceSession {
            override fun blocker(): String? = null
            override fun sendPrompt(text: String, promptId: String): Boolean { sent += promptId; return true }
        },
        amap = GuidanceSpeaker { true },
        arbiter = object : GuidanceArbiterView {
            override fun amapSpeaking() = false
            override fun transientFocusLoss() = false
            override fun abandon(promptId: String) {}
            override fun bracket(speaking: Boolean) {}
        },
        enabled = { true },
        log = {},
    )

    private fun wire() {
        AmapGuidanceVoice.textReceiver = { panel += it }
        HostAudioTap.guidanceSink = { host += it }
    }

    private val defaultPoster = AmapGuidanceVoice.poster

    @AfterEach
    fun tearDown() {
        AmapGuidanceVoice.poster = defaultPoster
        relayField.set(null, null)
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
    fun toggleOnNavigationTextFeedsPanelButNotHostTap() {
        wire()
        relayField.set(null, relay())
        AmapGuidanceVoice.onNavigationText("前方左转")
        assertEquals(listOf("前方左转"), panel)
        assertTrue(host.isEmpty(), "relay on: the host hears only Amap's fallback")
        assertEquals(1, sent.size, "one prompt reached the session")
    }

    @Test
    fun toggleOnPlayStartDoesNotFeedPanelTwice() {
        wire()
        AmapGuidanceVoice.poster = { it() }
        relayField.set(null, relay())
        AmapGuidanceVoice.onNavigationText("前方左转")
        AmapGuidanceVoice.onPlayStart("前方左转")
        AmapGuidanceVoice.onPlayEnd("前方左转")
        assertEquals(listOf("前方左转"), panel, "relay on: the panel is fed once, from the text callback")
        assertTrue(host.isEmpty(), "relay on: onPlayStart does not feed the host tap")
    }

    @Test
    @Disabled("GAP: the Amap fallback speaker (host tap feed) is built in buildRelay from a real AMapNavi, not constructible in JVM unit tests")
    fun toggleOnHostTapFedOnlyThroughAmapFallback() {
    }
}
