package com.novadrive.app.ui

import com.novadrive.app.voice.UtteranceIntentResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One control the driver can see and may say (SPEC-010 B1). [id] is opaque and safe to log; the
 * names never are (I-8). [position] is the 1-based row number of a numbered list, for 「第N个」.
 */
data class Affordance(
    val id: String,
    val names: List<String>,
    val position: Int? = null,
)

/** The single owner of "what is on screen" (SPEC-010 B1). Views publish and withdraw by source. */
class ScreenAffordances {
    private val bySource = linkedMapOf<String, List<Affordance>>()
    private val _current = MutableStateFlow<List<Affordance>>(emptyList())
    val current: StateFlow<List<Affordance>> = _current.asStateFlow()

    @Synchronized
    fun publish(source: String, affordances: List<Affordance>) {
        bySource[source] = affordances
        _current.value = bySource.values.flatten()
    }

    @Synchronized
    fun withdraw(source: String) {
        if (bySource.remove(source) != null) _current.value = bySource.values.flatten()
    }
}

/**
 * Whole-utterance matcher (SPEC-010 B2). Pure. A match needs **nothing left over** once one leading
 * verb, the name and politeness particles are removed, so a name inside a longer sentence never
 * steals it from the model.
 */
object AffordanceMatcher {
    sealed interface Result {
        /** [verb] is the verb that was said, if any, so the caller can act by word (暂停 vs 播放). */
        data class Match(val affordance: Affordance, val verb: String?) : Result
        data class Ambiguous(val ids: List<String>) : Result
        data object None : Result
    }

    val VERBS = listOf("打开", "关掉", "关闭", "点", "按", "选")
    private val LEADING = listOf("请", "帮我", "给我")
    private val TRAILING = listOf("一下", "吧", "呀", "啊")
    private val POSITION = Regex("^第([一二三四五六七八九十]|\\d+)个$")
    private val CHINESE_DIGITS = "一二三四五六七八九十"

    fun match(text: String, affordances: List<Affordance>): Result {
        var s = UtteranceIntentResolver.normalizeForHelp(text)
        s = stripLeading(s, LEADING)
        val verb = VERBS.firstOrNull { s.startsWith(it) && s.length > it.length }
        if (verb != null) s = s.removePrefix(verb)
        s = stripTrailing(s)
        if (s.isEmpty()) return Result.None

        val position = POSITION.matchEntire(s)?.groupValues?.get(1)?.let(::number)
        val hits = if (position != null) {
            affordances.filter { it.position == position }
        } else {
            affordances.filter { a -> a.names.any { normalize(it) == s } }
        }
        return when (hits.size) {
            0 -> Result.None
            1 -> Result.Match(hits.single(), verb)
            else -> Result.Ambiguous(hits.map { it.id })
        }
    }

    private fun normalize(name: String) = UtteranceIntentResolver.normalizeForHelp(name)

    private fun stripLeading(text: String, prefixes: List<String>): String {
        var s = text
        var changed = true
        while (changed) {
            changed = false
            prefixes.firstOrNull { s.startsWith(it) && s.length > it.length }?.let { s = s.removePrefix(it); changed = true }
        }
        return s
    }

    private fun stripTrailing(text: String): String {
        var s = text
        var changed = true
        while (changed) {
            changed = false
            TRAILING.firstOrNull { s.endsWith(it) && s.length > it.length }?.let { s = s.removeSuffix(it); changed = true }
        }
        return s
    }

    private fun number(token: String): Int? =
        token.toIntOrNull() ?: CHINESE_DIGITS.indexOf(token).takeIf { it >= 0 }?.plus(1)
}
