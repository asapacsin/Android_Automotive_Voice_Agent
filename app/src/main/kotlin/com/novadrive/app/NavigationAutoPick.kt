package com.novadrive.app

object NavigationAutoPick {
    const val WINDOW_MS = 25_000L

    enum class Stage { PICK_DESTINATION, START_NAVIGATION }

    data class Request(
        val label: String,
        val expiresAtMs: Long,
        val stage: Stage = Stage.PICK_DESTINATION,
        val lastActionMs: Long = 0L,
    )

    @Volatile
    var pending: Request? = null

    fun arm(label: String, nowMs: Long) {
        pending = Request(label.trim(), nowMs + WINDOW_MS)
    }

    fun clear() {
        pending = null
    }

    fun active(nowMs: Long): Request? =
        pending?.takeIf { it.expiresAtMs > nowMs } ?: run {
            pending = null
            null
        }

    /** Index of the list entry to tap, or null. Exact match first, then startsWith, then contains; skip the title "输入终点" and blank texts. */
    fun pickIndex(texts: List<String>, label: String): Int? {
        val needle = label.trim()
        if (needle.isEmpty()) return null
        val usable = texts.mapIndexedNotNull { index, raw ->
            val text = raw.trim()
            if (text.isEmpty() || text == "输入终点") null else index to text
        }
        return usable.firstOrNull { it.second == needle }?.first
            ?: usable.firstOrNull { it.second.startsWith(needle) }?.first
            ?: usable.firstOrNull { it.second.contains(needle) }?.first
    }

    fun isStartButton(text: String): Boolean = text.trim() == "开始导航"

    fun advance(nowMs: Long) {
        pending = pending?.copy(stage = Stage.START_NAVIGATION, lastActionMs = nowMs)
    }

    fun debounced(nowMs: Long, minGapMs: Long = 1_500L): Boolean =
        (pending?.lastActionMs ?: 0L) + minGapMs > nowMs
}
