package com.novadrive.app.media

/** What the model asked for (SPEC-017). Pure; no android.* imports. */
data class MusicRequest(
    val title: String?,
    val artist: String?,
    val albumOrWork: String?,
    val mood: String?,
    val query: String?,
    val excludeTitle: String? = null,
) {
    val isEmpty: Boolean
        get() = listOf(title, artist, albumOrWork, mood, query).all { it.isNullOrBlank() }

    /** Field-presence flags for logs (I-8: never the values). */
    fun fieldFlags(): String = buildString {
        if (!title.isNullOrBlank()) append('t')
        if (!artist.isNullOrBlank()) append('a')
        if (!albumOrWork.isNullOrBlank()) append('w')
        if (!mood.isNullOrBlank()) append('m')
        if (!query.isNullOrBlank()) append('q')
    }.ifEmpty { "-" }
}

data class MediaSearchSpec(val query: String, val focus: String?, val extras: Map<String, String>)

object MediaSearchSpecs {
    const val EXTRA_FOCUS = "android.intent.extra.focus"
    const val EXTRA_TITLE = "android.intent.extra.title"
    const val EXTRA_ARTIST = "android.intent.extra.artist"
    const val EXTRA_ALBUM = "android.intent.extra.album"
    const val EXTRA_QUERY = "query"
    const val FOCUS_AUDIO = "vnd.android.cursor.item/audio"
    const val FOCUS_ARTIST = "vnd.android.cursor.item/artist"
    const val FOCUS_ALBUM = "vnd.android.cursor.item/album"
    const val MAX_QUERY = 80

    fun build(request: MusicRequest): MediaSearchSpec {
        val title = request.title.clean()
        val artist = request.artist.clean()
        val work = request.albumOrWork.clean()
        val focus = when {
            title != null -> FOCUS_AUDIO
            artist != null && work == null -> FOCUS_ARTIST
            work != null && artist == null -> FOCUS_ALBUM
            else -> null
        }
        val text = when {
            title != null -> join(title, artist)
            work != null -> join(work, artist)
            request.query.clean() != null -> request.query.clean()!!
            artist != null -> artist
            else -> request.mood.clean().orEmpty()
        }.trim().take(MAX_QUERY).trim()
        val extras = linkedMapOf(EXTRA_QUERY to text)
        if (focus != null) extras[EXTRA_FOCUS] = focus
        title?.let { extras[EXTRA_TITLE] = it }
        artist?.let { extras[EXTRA_ARTIST] = it }
        work?.let { extras[EXTRA_ALBUM] = it }
        return MediaSearchSpec(text, focus, extras)
    }

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
    private fun join(a: String, b: String?) = if (b == null) a else "$a $b"
}

object MediaAppPreference {
    val DEFAULT = listOf("com.netease.cloudmusic", "com.tencent.qqmusic", "org.videolan.vlc")

    fun pick(installedResolvers: List<String>, preference: List<String> = DEFAULT): String? =
        preference.firstOrNull { it in installedResolvers } ?: installedResolvers.firstOrNull()
}

data class NowPlaying(val title: String?, val artist: String?, val playing: Boolean, val packageName: String?)

interface NowPlayingSource {
    fun hasAccess(): Boolean
    fun current(): NowPlaying?
}

sealed interface PlaybackCheck {
    data class Playing(val nowPlaying: NowPlaying, val matchesRequest: Boolean) : PlaybackCheck
    data object Unverified : PlaybackCheck
    data object NotPlaying : PlaybackCheck
}
