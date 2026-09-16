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
        override var isActive: Boolean = false
        override fun hasMicPermission(): Boolean = hasMic
        override fun startBaidu() {
            startCalls += 1
            isActive = true
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
