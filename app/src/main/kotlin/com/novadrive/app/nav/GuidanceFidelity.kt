package com.novadrive.app.nav

/**
 * SPEC-018 B7: did the assistant say the guidance sentence right? Compares the Amap text with the
 * output transcription on direction words and numbers only (Arabic and Chinese numerals
 * normalised). Pure; never logs text.
 */
object GuidanceFidelity {
    enum class Result { MATCH, MISMATCH_DIRECTION, MISMATCH_NUMBER, UNKNOWN }

    /** Longest first, so 靠左 is one token and not also a 左. */
    private val DIRECTIONS = listOf("掉头", "直行", "靠左", "靠右", "出口", "匝道", "环岛", "左", "右")
    private const val CN_DIGITS = "零一二两三四五六七八九"
    private const val CN_CHARS = CN_DIGITS + "十百千万点"

    fun compare(expected: String, spoken: String?): Result {
        if (spoken.isNullOrBlank()) return Result.UNKNOWN
        val want = directions(expected).groupingBy { it }.eachCount()
        val got = directions(spoken).groupingBy { it }.eachCount()
        if (want.any { (token, n) -> (got[token] ?: 0) < n }) return Result.MISMATCH_DIRECTION
        if (numbers(expected) != numbers(spoken)) return Result.MISMATCH_NUMBER
        return Result.MATCH
    }

    internal fun directions(text: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            val token = DIRECTIONS.firstOrNull { text.startsWith(it, i) }
            if (token != null) {
                out += token
                i += token.length
            } else {
                i++
            }
        }
        return out
    }

    internal fun numbers(text: String): List<Double> {
        val out = mutableListOf<Double>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isDigit() -> {
                    var j = i
                    while (j < text.length && (text[j].isDigit() ||
                            (text[j] == '.' && j + 1 < text.length && text[j + 1].isDigit()))
                    ) j++
                    out += text.substring(i, j).toDouble()
                    i = j
                }
                c in CN_CHARS && c != '点' -> {
                    var j = i
                    while (j < text.length && text[j] in CN_CHARS &&
                        !(text[j] == '点' && (j + 1 >= text.length || text[j + 1] !in CN_DIGITS))
                    ) j++
                    chinese(text.substring(i, j))?.let { out += it }
                    i = j
                }
                else -> i++
            }
        }
        return out
    }

    private fun digit(c: Char): Int = if (c == '两') 2 else CN_DIGITS.indexOf(c).let { if (it > 2) it - 1 else it }

    private fun chinese(run: String): Double? {
        val dot = run.indexOf('点')
        val intPart = if (dot >= 0) run.substring(0, dot) else run
        val frac = if (dot >= 0) run.substring(dot + 1) else ""
        val whole = integer(intPart) ?: return null
        if (frac.isEmpty()) return whole.toDouble()
        val fracDigits = frac.map { digit(it) }
        if (fracDigits.any { it < 0 }) return null
        return "$whole.${fracDigits.joinToString("")}".toDouble()
    }

    private fun integer(run: String): Long? {
        if (run.isEmpty()) return 0
        // 三零五: a plain digit string, read digit by digit.
        if (run.length > 1 && run.all { it in CN_DIGITS }) {
            return run.map { digit(it) }.joinToString("").toLong()
        }
        var total = 0L
        var section = 0L
        var num = 0L
        for (c in run) {
            when (c) {
                '十', '百', '千' -> {
                    val unit = when (c) { '十' -> 10L; '百' -> 100L; else -> 1000L }
                    section += (if (num == 0L) 1L else num) * unit
                    num = 0
                }
                '万' -> {
                    total += (section + num).coerceAtLeast(1) * 10_000
                    section = 0
                    num = 0
                }
                else -> {
                    val d = digit(c)
                    if (d < 0) return null
                    num = d.toLong()
                }
            }
        }
        return total + section + num
    }
}
