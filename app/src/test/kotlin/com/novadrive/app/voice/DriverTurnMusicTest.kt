package com.novadrive.app.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * SPEC-017 / I-1: a play_music result proves only what its status supports. After an unconfirmed
 * hand-off (requested_unverified) or a failure, 「正在放《X》」 is never heard.
 */
class DriverTurnMusicTest {
    private val audio = SpeechUplinkGate.Segment(durationMs = 1_500, voicedFrames = 14, peak = 9_000)

    /** The call response, its result, then the reply response. */
    private fun replyAfter(ok: Boolean, confirmed: Boolean, reply: String, failure: String? = null): DriverTurn.Verdict {
        val t = DriverTurn(epoch = 1)
        t.onUserTranscript("放梶浦由记的、空之境界里很燃的那首") { DriverTurn.Kind.ACTION }
        t.onResponseStarted(audio, false)
        t.onResponseDone("", hadToolCallInResponse = true)
        t.onExecutionResult(ok = ok, failure = failure, musicConfirmed = confirmed)
        val hold = t.onResponseStarted(audio, false)
        assertTrue(hold != DriverTurn.HoldReason.NONE || confirmed, "an unconfirmed hand-off keeps the reply held")
        t.onAssistantText(reply)
        return t.onResponseDone(reply, hadToolCallInResponse = false)
    }

    @Test
    fun unverifiedHandOffDropsAPlayingClaim() {
        val v = replyAfter(ok = true, confirmed = false, reply = "正在放梶浦由记的《oblivious》")
        assertTrue(v is DriverTurn.Verdict.Drop, "$v")
        assertEquals(ActionClaimGuard.MUSIC_NOT_CONFIRMED, (v as DriverTurn.Verdict.Drop).correction)
    }

    @Test
    fun unverifiedHandOffReleasesTheHonestLine() {
        assertTrue(replyAfter(ok = true, confirmed = false, reply = "已经让音乐 app 去找了") is DriverTurn.Verdict.Release)
    }

    @Test
    fun failedHandOffDropsAPlayingClaim() {
        val v = replyAfter(ok = false, confirmed = false, reply = "正在放梶浦由记的《oblivious》", failure = "NOT_PLAYING")
        assertTrue(v is DriverTurn.Verdict.Drop, "$v")
    }

    @Test
    fun failedHandOffReleasesAnHonestRefusal() {
        assertTrue(replyAfter(ok = false, confirmed = false, reply = "这首没放成，可能需要会员。", failure = "NOT_PLAYING") is DriverTurn.Verdict.Release)
    }

    @Test
    fun confirmedPlaybackReleasesNormally() {
        val t = DriverTurn(epoch = 1)
        t.onUserTranscript("放梶浦由记的歌") { DriverTurn.Kind.ACTION }
        t.onResponseStarted(audio, false)
        t.onResponseDone("", hadToolCallInResponse = true)
        t.onExecutionResult(ok = true, failure = null, musicConfirmed = true)
        assertEquals(DriverTurn.HoldReason.NONE, t.onResponseStarted(audio, false))
        assertTrue(t.onResponseDone("在放Kalafina的《oblivious》", hadToolCallInResponse = false) is DriverTurn.Verdict.Release)
    }

    // ---- Gemini shape: the reply comes in the same response as the call ----------------------

    private fun geminiReply(reply: String): DriverTurn.Verdict {
        val t = DriverTurn(epoch = 1)
        t.onUserTranscript("放梶浦由记的歌") { DriverTurn.Kind.ACTION }
        t.onResponseStarted(audio, false)
        t.onToolCall("m1", "play_music")
        t.onExecutionResult(ok = true, failure = null, callId = "m1", musicConfirmed = false)
        t.onAssistantText(reply)
        return t.onResponseDone(reply, hadToolCallInResponse = true)
    }

    @Test
    fun geminiSameResponsePlayingClaimIsDropped() {
        val v = geminiReply("正在放《X》")
        assertTrue(v is DriverTurn.Verdict.Drop, "$v")
        assertEquals(ActionClaimGuard.MUSIC_NOT_CONFIRMED, (v as DriverTurn.Verdict.Drop).correction)
    }

    @Test
    fun geminiSameResponseHonestLineIsReleased() {
        assertTrue(geminiReply("已经让音乐 app 去找了") is DriverTurn.Verdict.Release)
    }
}
