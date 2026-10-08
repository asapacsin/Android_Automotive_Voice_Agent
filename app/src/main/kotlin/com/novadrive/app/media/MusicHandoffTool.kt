package com.novadrive.app.media

import android.content.Context
import android.os.SystemClock

/**
 * What `play_music` needs from the device (SPEC-017): what is playing now, the hand-off, and the
 * readback. An interface so the tool server is testable without Android.
 */
interface MusicHandoffTool {
    /** What is playing before the hand-off, or null (nothing, or no listener access). */
    fun snapshot(): NowPlaying?

    fun handOff(request: MusicRequest): HandoffResult

    /** Polls the session source; the caller runs it on an IO dispatcher. Cancellable. */
    suspend fun await(request: MusicRequest, previous: NowPlaying?): PlaybackCheck

    /** Pauses the playing session(s) of any app; see [PauseResult]. */
    fun pauseActive(): PauseResult

    /** After [pauseActive]: true when the readback shows nothing playing within a short wait. */
    suspend fun awaitStopped(): Boolean

    /** A hand-off was sent in this process, so another app may be playing. */
    val handedOff: Boolean

    /** How long the last [await] waited, for the code-only log line. */
    val lastWaitedMs: Long
}

sealed interface PauseResult {
    data object NoAccess : PauseResult
    data object NothingPlaying : PauseResult
    data object PauseSent : PauseResult
}

/** Composes [AndroidMediaHandoff], [AndroidNowPlayingSource] and [NowPlayingVerifier]. */
class AndroidMusicHandoffTool(context: Context) : MusicHandoffTool {
    private val appContext = context.applicationContext
    private val source = AndroidNowPlayingSource(appContext)
    private val handoff = AndroidMediaHandoff(appContext)
    private val verifier = NowPlayingVerifier(source, SystemClock::elapsedRealtime)

    @Volatile
    override var handedOff: Boolean = false
        private set

    override fun snapshot(): NowPlaying? = if (source.hasAccess()) source.current() else null

    override fun handOff(request: MusicRequest): HandoffResult =
        handoff.play(request).also { if (it is HandoffResult.Sent) handedOff = true }

    override suspend fun await(request: MusicRequest, previous: NowPlaying?): PlaybackCheck =
        verifier.await(request, previous)

    override fun pauseActive(): PauseResult = when (source.pausePlaying()) {
        null -> PauseResult.NoAccess
        false -> PauseResult.NothingPlaying
        true -> PauseResult.PauseSent
    }

    override suspend fun awaitStopped(): Boolean {
        repeat(STOP_POLLS) {
            if (source.current() == null) return true
            kotlinx.coroutines.delay(STOP_POLL_MS)
        }
        return source.current() == null
    }

    private companion object {
        const val STOP_POLLS = 6
        const val STOP_POLL_MS = 250L
    }

    override val lastWaitedMs: Long get() = verifier.lastWaitedMs
}
