package com.novadrive.app.voice

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GuidanceMicGateTest {
    private fun TestScope.gate(changes: MutableList<Boolean>) =
        GuidanceMicGate(this, { changes += it }, tailMs = 500, maxClosedMs = 20_000)

    @Test
    fun closesAtOnceWhenGuidanceStarts() = runTest {
        val changes = mutableListOf<Boolean>()
        val gate = gate(changes)
        gate.onGuidanceSpeaking(true)
        assertTrue(gate.closed)
        assertEquals(listOf(true), changes)
        gate.reset()
    }

    @Test
    fun reopensOnlyAfterTheTail() = runTest {
        val changes = mutableListOf<Boolean>()
        val gate = gate(changes)
        gate.onGuidanceSpeaking(true)
        gate.onGuidanceSpeaking(false)
        advanceTimeBy(499)
        runCurrent()
        assertTrue(gate.closed, "the echo of the last word must not be sent")
        advanceTimeBy(2)
        runCurrent()
        assertFalse(gate.closed)
        assertEquals(listOf(true, false), changes)
    }

    @Test
    fun guidanceResumingDuringTheTailKeepsItClosed() = runTest {
        val changes = mutableListOf<Boolean>()
        val gate = gate(changes)
        gate.onGuidanceSpeaking(true)
        gate.onGuidanceSpeaking(false)
        advanceTimeBy(300)
        gate.onGuidanceSpeaking(true)
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(gate.closed)
        assertEquals(listOf(true), changes, "no open/close flicker between two prompts")
        gate.reset()
    }

    @Test
    fun aLostEndCallbackCannotLeaveTheAssistantDeaf() = runTest {
        val changes = mutableListOf<Boolean>()
        val gate = gate(changes)
        gate.onGuidanceSpeaking(true)
        advanceTimeBy(19_999)
        runCurrent()
        assertTrue(gate.closed)
        advanceTimeBy(2)
        runCurrent()
        assertFalse(gate.closed)
    }

    @Test
    fun resetOpensImmediatelyAndCancelsPendingWork() = runTest {
        val changes = mutableListOf<Boolean>()
        val gate = gate(changes)
        gate.onGuidanceSpeaking(true)
        gate.reset()
        assertFalse(gate.closed)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(listOf(true, false), changes)
    }

    @Test
    fun anEndWithoutAStartChangesNothing() = runTest {
        val changes = mutableListOf<Boolean>()
        val gate = gate(changes)
        gate.onGuidanceSpeaking(false)
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(gate.closed)
        assertTrue(changes.isEmpty())
    }
}
