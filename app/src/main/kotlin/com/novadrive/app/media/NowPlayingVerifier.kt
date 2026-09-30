package com.novadrive.app.media

/** Reads back what is actually playing after a hand-off; the model's guess is never evidence (I-1). */
class NowPlayingVerifier(
    private val source: NowPlayingSource,
    private val clock: () -> Long,
    private val sleep: (Long) -> Unit,
    private val timeoutMs: Long = 6000,
    private val pollMs: Long = 250,
) {
    var lastWaitedMs: Long = 0
        private set

    fun await(request: MusicRequest, previous: NowPlaying?): PlaybackCheck {
        val start = clock()
        lastWaitedMs = 0
        if (!source.hasAccess()) return PlaybackCheck.Unverified
        while (true) {
            val now = source.current()
            lastWaitedMs = clock() - start
            if (now != null && now.playing && !isSameTrack(now, previous)) {
                return PlaybackCheck.Playing(now, MusicMatch.matches(request, now))
            }
            if (lastWaitedMs >= timeoutMs) return PlaybackCheck.NotPlaying
            sleep(pollMs)
        }
    }

    private fun isSameTrack(now: NowPlaying, previous: NowPlaying?): Boolean =
        previous != null && previous.playing &&
            now.title == previous.title && now.packageName == previous.packageName

    companion object {
        fun logLine(check: PlaybackCheck, waitedMs: Long): String {
            val (result, match) = when (check) {
                is PlaybackCheck.Playing -> "playing" to check.matchesRequest
                PlaybackCheck.Unverified -> "unverified" to false
                PlaybackCheck.NotPlaying -> "not_playing" to false
            }
            return "music_verify result=$result match=$match waited_ms=$waitedMs"
        }
    }
}
