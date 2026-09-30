package com.novadrive.app.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GuidanceRelayTest {
    private var now = 0L
    private val timers = mutableListOf<Triple<Long, () -> Unit, BooleanArray>>()
    private var blocker: String? = null
    private var sendAccepted = true
    private val sent = mutableListOf<Pair<String, String>>()
    private val spoken = mutableListOf<String>()
    private val abandoned = mutableListOf<String>()
    private var amapSpeaking = false
    private var focusLoss = false
    private var enabled = true
    private val logs = mutableListOf<String>()

    private val relay = GuidanceRelay(
        clock = { now },
        schedule = { delay, task ->
            val cancelled = BooleanArray(1)
            timers += Triple(now + delay, task, cancelled)
            Cancellable { cancelled[0] = true }
        },
        session = object : GuidanceSession {
            override fun blocker() = blocker
            override fun sendPrompt(text: String, promptId: String): Boolean {
                sent += promptId to text
                return sendAccepted
            }
        },
        amap = GuidanceSpeaker { spoken += it; true },
        arbiter = object : GuidanceArbiterView {
            override fun amapSpeaking() = amapSpeaking
            override fun transientFocusLoss() = focusLoss
            override fun abandon(promptId: String) { abandoned += promptId }
        },
        enabled = { enabled },
        log = { logs += it },
    )

    private fun advance(ms: Long) {
        val target = now + ms
        while (true) {
            val next = timers.filter { !it.third[0] && it.first <= target }.minByOrNull { it.first } ?: break
            timers.remove(next)
            now = next.first
            next.second()
        }
        now = target
    }

    private fun lastRoute() = logs.last { it.startsWith("guidance_route") }

    @Test
    fun everyB1ReasonGoesToAmapAtOnce() {
        val cases = listOf<Pair<String, () -> Unit>>(
            "NOT_CONNECTED" to { blocker = "NOT_CONNECTED" },
            "NO_CAPABILITY" to { blocker = "NO_CAPABILITY" },
            "DRIVER_SPEAKING" to { blocker = "DRIVER_SPEAKING" },
            "WORK_PENDING" to { blocker = "WORK_PENDING" },
            "AMAP_SPEAKING" to { amapSpeaking = true },
            "FOCUS" to { focusLoss = true },
            "DISABLED" to { enabled = false },
        )
        for ((reason, setup) in cases) {
            blocker = null; amapSpeaking = false; focusLoss = false; enabled = true
            setup()
            relay.onGuidanceText("左转")
            assertTrue(lastRoute().contains("to=amap reason=$reason"), reason)
        }
        assertEquals(cases.size, spoken.size)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun sendFailureGoesToAmap() {
        sendAccepted = false
        relay.onGuidanceText("左转")
        assertEquals(listOf("左转"), spoken)
        assertTrue(lastRoute().contains("reason=SEND_FAILED"))
    }

    @Test
    fun sentPromptIsVerbatimAndClaimBeforeDeadlineIsAssistant() {
        relay.onGuidanceText("前方二百米左转")
        assertEquals("g1" to GuidanceRelay.VERBATIM_PREFIX + "前方二百米左转", sent.single())
        advance(800)
        assertTrue(relay.claimAssistant("g1"))
        assertTrue(logs.contains("guidance_claim prompt=g1 side=assistant waited_ms=800"))
        advance(5_000)
        assertTrue(spoken.isEmpty())
        assertTrue(abandoned.isEmpty())
    }

    @Test
    fun deadlineFirstIsAmapAndAbandons() {
        relay.onGuidanceText("左转")
        advance(1_200)
        assertEquals(listOf("g1"), abandoned)
        assertEquals(listOf("左转"), spoken)
        assertTrue(logs.contains("guidance_claim prompt=g1 side=amap waited_ms=1200"))
        assertFalse(relay.claimAssistant("g1"))
    }

    @Test
    fun sameTickClaimThenDeadline() {
        relay.onGuidanceText("左转")
        now = 1_200
        assertTrue(relay.claimAssistant("g1"))
        advance(0)
        assertTrue(spoken.isEmpty())
        assertTrue(abandoned.isEmpty())
    }

    @Test
    fun sameTickDeadlineThenClaim() {
        relay.onGuidanceText("左转")
        advance(1_200)
        assertFalse(relay.claimAssistant("g1"))
        assertEquals(listOf("左转"), spoken)
    }

    @Test
    fun twoConsecutiveFallbacksStartACooldownThenRetry() {
        relay.onGuidanceText("a"); advance(1_200)
        relay.onGuidanceText("b"); advance(1_200)
        assertEquals(2, sent.size)
        relay.onGuidanceText("c")
        assertTrue(lastRoute().contains("reason=COOLDOWN"))
        assertEquals(2, sent.size)
        advance(60_000)
        relay.onGuidanceText("d")
        assertEquals(3, sent.size)
    }

    @Test
    fun assistantClaimResetsTheConsecutiveCounter() {
        relay.onGuidanceText("a"); advance(1_200)
        relay.onGuidanceText("b"); relay.claimAssistant("g2"); relay.onCompleted("g2", "b"); relay.onDrained("g2")
        relay.onGuidanceText("c"); advance(1_200)
        relay.onGuidanceText("d")
        assertEquals(4, sent.size)
    }

    @Test
    fun fifoWaitsForThePlayingPromptAndDeadlineRunsFromEnqueue() {
        relay.onGuidanceText("前方左转")
        relay.claimAssistant("g1")
        advance(100)
        relay.onGuidanceText("前方右转") // g2 waits
        assertEquals(1, sent.size)
        advance(500)
        relay.onCompleted("g1", "前方左转"); relay.onDrained("g1")
        assertEquals("g2", sent.last().first)
        advance(699)
        assertTrue(spoken.isEmpty())
        advance(1) // 1200 ms after g2's enqueue
        assertEquals(listOf("前方右转"), spoken)
    }

    @Test
    fun expiredQueuedPromptIsNeverSpokenOverAPlayingOne() {
        relay.onGuidanceText("前方左转")
        relay.claimAssistant("g1")
        relay.onGuidanceText("前方右转")
        advance(3_000)
        assertTrue(spoken.isEmpty())
        relay.onCompleted("g1", "前方左转"); relay.onDrained("g1")
        assertEquals(listOf("前方右转"), spoken)
        assertTrue(lastRoute().contains("prompt=g2 to=amap reason=EXPIRED"))
        assertEquals(1, sent.size)
    }

    @Test
    fun cutAfterAssistantClaimIsRespokenByAmap() {
        relay.onGuidanceText("前方左转")
        relay.claimAssistant("g1")
        relay.onCut("g1")
        assertEquals(listOf("前方左转"), spoken)
        relay.onCompleted("g1", null); relay.onDrained("g1")
        advance(2_000)
        assertEquals(1, spoken.size)
    }

    @Test
    fun fidelityMismatchIsRespokenAtOnceAndStrikesEndTheAssistant() {
        relay.onGuidanceText("前方左转")
        relay.claimAssistant("g1")
        relay.onCompleted("g1", "前方右转"); relay.onDrained("g1")
        assertEquals(listOf("前方左转"), spoken)
        assertTrue(logs.contains("guidance_fidelity prompt=g1 result=MISMATCH_DIRECTION"))
        relay.onGuidanceText("二百米直行")
        relay.claimAssistant("g2")
        relay.onCompleted("g2", null); relay.onDrained("g2")
        advance(1_500) // UNKNOWN after the grace: a strike, no re-speak
        assertEquals(1, spoken.size)
        relay.onGuidanceText("掉头")
        assertTrue(lastRoute().contains("reason=FIDELITY_STRIKES"))
        assertEquals(2, sent.size)
    }

    @Test
    fun navigationEndClearsStrikesAndCooldown() {
        relay.onGuidanceText("a"); advance(1_200)
        relay.onGuidanceText("b"); advance(1_200)
        relay.onNavigationEnded()
        relay.onGuidanceText("c")
        assertEquals(3, sent.size)
    }

    @Test
    fun toggleOffAlwaysAmapAndNeverSends() {
        enabled = false
        repeat(3) { relay.onGuidanceText("左转$it") }
        assertEquals(3, spoken.size)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun logsCarryNoGuidanceText() {
        relay.onGuidanceText("前方二百米左转进入横琴大道")
        relay.claimAssistant("g1")
        relay.onCompleted("g1", "前方二百米右转"); relay.onDrained("g1")
        relay.onGuidanceText("靠右进入匝道"); advance(2_000)
        assertTrue(logs.isNotEmpty())
        logs.forEach { line ->
            assertFalse(line.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }, line)
        }
    }

    @Test
    fun queuedExpiredPromptWaitsForDrainNotJustCompletion() {
        relay.onGuidanceText("前方左转")
        relay.claimAssistant("g1")
        relay.onGuidanceText("前方右转")
        advance(3_000)
        relay.onCompleted("g1", "前方左转")
        assertTrue(spoken.isEmpty())
        relay.onDrained("g1")
        assertEquals(listOf("前方右转"), spoken)
    }

    @Test
    fun nextAssistantPromptIsSentOnlyAfterDrain() {
        relay.onGuidanceText("前方左转")
        relay.claimAssistant("g1")
        relay.onGuidanceText("前方右转")
        relay.onCompleted("g1", "前方左转")
        assertEquals(1, sent.size)
        relay.onDrained("g1")
        assertEquals("g2", sent.last().first)
    }

    @Test
    fun drainBeforeCompletionAlsoWaitsForCompletion() {
        relay.onGuidanceText("前方左转")
        relay.claimAssistant("g1")
        relay.onGuidanceText("前方右转")
        relay.onDrained("g1")
        assertEquals(1, sent.size)
        relay.onCompleted("g1", "前方左转")
        assertEquals(2, sent.size)
    }

    @Test
    fun lateTranscriptWithinGraceMatchesWithoutStrike() {
        repeat(2) { i ->
            relay.onGuidanceText("前方左转")
            val id = "g${i + 1}"
            relay.claimAssistant(id)
            relay.onCompleted(id, null)
            relay.onDrained(id)
            advance(1_000)
            relay.onLateTranscript(id, "前方左转")
            assertTrue(logs.contains("guidance_fidelity prompt=$id result=MATCH"))
        }
        relay.onGuidanceText("掉头")
        assertEquals(3, sent.size) // no strikes accrued
        assertTrue(spoken.isEmpty())
    }

    @Test
    fun transcriptAfterGraceIsUnknownStrikeWithoutRespeak() {
        relay.onGuidanceText("前方左转")
        relay.claimAssistant("g1")
        relay.onCompleted("g1", null)
        relay.onDrained("g1")
        advance(1_499)
        assertFalse(logs.any { it.startsWith("guidance_fidelity") })
        advance(1)
        assertTrue(logs.contains("guidance_fidelity prompt=g1 result=UNKNOWN"))
        relay.onLateTranscript("g1", "前方左转")
        assertTrue(spoken.isEmpty())
    }

    @Test
    fun strikesAndCooldownDoNotCarryIntoTheNextNavigation() {
        relay.onGuidanceText("左转"); relay.claimAssistant("g1"); relay.onCompleted("g1", "右转"); relay.onDrained("g1")
        relay.onGuidanceText("右转"); relay.claimAssistant("g2"); relay.onCompleted("g2", "左转"); relay.onDrained("g2")
        relay.onGuidanceText("a"); advance(1_200)
        relay.onGuidanceText("b")
        assertTrue(lastRoute().contains("reason=FIDELITY_STRIKES"))
        relay.onNavigationEnded()
        relay.onGuidanceText("c")
        assertEquals("g5", sent.last().first)
    }
}
