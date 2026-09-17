package com.novadrive.app.voice

import com.novadrive.app.BaiduApiConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VoiceSessionGatewayTest {
    private val identities = mutableListOf<Any>()

    @AfterEach
    fun detachAll() {
        identities.forEach { VoiceSessionGateway.detachInternal(it) }
        identities.clear()
    }

    @Test
    fun attachStartStopAndIsActive() {
        val identity = Any().also { identities += it }
        val session = FakeGatewaySession()
        val service = FakeServiceControl()
        VoiceSessionGateway.attachInternal(identity, session, service)

        assertFalse(VoiceSessionGateway.isActive)
        assertEquals(StartResult.Started, VoiceSessionGateway.start())
        assertTrue(VoiceSessionGateway.isActive)
        assertEquals(1, session.startCalls)
        assertEquals(1, service.startCalls)

        assertEquals(StartResult.AlreadyActive, VoiceSessionGateway.start())
        assertEquals(1, session.startCalls)

        VoiceSessionGateway.stop()
        assertFalse(VoiceSessionGateway.isActive)
        assertEquals(1, session.stopCalls)
        assertEquals(1, service.stopCalls)
    }

    @Test
    fun startReturnsMicPermissionMissingWithoutStarting() {
        val identity = Any().also { identities += it }
        val session = FakeGatewaySession(hasMic = false)
        VoiceSessionGateway.attachInternal(identity, session, FakeServiceControl())
        assertEquals(StartResult.MicPermissionMissing, VoiceSessionGateway.start())
        assertEquals(0, session.startCalls)
    }

    @Test
    fun speakStartsTheSessionWhenIdleThenSendsTheText() {
        val identity = Any().also { identities += it }
        val session = FakeGatewaySession()
        val service = FakeServiceControl()
        VoiceSessionGateway.attachInternal(identity, session, service)

        assertEquals(StartResult.Started, VoiceSessionGateway.speak("读一下"))
        assertEquals(1, session.startCalls)
        assertEquals(listOf("读一下"), session.texts)

        assertEquals(StartResult.AlreadyActive, VoiceSessionGateway.speak("再读一下"))
        assertEquals(1, session.startCalls)
        assertEquals(listOf("读一下", "再读一下"), session.texts)
    }

    @Test
    fun speakSendsNothingWhenTheSessionCannotStart() {
        val identity = Any().also { identities += it }
        val session = FakeGatewaySession(hasMic = false)
        VoiceSessionGateway.attachInternal(identity, session, FakeServiceControl())
        assertEquals(StartResult.MicPermissionMissing, VoiceSessionGateway.speak("读一下"))
        assertTrue(session.texts.isEmpty())
        assertEquals(StartResult.NotAttached, run {
            VoiceSessionGateway.detachInternal(identity)
            VoiceSessionGateway.speak("读一下")
        })
    }

    @Test
    fun wakeOrUiResumesAStandbySessionWithoutANewConnection() {
        val identity = Any().also { identities += it }
        val session = FakeGatewaySession()
        VoiceSessionGateway.attachInternal(identity, session, FakeServiceControl())
        VoiceSessionGateway.start("wake_word")
        VoiceSessionGateway.standby("ui")
        assertEquals(ListeningState.STANDBY, VoiceSessionGateway.listeningState)
        assertEquals(StartResult.AlreadyActive, VoiceSessionGateway.start("wake_word"))
        assertEquals(listOf("wake_word"), session.activations)
        assertEquals(1, session.startCalls, "the same session is resumed")
        assertEquals(ListeningState.ACTIVE, VoiceSessionGateway.listeningState)
    }

    @Test
    fun appPromptsResumeListeningBeforeSpeaking() {
        val identity = Any().also { identities += it }
        val session = FakeGatewaySession()
        VoiceSessionGateway.attachInternal(identity, session, FakeServiceControl())
        VoiceSessionGateway.start()
        VoiceSessionGateway.standby("ui")
        VoiceSessionGateway.speak("读一下")
        assertEquals(listOf("app_prompt"), session.activations)
        assertEquals(listOf("读一下"), session.texts)
    }

    @Test
    fun endConversationNeedsARunningSession() {
        val identity = Any().also { identities += it }
        val session = FakeGatewaySession()
        VoiceSessionGateway.attachInternal(identity, session, FakeServiceControl())
        assertFalse(VoiceSessionGateway.standbyAfterReply("end_conversation"))
        VoiceSessionGateway.start()
        assertTrue(VoiceSessionGateway.standbyAfterReply("end_conversation"))
        assertEquals(listOf("after:end_conversation"), session.standbys)
        assertEquals(ListeningState.DEEP_IDLE, run {
            VoiceSessionGateway.stop()
            VoiceSessionGateway.listeningState
        })
    }

    @Test
    fun startReturnsNotAttachedWhenEmpty() {
        assertEquals(StartResult.NotAttached, VoiceSessionGateway.start())
    }

    @Test
    fun releasingFirstOwnerDoesNotClearSecondOwner() {
        val first = Any().also { identities += it }
        val second = Any().also { identities += it }
        val firstSession = FakeGatewaySession()
        val secondSession = FakeGatewaySession()
        VoiceSessionGateway.attachInternal(first, firstSession, FakeServiceControl())
        VoiceSessionGateway.attachInternal(second, secondSession, FakeServiceControl())

        VoiceSessionGateway.detachInternal(first)

        assertTrue(VoiceSessionGateway.isAttached(second))
        assertFalse(VoiceSessionGateway.isAttached(first))
        assertEquals(StartResult.Started, VoiceSessionGateway.start())
        assertEquals(0, firstSession.startCalls)
        assertEquals(1, secondSession.startCalls)
    }

    private class FakeGatewaySession(
        var hasMic: Boolean = true,
    ) : GatewaySession {
        var startCalls = 0
        var stopCalls = 0
        val texts = mutableListOf<String>()
        override var isActive: Boolean = false
        override fun hasMicPermission(): Boolean = hasMic
        override fun sendText(text: String) {
            texts += text
        }
        val activations = mutableListOf<String>()
        val standbys = mutableListOf<String>()
        override var listeningState: ListeningState = ListeningState.DEEP_IDLE
        override fun startBaidu(reason: String) {
            startCalls += 1
            isActive = true
            listeningState = ListeningState.ACTIVE
        }
        override fun activate(reason: String) {
            activations += reason
            listeningState = ListeningState.ACTIVE
        }
        override fun standby(reason: String) {
            standbys += reason
            listeningState = ListeningState.STANDBY
        }
        override fun standbyAfterReply(reason: String) {
            standbys += "after:$reason"
        }
        override fun stop() {
            stopCalls += 1
            isActive = false
        }
    }

    private class FakeServiceControl : SessionServiceControl {
        var startCalls = 0
        var stopCalls = 0
        override fun start() {
            startCalls += 1
        }
        override fun stop() {
            stopCalls += 1
        }
        override fun hasMicPermission(): Boolean = true
        override fun baiduConfig(): BaiduApiConfig = error("not used")
    }
}
