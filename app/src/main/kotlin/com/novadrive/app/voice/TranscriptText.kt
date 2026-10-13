package com.novadrive.app.voice

/**
 * The one normaliser for a provider's input transcript (P56).
 *
 * Gemini 3.1 Live has character-spaced its input transcripts since 2026-10-12 (「帮 我 把 天 窗 打 开 。」),
 * which defeats every keyword match downstream. A whitespace run that touches a CJK character or
 * CJK / full-width punctuation is removed; spaces between Latin or digit tokens stay
 * (「放 Taylor Swift 的 歌」 → 「放Taylor Swift的歌」).
 */
object TranscriptText {
    private const val CJK = "\\u3000-\\u303F\\u4E00-\\u9FFF\\uFF00-\\uFFEF"
    private val SPACE_AT_CJK = Regex("(?<=[$CJK])[\\s\\u3000]+|[\\s\\u3000]+(?=[$CJK])")

    fun normalize(text: String): String = SPACE_AT_CJK.replace(text, "").trim()
}
