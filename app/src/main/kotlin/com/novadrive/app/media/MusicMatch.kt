package com.novadrive.app.media

import java.text.Normalizer

object MusicMatch {
    fun normalise(s: String?): String {
        if (s == null) return ""
        val n = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase()
        return n.filter { c ->
            !c.isWhitespace() && !isPunct(c)
        }
    }

    private fun isPunct(c: Char): Boolean {
        if (c in "《》「」『』·・、，。！？：；（）【】〈〉\"'`~!?.,:;()[]{}<>-_/\\|&+*#@%^=") return true
        return when (Character.getType(c).toByte()) {
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
            Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
            Character.OTHER_PUNCTUATION -> true
            else -> false
        }
    }

    private fun containsEither(a: String, b: String) = a.isNotEmpty() && b.isNotEmpty() && (a.contains(b) || b.contains(a))

    fun matches(request: MusicRequest, nowPlaying: NowPlaying): Boolean {
        val playingTitle = normalise(nowPlaying.title)
        val exclude = normalise(request.excludeTitle)
        if (exclude.isNotEmpty() && containsEither(exclude, playingTitle)) return false
        if (containsEither(normalise(request.title), playingTitle)) return true
        return containsEither(normalise(request.artist), normalise(nowPlaying.artist))
    }
}
