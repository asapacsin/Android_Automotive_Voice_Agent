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

    /** Blocking; call off the event loop. */
    fun await(request: MusicRequest, previous: NowPlaying?): PlaybackCheck

    /** How long the last [await] waited, for the code-only log line. */
    val lastWaitedMs: Long
}

/** Composes [AndroidMediaHandoff], [AndroidNowPlayingSource] and [NowPlayingVerifier]. */
class AndroidMusicHandoffTool(context: Context) : MusicHandoffTool {
    private val appContext = context.applicationContext
    private val source = AndroidNowPlayingSource(appContext)
    private val handoff = AndroidMediaHandoff(appContext)
    private val verifier = NowPlayingVerifier(source, SystemClock::elapsedRealtime, Thread::sleep)

    override fun snapshot(): NowPlaying? = if (source.hasAccess()) source.current() else null

    override fun handOff(request: MusicRequest): HandoffResult = handoff.play(request)

    override fun await(request: MusicRequest, previous: NowPlaying?): PlaybackCheck =
        verifier.await(request, previous)

    override val lastWaitedMs: Long get() = verifier.lastWaitedMs
}
