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

    private val brackets = mutableListOf<Boolean>()
    private lateinit var relay: GuidanceRelay

    /** Amap's listener reports the end of what it was saying. */
    private fun amapEnds() {
        amapSpeaking = false
        relay.onAmapSpeaking(false)
    }

    private fun build() = GuidanceRelay(
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
            override fun bracket(speaking: Boolean) {
                brackets += speaking
                amapSpeaking = speaking
                if (!speaking) relay.onAmapSpeaking(false)
            }
        },
        enabled = { enabled },
        log = { logs += it },
    )

    init {
        relay = build()
    }

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
            "RESPONSE_OPEN" to { blocker = "RESPONSE_OPEN" },
            "AMAP_SPEAKING" to { amapSpeaking = true },
            "FOCUS" to { focusLoss = true },
            "DISABLED" to { enabled = false },
        )
        for ((reason, setup) in cases) {
            blocker = null; focusLoss = false; enabled = true
            amapEnds()
            setup()
            relay.onGuidanceText("左转")
            assertTrue(lastRoute().contains("to=amap reason=$reason"), reason)
            amapEnds() // G-1b+: a fallback over Amap waits for its end
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
        relay.onGuidanceText("a"); advance(1_200); amapEnds()
        relay.onGuidanceText("b"); advance(1_200); amapEnds()
        assertEquals(2, sent.size)
        relay.onGuidanceText("c")
        assertTrue(lastRoute().contains("reason=COOLDOWN"))
        assertEquals(2, sent.size)
        amapEnds()
        advance(60_000)
        relay.onGuidanceText("d")
        assertEquals(3, sent.size)
    }

    @Test
    fun assistantClaimResetsTheConsecutiveCounter() {
        relay.onGuidanceText("a"); advance(1_200); amapEnds()
        relay.onGuidanceText("b"); relay.claimAssistant("g2"); relay.onCompleted("g2", "b"); relay.onDrained("g2")
        relay.onGuidanceText("c"); advance(1_200); amapEnds()
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
        amapEnds()
        relay.onGuidanceText("二百米直行")
        relay.claimAssistant("g2")
        relay.onCompleted("g2", null); relay.onDrained("g2") // UNKNOWN: a strike, no re-speak
        assertEquals(1, spoken.size)
        relay.onGuidanceText("掉头")
        assertTrue(lastRoute().contains("reason=FIDELITY_STRIKES"))
        assertEquals(2, sent.size)
    }

    @Test
    fun navigationEndClearsStrikesAndCooldown() {
        relay.onGuidanceText("a"); advance(1_200); amapEnds()
        relay.onGuidanceText("b"); advance(1_200); amapEnds()
        relay.onNavigationEnded()
        relay.onGuidanceText("c")
        assertEquals(3, sent.size)
    }

    @Test
    fun toggleOffAlwaysAmapAndNeverSends() {
        enabled = false
        repeat(3) { relay.onGuidanceText("左转$it") }
        assertEquals(1, spoken.size) // one at a time: never Amap over itself
        amapEnds(); amapEnds()
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
    fun strikesAndCooldownDoNotCarryIntoTheNextNavigation() {
        relay.onGuidanceText("左转"); relay.claimAssistant("g1"); relay.onCompleted("g1", "右转"); relay.onDrained("g1"); amapEnds()
        relay.onGuidanceText("右转"); relay.claimAssistant("g2"); relay.onCompleted("g2", "左转"); relay.onDrained("g2"); amapEnds()
        relay.onGuidanceText("a"); advance(1_200); amapEnds()
        relay.onGuidanceText("b")
        assertTrue(lastRoute().contains("reason=FIDELITY_STRIKES"))
        amapSpeaking = false // the host's stop funnel ends Amap's voice
        relay.onNavigationEnded()
        relay.onGuidanceText("c")
        assertEquals("g5", sent.last().first)
    }

    // ---- revision 3: R1, typed transcript, G-1b+ ----------------------------------------------

    @Test
    fun claimDrainThenVoidedStillLetsTheNextPromptRoute() {
        relay.onGuidanceText("前方左转")
        relay.claimAssistant("g1")
        relay.onDrained("g1") // drained, COMPLETED never came
        relay.onVoided("g1")
        assertEquals(listOf("前方左转"), spoken) // B2a
        amapEnds()
        relay.onGuidanceText("前方右转")
        assertEquals("g2", sent.last().first)
    }

    @Test
    fun claimDrainThenSessionStopStillLetsTheNextPromptRoute() {
        relay.onGuidanceText("前方左转")
        relay.claimAssistant("g1")
        relay.onDrained("g1")
        relay.onSessionStopped()
        assertEquals(listOf("前方左转"), spoken)
        assertTrue(lastRoute().contains("prompt=g1 to=amap reason=SESSION_STOPPED"))
        amapEnds()
        relay.onGuidanceText("前方右转")
        assertEquals("g2", sent.last().first)
    }

    @Test
    fun pendingThenVoidedGoesToAmapNowAndAbandons() {
        relay.onGuidanceText("前方左转")
        advance(300)
        relay.onVoided("g1")
        assertEquals(listOf("g1"), abandoned)
        assertEquals(listOf("前方左转"), spoken)
        assertTrue(lastRoute().contains("reason=VOIDED waited_ms=300"))
        advance(5_000) // the cancelled deadline does not speak it twice
        assertEquals(1, spoken.size)
        assertFalse(relay.claimAssistant("g1"))
    }

    @Test
    fun pendingThenSessionStoppedGoesToAmapAndTheQueueMovesOn() {
        relay.onGuidanceText("前方左转")
        relay.onGuidanceText("前方右转")
        blocker = "NOT_CONNECTED"
        relay.onSessionStopped()
        assertEquals(listOf("g1"), abandoned)
        assertEquals(listOf("前方左转"), spoken)
        amapEnds()
        assertEquals(listOf("前方左转", "前方右转"), spoken)
    }

    @Test
    fun heldChunkNeverClaimedHandsThePromptToAmapAtTheDeadline() {
        // R8a: the port claims only at playout; a HOLD-paused chunk makes no claim.
        relay.onGuidanceText("前方左转")
        advance(1_200)
        assertEquals(listOf("前方左转"), spoken)
        assertEquals(listOf("g1"), abandoned)
        assertFalse(relay.claimAssistant("g1")) // the port flushes what it held
    }

    @Test
    fun fidelityIsJudgedFromTheTypedTranscriptAtCompletedAfterDrain() {
        GuidanceRelay.install(relay)
        try {
            relay.onGuidanceText("前方二百米左转")
            relay.claimAssistant("g1")
            val opened = com.novadrive.ingress.realtime.DomainVoiceEvent.AppPromptTurn.Phase.OPENED
            val completed = com.novadrive.ingress.realtime.DomainVoiceEvent.AppPromptTurn.Phase.COMPLETED
            GuidanceTranscripts.onAppPromptTurn("g1", opened)
            GuidanceTranscripts.onAppPromptTranscript("g1", "前方二百米")
            relay.onDrained("g1")
            assertFalse(logs.any { it.startsWith("guidance_fidelity") })
            GuidanceTranscripts.onAppPromptTranscript("g1", "右转")
            GuidanceTranscripts.onAppPromptTurn("g1", completed)
            assertTrue(logs.contains("guidance_fidelity prompt=g1 result=MISMATCH_DIRECTION"))
            assertEquals(listOf("前方二百米左转"), spoken)
        } finally {
            GuidanceRelay.uninstall(relay)
        }
    }

    @Test
    fun fidelityAtDrainWhenCompletedCameFirst() {
        relay.onGuidanceText("前方左转")
        relay.claimAssistant("g1")
        relay.onCompleted("g1", "前方左转")
        assertFalse(logs.any { it.startsWith("guidance_fidelity") })
        relay.onDrained("g1")
        assertTrue(logs.contains("guidance_fidelity prompt=g1 result=MATCH"))
    }

    @Test
    fun voidedThroughTheTranscriptCollectorReachesTheRelay() {
        GuidanceRelay.install(relay)
        try {
            relay.onGuidanceText("前方左转")
            GuidanceTranscripts.onAppPromptTurn("g1", com.novadrive.ingress.realtime.DomainVoiceEvent.AppPromptTurn.Phase.VOIDED)
            assertEquals(listOf("前方左转"), spoken)
        } finally {
            GuidanceRelay.uninstall(relay)
        }
    }

    @Test
    fun relayNeverCallsPlayTtsWhileAmapSpeaks() {
        amapSpeaking = true // Amap is speaking something
        relay.onGuidanceText("前方左转")
        assertTrue(lastRoute().contains("reason=AMAP_SPEAKING"))
        assertTrue(spoken.isEmpty())
        amapEnds()
        assertEquals(listOf("前方左转"), spoken)
        assertEquals(listOf(true), brackets)
    }

    @Test
    fun ownBracketEndsOnTheListenerAndBlocksPromptsWhileOpen() {
        sendAccepted = false
        relay.onGuidanceText("左转") // SEND_FAILED → Amap, bracket opens
        sendAccepted = true
        relay.onGuidanceText("右转")
        assertTrue(lastRoute().contains("reason=AMAP_SPEAKING"))
        assertEquals(1, sent.size)
        assertEquals(listOf("左转"), spoken)
        amapEnds()
        assertEquals(listOf("左转", "右转"), spoken)
    }

    @Test
    fun ownBracketEndsByTheLengthEstimateWhenNoListenerEndArrives() {
        sendAccepted = false
        relay.onGuidanceText("前方左转") // 4 chars → estimate max(1500, 1200) = 1500 ms
        assertEquals(listOf(true), brackets)
        advance(1_499)
        assertEquals(listOf(true), brackets)
        advance(1)
        assertEquals(listOf(true, false), brackets)
        assertFalse(amapSpeaking)
        relay.onGuidanceText("前".repeat(10)) // 3000 ms
        advance(2_999)
        assertEquals(3, brackets.size)
        advance(1)
        assertEquals(4, brackets.size)
    }

    @Test
    fun completedWhilePendingIsKeptAndSettlesAfterClaimAndDrain() {
        relay.onGuidanceText("前方左转")
        relay.onCompleted("g1", "前方左转")
        assertTrue(relay.claimAssistant("g1"))
        relay.onDrained("g1")
        assertTrue(logs.contains("guidance_fidelity prompt=g1 result=MATCH"))
        relay.onGuidanceText("再右转")
        assertEquals("g2", sent.last().first)
        assertTrue(spoken.isEmpty())
    }

    @Test
    fun completedWhilePendingStillFallsBackAtTheDeadline() {
        relay.onGuidanceText("前方左转")
        relay.onCompleted("g1", "前方左转")
        advance(1_200)
        assertEquals(listOf("前方左转"), spoken)
        assertFalse(relay.claimAssistant("g1"))
        amapEnds()
        relay.onGuidanceText("再右转")
        assertEquals("g2", sent.last().first)
        assertEquals(1, spoken.size)
    }
}
