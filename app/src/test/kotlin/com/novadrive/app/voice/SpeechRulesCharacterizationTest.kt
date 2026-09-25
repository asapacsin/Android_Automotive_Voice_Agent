package com.novadrive.app.voice

import android.media.AudioManager
import com.novadrive.app.NavigationState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * SPEC-012 A1: the rules for who may speak, R1–R9, written against **today's** owners before any of
 * them move. Every combination of the inputs is checked, so a rewrite that changes any one answer
 * fails here.
 *
 * Reply decisions are for a new chunk of reply audio arriving in that state; volume is the answer
 * to a duck request in that state; uplink is whether the microphone may reach Baidu.
 */
@OptIn(ExperimentalCoroutinesApi::class)
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
    fun currentClassesFollowTheTable(state: State) {
        assertEquals(expected(state), Legacy.answer(state)) { "$state" }
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

        /** Today's owners, each asked its own question; the glue mirrors their call sites. */
        val Legacy = Rules { s ->
            val now = 1_000_000L
            NavigationState.reset()
            if (s.navigating) NavigationState.begin()
            if (s.windowOpen) NavigationState.allowReply(now - 1_000)

            // AndroidPlaybackPort.enqueue: P1 mute first; applyFocusChange: STOP flushes, PAUSE and
            // the guidanceListener pause; DUCK is ignored inside the permitted window.
            val action = focusAction(
                when (s.focus) {
                    Focus.HELD -> AudioManager.AUDIOFOCUS_GAIN
                    Focus.DUCK -> AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
                    Focus.TRANSIENT_LOSS -> AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                    Focus.PERMANENT_LOSS -> AudioManager.AUDIOFOCUS_LOSS
                },
            )
            val guidancePaused = s.guidance == Guidance.SPEAKING || s.guidance == Guidance.SPEAKING_TOO_LONG
            val reply = when {
                action == FocusAction.STOP -> Reply.DROP
                NavigationState.shouldMuteSpeech(now) -> Reply.DROP
                guidancePaused || action == FocusAction.PAUSE -> Reply.HOLD
                else -> Reply.PLAY
            }
            val ducks = action == FocusAction.DUCK &&
                !(NavigationState.navigating && !NavigationState.shouldMuteSpeech(now))
            Answer(reply, if (ducks) Volume.DUCK else Volume.FULL, legacyUplink(s.guidance))
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
            val (started, ended) = when (s.guidance) {
                Guidance.NONE -> null to null
                Guidance.SPEAKING -> 1_000L to null
                Guidance.SPEAKING_TOO_LONG -> 20_001L to null
                Guidance.JUST_ENDED -> 200L to 200L
                Guidance.ENDED -> 600L to 600L
            }
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

        private fun legacyUplink(guidance: Guidance): Uplink {
            val scope = TestScope()
            val gate = GuidanceMicGate(scope, {})
            when (guidance) {
                Guidance.NONE -> Unit
                Guidance.SPEAKING -> { gate.onGuidanceSpeaking(true); scope.advanceTimeBy(1_000) }
                Guidance.SPEAKING_TOO_LONG -> { gate.onGuidanceSpeaking(true); scope.advanceTimeBy(20_001) }
                Guidance.JUST_ENDED -> {
                    gate.onGuidanceSpeaking(true); gate.onGuidanceSpeaking(false); scope.advanceTimeBy(200)
                }
                Guidance.ENDED -> {
                    gate.onGuidanceSpeaking(true); gate.onGuidanceSpeaking(false); scope.advanceTimeBy(600)
                }
            }
            scope.runCurrent()
            val closed = gate.closed
            gate.reset()
            return if (closed) Uplink.CLOSED else Uplink.OPEN
        }
    }
}
