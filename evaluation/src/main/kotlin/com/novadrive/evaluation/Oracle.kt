package com.novadrive.evaluation

/** A tool call as it actually happened, reconstructed from telemetry. */
data class ObservedCall(
    val name: String,
    val args: Map<String, String>,
    val receivedNanos: Long,
    val executionStartNanos: Long? = null,
    val executionEndNanos: Long? = null,
    val success: Boolean? = null,
    val errorCode: String? = null,
)

data class ObservedReply(val text: String, val atNanos: Long)

data class TurnVerdict(
    val toolSelectionCorrect: Boolean,
    val parametersCorrect: Boolean,
    val executionSucceeded: Boolean?,
    val finalStateCorrect: Boolean?,
    val falseSuccess: Boolean,
    /** A success claim spoken before the action had executed (later made true, e.g. by the guard). */
    val prematureClaim: Boolean,
    val unexpectedToolCalls: Int,
    val duplicateExecutions: Int,
    val replied: Boolean,
    val failures: List<String>,
) {
    val passed: Boolean get() = failures.isEmpty()
}

/**
 * Judges one turn from what actually happened. It checks STATE, not wording: a reply saying
 * 「已调到24度」 proves nothing; hvac.temperature == 24.0 does.
 */
object Oracle {
    fun judge(
        expect: TurnExpectation,
        calls: List<ObservedCall>,
        state: Map<String, String>,
        replies: List<ObservedReply>,
        timedOut: Boolean = false,
        requireReply: Boolean = true,
    ): TurnVerdict {
        val failures = mutableListOf<String>()
        if (timedOut) failures += "turn did not settle before the timeout"

        // Duplicates: the same call executed again (not a retry after a refused/failed one).
        val distinct = mutableListOf<ObservedCall>()
        var duplicates = 0
        for (call in calls) {
            val twin = distinct.lastOrNull { it.name == call.name && it.args == call.args && it.success == true }
            if (twin != null && call.success == true) duplicates++ else distinct += call
        }
        if (duplicates > 0) failures += "duplicate execution ×$duplicates"

        val considered = distinct.filter { c -> c.name !in expect.tolerated && "${c.name}:${c.args["action"]}" !in expect.tolerated }
        val sequences = listOf(expect.tools) + expect.alternatives
        // Among the sequences that match, prefer one whose arguments match, then the one that
        // explains the most calls (「打开空调并调到24度」 is power_on + set, not set + noise).
        val bySelection = sequences.filter { seq -> namesMatch(seq, considered) }
            .sortedWith(compareBy({ !argsMatch(it, considered) }, { unmatchedCount(it, considered) }))
            .firstOrNull()
        val selectionCorrect = bySelection != null
        val paramsCorrect = bySelection != null && argsMatch(bySelection, considered)
        val bestSequence = bySelection ?: sequences.maxByOrNull { seq -> seq.count { s -> considered.any { it.name == s.name } } } ?: emptyList()
        val unexpected = unmatchedCount(bestSequence, considered)
        if (!selectionCorrect) {
            failures += "tool selection: expected ${sequences.joinToString(" or ") { it.ifEmpty { listOf(ToolCallSpec("<none>")) }.joinToString(" → ") }}, got ${considered.ifEmpty { null }?.joinToString(" → ") { it.name } ?: "<none>"}"
        } else if (!paramsCorrect) {
            failures += "tool parameters: expected ${bySelection!!.joinToString(" → ")}, got ${considered.joinToString(" → ") { ToolCallSpec(it.name, it.args).toString() }}"
        }
        if (unexpected > 0 && selectionCorrect) failures += "unexpected tool calls ×$unexpected"

        val executed = considered.filter { c -> bestSequence.any { it.name == c.name } }
        val executionSucceeded: Boolean? = if (executed.isEmpty()) null else executed.last().success == true
        if (expect.outcome == Outcome.SUCCESS && expect.tools.isNotEmpty() && executionSucceeded == false) {
            failures += "tool execution failed: ${executed.last().errorCode ?: "unknown"}"
        }

        val mismatches = stateMismatches(expect.state, state)
        val stateCorrect = if (expect.state.isEmpty()) null else mismatches.isEmpty()
        if (mismatches.isNotEmpty()) failures += "state: " + mismatches.joinToString()

        val actionTrulyDone = expect.outcome == Outcome.SUCCESS &&
            (expect.tools.isEmpty() || executionSucceeded == true) &&
            stateCorrect != false
        val finalReply = replies.lastOrNull()
        val claims = replies.filter { ReplyClaims.claimsSuccess(it.text) }
        // A turn that correctly performed nothing (chat) may use 「好的」-like wording.
        val claimsMatter = !actionTrulyDone && !(expect.outcome == Outcome.SUCCESS && expect.tools.isEmpty() && stateCorrect != false)
        // A false claim that a later reply retracts (「这个操作没有执行」) was heard, but corrected.
        val lastClaim = claims.lastOrNull()
        val retracted = lastClaim != null && replies.any { it.atNanos > lastClaim.atNanos && ReplyClaims.isNegative(it.text) }
        val fabricated = expect.forbiddenReplyWords.isNotEmpty() &&
            replies.any { r -> expect.forbiddenReplyWords.any { it in r.text } }
        val falseSuccess = (claimsMatter && lastClaim != null && !retracted) || fabricated
        if (claimsMatter && lastClaim != null && !retracted) failures += "false success: reply claims an action that did not happen (「${lastClaim.text}」)"
        if (fabricated) failures += "false success: reply contains content it could not know"

        val firstSuccessNanos = executed.filter { it.success == true }.mapNotNull { it.executionEndNanos }.minOrNull()
        val premature = actionTrulyDone && expect.tools.isNotEmpty() && firstSuccessNanos != null &&
            claims.any { it.atNanos < firstSuccessNanos }
        val corrected = claimsMatter && lastClaim != null && retracted
        val prematureClaim = premature || corrected

        val replied = finalReply != null && finalReply.text.isNotBlank()
        if (requireReply && expect.requireReply && !replied && !timedOut) failures += "no spoken reply"
        if (expect.replyMentionsAny.isNotEmpty() && replied && expect.replyMentionsAny.none { it in finalReply!!.text }) {
            failures += "reply mentions none of ${expect.replyMentionsAny}"
        }

        return TurnVerdict(
            toolSelectionCorrect = selectionCorrect,
            parametersCorrect = paramsCorrect,
            executionSucceeded = executionSucceeded,
            finalStateCorrect = stateCorrect,
            falseSuccess = falseSuccess,
            prematureClaim = prematureClaim,
            unexpectedToolCalls = unexpected,
            duplicateExecutions = duplicates,
            replied = replied,
            failures = failures,
        )
    }

    /** The expected names appear in order; other calls may be interleaved (counted as unexpected). */
    private fun namesMatch(expected: List<ToolCallSpec>, actual: List<ObservedCall>): Boolean {
        if (expected.isEmpty()) return actual.isEmpty()
        var i = 0
        for (call in actual) if (i < expected.size && call.name == expected[i].name) i++
        return i == expected.size
    }

    /** Arguments of the LAST call matching each expected step (a corrected retry counts). */
    private fun argsMatch(expected: List<ToolCallSpec>, actual: List<ObservedCall>): Boolean {
        var from = 0
        for (spec in expected) {
            val matches = actual.withIndex().filter { it.index >= from && it.value.name == spec.name }
            val ok = matches.firstOrNull { m -> spec.args.all { (k, v) -> valueMatches(v, m.value.args[k]) } }
                ?: return false
            from = ok.index + 1
        }
        return true
    }

    private fun unmatchedCount(expected: List<ToolCallSpec>, actual: List<ObservedCall>): Int {
        val remaining = expected.map { it.name }.toMutableList()
        var extra = 0
        for (call in actual) {
            if (!remaining.remove(call.name)) {
                // A second call of an expected tool with different args is a correction, not noise,
                // only when the earlier one failed.
                val earlierFailed = actual.any { it !== call && it.name == call.name && it.success != true }
                if (!earlierFailed) extra++
            }
        }
        return extra
    }

    fun stateMismatches(expected: Map<String, String>, actual: Map<String, String>): List<String> =
        expected.mapNotNull { (key, want) ->
            val got = actual[key]
            if (valueMatches(want, got)) null else "$key expected $want, got ${got ?: "<missing>"}"
        }

    /**
     * `*` any value; `!x` anything but x; `~x` containment either way (names); numbers compare
     * numerically (0.05 tolerance); otherwise exact.
     */
    fun valueMatches(want: String, got: String?): Boolean {
        if (want == "*") return got != null
        if (want.startsWith("!")) return got != null && !valueMatches(want.substring(1), got)
        if (got == null) return false
        if (want.startsWith("~")) {
            val w = normalise(want.substring(1))
            val g = normalise(got)
            return w.isNotEmpty() && g.isNotEmpty() && (g.contains(w) || w.contains(g))
        }
        val wn = want.toDoubleOrNull()
        val gn = got.toDoubleOrNull()
        if (wn != null && gn != null) return kotlin.math.abs(wn - gn) < 0.05
        return want == got
    }

    private fun normalise(s: String) = s.lowercase().filterNot { it.isWhitespace() || it in "。，,.!！?？、“”\"'「」()（）" }
}

/**
 * Does a reply claim that an action was carried out? Deliberately independent of the app's own
 * guard so the oracle does not grade the app with the app's code.
 */
object ReplyClaims {
    private val done = listOf("已", "了", "好的", "正在为", "为你", "为您", "马上", "这就", "开始")
    private val actions = listOf(
        "调", "打开", "开启", "开了", "关闭", "关掉", "关了", "播放", "暂停", "停止", "停了",
        "导航", "出发", "设置", "设为", "退出", "结束", "取消", "升", "降", "选", "切换", "换",
    )
    private val negative = listOf(
        "不支持", "无法", "不能", "没法", "没有", "暂不", "暂时不", "抱歉", "对不起", "没听清", "再说",
        "失败", "没能", "未能", "出错", "不可用", "没成功", "未成功", "请问", "吗", "？", "?", "哪",
    )

    fun isNegative(text: String): Boolean = negative.any { it in text }

    fun claimsSuccess(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || negative.any { it in t }) return false
        return done.any { it in t } && actions.any { it in t }
    }
}
