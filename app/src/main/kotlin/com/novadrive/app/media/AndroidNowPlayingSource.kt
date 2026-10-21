package com.novadrive.app.media

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState

class AndroidNowPlayingSource(private val context: Context) : NowPlayingSource {
    private fun controllers(): List<MediaController>? {
        val manager = context.getSystemService(MediaSessionManager::class.java) ?: return null
        return try {
            manager.getActiveSessions(ComponentName(context, NovaMediaListener::class.java))
        } catch (_: SecurityException) {
            null
        }
    }

    override fun hasAccess(): Boolean = controllers() != null

    override fun current(): NowPlaying? {
        val c = controllers()?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: return null
        val md = c.metadata
        return NowPlaying(
            title = md?.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST),
            playing = true,
            packageName = c.packageName,
        )
    }

    /** Pauses every playing session: null without listener access, else whether one was playing. */
    fun pausePlaying(): Boolean? {
        val playing = controllers()?.filter { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: return null
        playing.forEach { it.transportControls.pause() }
        return playing.isNotEmpty()
    }
}
