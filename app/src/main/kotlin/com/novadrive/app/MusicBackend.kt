package com.novadrive.app

import android.content.Context

/** What the music tool drives. Production: the bundled track; tests: a simulated player. */
interface MusicBackend {
    fun play(): Boolean
    fun stop()
    val isPlaying: Boolean
}

private class BundledMusicBackend(private val context: Context) : MusicBackend {
    override fun play(): Boolean = BundledMusicPlayer.play(context)
    override fun stop() = BundledMusicPlayer.stop()
    override val isPlaying: Boolean get() = BundledMusicPlayer.isPlaying
}

object MusicBackends {
    /** Set by the benchmark runner (debug builds) to a simulated player; null in normal use. */
    @Volatile var override: MusicBackend? = null

    fun current(context: Context): MusicBackend = override ?: BundledMusicBackend(context)
}
