package com.novadrive.app.voice

import android.media.AudioManager
import com.novadrive.app.NavigationState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * SPEC-012 A1: the rules for who may speak, R1–R9. Written in step 1 against the owners of the day
 * (`NavigationState` window, `GuidanceMicGate`, focus mapping); since step 3 the first mode drives
 * the wired production path instead, with the table itself unchanged. Every combination of the
 * inputs is checked, so a rewrite that changes any one answer
 * fails here.
 *
 * Reply decisions are for a new chunk of reply audio arriving in that state; volume is the answer
 * to a duck request in that state; uplink is whether the microphone may reach Baidu.
 */
class SpeechRulesCharacterizationTest {

    enum class Guidance { NONE, SPEAKING, SPEAKING_TOO_LONG, JUST_ENDED, ENDED }
    enum class Focus { HELD, DUCK, TRANSIENT_LOSS, PERMANENT_LOSS }
    enum class Reply { PLAY, HOLD, DROP }
    enum class Volume { FULL, DUCK }
    enum class Uplink { OPEN, CLOSED }

    data class State(val navigating: Boolean, val windowOpen: Boolean, val guidance: Guidance, val focus: Focus)
    data class Answer(val reply: Reply, val volume: Volume, val uplink: Uplink)

    /** A way of answering the three questions; one per implementation under test. */
    fun interface Rules {
        fun answer(state: State): Answer
    }

    @AfterEach
    fun clear() = NavigationState.reset()

    @ParameterizedTest
    @MethodSource("states")
    fun wiredPathFollowsTheTable(state: State) {
        assertEquals(expected(state), Wired.answer(state)) { "$state" }
    }

    /** SPEC-012 A2: the arbiter gives the same answer in every state. */
    @ParameterizedTest
    @MethodSource("states")
    fun arbiterFollowsTheSameTable(state: State) {
        assertEquals(expected(state), Arbiter.answer(state)) { "$state" }
    }

    companion object {
        @JvmStatic
        fun states(): List<State> = buildList {
            for (navigating in listOf(false, true)) for (window in listOf(false, true))
                for (g in Guidance.entries) for (f in Focus.entries) add(State(navigating, window, g, f))
        }

        /**
         * The table, as today's code behaves. Order matters and differs from the SPEC's first draft
         * in one place, found by writing this: a permanent focus loss and the P1 mute both *drop*
         * new audio even while guidance is speaking, because both act before the guidance pause.
         */
        fun expected(s: State): Answer {
            val muted = s.navigating && !s.windowOpen
            val reply = when {
                s.focus == Focus.PERMANENT_LOSS -> Reply.DROP // R4
                muted -> Reply.DROP // R6 (P1)
                s.guidance == Guidance.SPEAKING || s.guidance == Guidance.SPEAKING_TOO_LONG -> Reply.HOLD // R1
                s.focus == Focus.TRANSIENT_LOSS -> Reply.HOLD // R5
                else -> Reply.PLAY // R7–R9
            }
            // R7: a permitted reply while navigating ignores a duck request; R8 otherwise ducks.
            val volume = if (s.focus == Focus.DUCK && !(s.navigating && s.windowOpen)) Volume.DUCK else Volume.FULL
            val uplink = when (s.guidance) {
                Guidance.SPEAKING, Guidance.JUST_ENDED -> Uplink.CLOSED // R1, R2
                else -> Uplink.OPEN // R3 (lost end callback), R9
            }
            return Answer(reply, volume, uplink)
        }

        /**
         * The wired path (SPEC-012 step 3): the old owners are gone, so the first mode now drives the
         * production seams — `NavigationState` for navigating, `AndroidPlaybackPort.applyFocusChange`
         * for focus, the process arbiter in [SpeechAuthority] for the window, guidance and uplink —
         * and reads the answers the player, focus path and microphone act on.
         */
        val Wired = Rules { s ->
            var now = 1_000_000L
            SpeechAuthority.resetForTest { now }
            NavigationState.reset()
            if (s.navigating) NavigationState.begin()
            if (s.windowOpen) {
                now -= 1_000
                SpeechAuthority.arbiter.onDriverRequest()
                now += 1_000
            }
            val (started, ended) = guidanceTimes(s.guidance)
            if (started != null) {
                val end = now
                now = end - started
                SpeechAuthority.arbiter.onGuidanceSpeaking(true)
                if (ended != null) SpeechAuthority.arbiter.onGuidanceSpeaking(false)
                now = end
            }
            val player = PcmAudioPlayer { }
            val port = AndroidPlaybackPort(player) { true }
            val ducksBefore = player.duckCount
            port.applyFocusChange(
                when (s.focus) {
                    Focus.HELD -> AudioManager.AUDIOFOCUS_GAIN
                    Focus.DUCK -> AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
                    Focus.TRANSIENT_LOSS -> AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                    Focus.PERMANENT_LOSS -> AudioManager.AUDIOFOCUS_LOSS
                },
            )
            val ducked = player.duckCount > ducksBefore
            val answer = Answer(
                Reply.valueOf(SpeechAuthority.reply().name),
                if (ducked) Volume.DUCK else Volume.FULL,
                if (SpeechAuthority.uplinkClosed()) Uplink.CLOSED else Uplink.OPEN,
            )
            NavigationState.reset()
            SpeechAuthority.resetForTest()
            answer
        }

        /** Guidance start and end, as ms before the moment asked about. */
        fun guidanceTimes(g: Guidance): Pair<Long?, Long?> = when (g) {
            Guidance.NONE -> null to null
            Guidance.SPEAKING -> 1_000L to null
            Guidance.SPEAKING_TOO_LONG -> 20_001L to null
            Guidance.JUST_ENDED -> 200L to 200L
            Guidance.ENDED -> 600L to 600L
        }

        val Arbiter = Rules { s ->
            var now = 1_000_000L
            val arbiter = SpeechArbiter(clock = { now })
            arbiter.onNavigating(s.navigating)
            if (s.windowOpen) {
                now -= 1_000
                arbiter.onDriverRequest()
                now += 1_000
            }
            arbiter.onFocus(
                when (s.focus) {
                    Focus.HELD -> SpeechArbiter.Focus.HELD
                    Focus.DUCK -> SpeechArbiter.Focus.DUCK
                    Focus.TRANSIENT_LOSS -> SpeechArbiter.Focus.TRANSIENT_LOSS
                    Focus.PERMANENT_LOSS -> SpeechArbiter.Focus.PERMANENT_LOSS
                },
            )
            // Guidance happens "before now", then the clock reaches the moment asked about.
            val (started, ended) = guidanceTimes(s.guidance)
            if (started != null) {
                val end = now
                now = end - started
                arbiter.onGuidanceSpeaking(true)
                if (ended != null) arbiter.onGuidanceSpeaking(false)
                now = end
            }
            Answer(
                Reply.valueOf(arbiter.reply().name),
                Volume.valueOf(arbiter.volume().name),
                Uplink.valueOf(arbiter.uplink().name),
            )
        }
    }
}
