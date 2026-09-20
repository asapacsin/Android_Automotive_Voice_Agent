package com.novadrive.app.voice

import com.novadrive.contracts.CapabilityCatalog
import com.novadrive.contracts.CapabilityIds
import com.novadrive.contracts.ProductCapabilities
import com.novadrive.ingress.realtime.ResponseOutcome

/**
 * Catches a reply that claims a car action happened when no tool was called.
 *
 * Measured 2026-09-17 (speech harness, fresh conversation, camera open): 「温度调高一点。」 was
 * answered 「调高温度了。」 with outputs=[message] and no control_climate call — the temperature did
 * not change. The persona already forbids this; the model still does it about 1 time in 4.
 *
 * When the driver asked for a control action (or about the camera picture) and the reply finished
 * without any tool call and without declining, the client sends [nudgeFor] as a follow-up turn so
 * the model performs the action and answers from the tool result. At most once per utterance.
 * The false sentence has already been spoken by then; the follow-up makes it true or corrects it.
 */
class ActionClaimGuard {
    private var userText: String? = null
    private var toolCalledThisTurn = false
    private var nudged = false

    /** The latest tool result of this turn said ok=false (and why). */
    private var lastToolFailure: String? = null
    private var failureCorrected = false

    @Synchronized
    fun onUserTranscript(text: String) {
        userText = text.trim().takeIf { it.isNotEmpty() }
        toolCalledThisTurn = false
        nudged = false
        lastToolFailure = null
        failureCorrected = false
    }

    /**
     * A tool result is being returned to the model. Found by the simulation benchmark
     * (2026-09-17): when a tool fails and the model still says 「已经调好了」, nothing corrected it.
     */
    @Synchronized
    fun onToolResult(output: String) {
        lastToolFailure = if (output.contains("\"ok\":false")) {
            Regex("\"message\":\"([^\"]+)\"").find(output)?.groupValues?.get(1)
                ?: Regex("\"error\":\"([^\"]+)\"").find(output)?.groupValues?.get(1)
                ?: "操作失败"
        } else {
            null
        }
    }

    /** A response finished. Returns the follow-up text to send, or null. */
    @Synchronized
    fun onResponseDone(outcome: ResponseOutcome, assistantText: String): String? {
        if (outcome.requestedTool) {
            toolCalledThisTurn = true
            return null
        }
        if (!outcome.spoke) return null
        val failure = lastToolFailure
        if (failure != null && !failureCorrected && claimsDone(assistantText.trim())) {
            failureCorrected = true
            return correctionForFailure(failure)
        }
        val reply = assistantText.trim()
        val request = userText
        userText = null
        if (toolCalledThisTurn || nudged) return null
        // No utterance at all means the app started this turn itself (VoiceSessionGateway.speak),
        // where a claim may be about something the *app* just did. Left alone deliberately: the
        // check below is for a driver who spoke and was not understood.
        if (request == null) return null
        val suspicious = when {
            isCameraQuestion(request) -> !declines(reply)
            isHelpRequest(request) -> !answersCapabilityHelp(reply)
            isUnsupportedRequest(request) -> claimsDone(reply)
            // A claim needs correcting; an action that simply never happened needs performing.
            // Measured on device 2026-09-19: 「有点热」, 「再凉一点」 and 「还是有点热」 were each
            // answered with an intention (「调低温度。」) and no tool call. Nothing claimed completion, so
            // nothing was corrected, and the request evaporated. A question still ends here,
            // because declines() covers the clarification the ambiguity policy requires.
            // When the app has already resolved the request to ONE concrete action, a question back
            // is not the clarification the ambiguity policy protects — that path returns Clarify,
            // and asksForClimateChange() is false for it. It is hesitation, and it costs the driver
            // a turn at the wheel. Measured 2026-09-19: 「有点热。」 → 「需要我调节空调温度吗？」.
            // describesCarAction covers the wording claimsDone misses. Measured on device
            // 2026-09-20: 「算了」 was answered 「退出导航中，请选择下一个目的地。」 with no tool call -
            // no completion word, so claimsDone was false, and the driver was told to repeat
            // themselves for a request the app had understood perfectly well. When the request is
            // known, the right follow-up is to perform it, not to ask again.
            isControlRequest(request) ->
                claimsDone(reply) ||
                    describesCarAction(reply) ||
                    ContextResolver.asksForClimateChange(request)
            else -> false
        }
        if (suspicious) {
            nudged = true
            return nudgeFor(request)
        }
        // A real capability-help answer names the same device nouns as an action claim. Do not
        // treat 「我能帮你导航、放音乐」 as an unverified car action (P28).
        if (isHelpRequest(request) && answersCapabilityHelp(reply)) return null
        return unverifiedClaim(reply, request)?.also { nudged = true }
    }

    /**
     * An action in the reply - finished, underway, or promised - with no tool call behind it.
     *
     * The promise matters as much as the claim. Measured on device 2026-09-19: 「有啲熱，幫我舒
     * 服啲」 was heard as 「有的人帮我舒服的」 and answered 「有点热啊，我帮你调低一点温度」
     * with outputs=[message]. Nothing was called and the cabin stayed hot. To the driver that is
     * indistinguishable from a completed action: they were told it was being handled.
     */
    private fun unverifiedClaim(reply: String, request: String?): String? {
        if (!claimsDone(reply) && !describesCarAction(reply)) return null
        // The driver was heard; what is missing is the *target*. Telling them we did not catch a
        // sentence we caught perfectly is both false and useless (measured 2026-09-20 on 「再低一点」).
        if (request != null && ContextResolver.needsClarification(request)) return CLARIFY_REFERENT
        return UNVERIFIED_ACTION_CLAIM
    }

    @Synchronized
    fun reset() {
        userText = null
        toolCalledThisTurn = false
        nudged = false
        lastToolFailure = null
        failureCorrected = false
    }

    companion object {
        private val CONTROL_WORDS = listOf(
            "空调", "温度", "度", "风量", "风速", "暖风", "冷风", "除雾",
            "音乐", "歌", "播放", "暂停",
            "导航", "带我去", "出发", "目的地",
            "打开", "关闭", "关掉", "开启",
            "调高", "调低", "调大", "调小", "调到", "升高", "降低",
            "结束", "退出", "取消", "算了", "不用了", "没事了", "返回",
            "路线", "第一", "第二", "第三", "第四", "第五", "最快", "最短", "免费", "红绿灯",
        )
        /**
         * Fallback heuristic only. Consulted after [UtteranceIntentResolver] and
         * [CapabilityCatalog] have nothing to say. It is not capability truth — 电话 stays here
         * so a registered `phone.place_call` can still prove the catalog wins.
         *
         * Measured 2026-09-17: the generic follow-up for 「音量调大。」 made the model call
         * control_climate and raise the fan — a wrong action.
         */
        private val FALLBACK_UNSUPPORTED_CUES = listOf(
            "音量", "声音", "大声", "小声", "车窗", "窗户", "天窗", "座椅", "电话", "后备箱", "车门", "车灯", "雨刷",
            "下一首", "上一首", "换一首", "换首歌", "切歌",
        )

        /** Music nouns; without one of these a 「放」 is not a media request (「放大地图」). */
        private val MEDIA_NOUNS = listOf("音乐", "歌", "曲")
        private val MEDIA_PLAY_WORDS = listOf("放", "播放", "听")

        /**
         * The words that make up a *generic* music request. What survives stripping them is the
         * driver asking for something in particular.
         */
        private val GENERIC_MEDIA_TOKENS = Regex(
            "播放|音乐|歌曲|随便|随意|来点|来首|一下|一首|放|听|来|首|点|歌|曲|吧|我|想|要|给|帮|个|的|了|啊|呢|请|下",
        )
        private val MEDIA_PUNCTUATION = "，。！？、,.!? \t　"
        /**
         * Questions about the world right now. This product has **no** weather, traffic or news
         * source, so any answer containing an actual forecast is invented by definition — there is
         * nothing it could have been read from.
         *
         * Measured 2026-09-18: 「今天天气怎么样」 was answered honestly once and, in a later session,
         * with an invented forecast for **Beijing** (「今天北京天气晴转多云，气温20到28度」). Checklist
         * row T09 requires a fixed refusal instead.
         */
        private val REALTIME_INFO_WORDS = listOf(
            "天气", "气温", "温度多少度", "下雨", "下雪", "台风", "空气质量", "雾霾", "紫外线",
            "路况", "堵车", "拥堵", "限行", "油价", "股票", "新闻", "汇率",
        )

        private val CAMERA_WORDS = listOf("镜头", "摄像头", "画面", "拍到", "看看前面", "前面有什么", "前面是什么", "前面是谁", "前面有谁")
        private val DONE_WORDS = listOf("已", "了", "好的", "正在", "为你", "为您", "马上", "这就")
        private val ACTION_WORDS = listOf(
            "调", "打开", "开启", "开了", "关闭", "关掉", "关了", "播放", "暂停", "停止", "停了",
            "导航", "出发", "设置", "设为", "退出", "结束", "取消", "升", "降", "提高",
            "选", "开始",
        )
        private val DECLINE_WORDS = listOf(
            "不支持", "无法", "不能", "没法", "没有", "暂不", "暂时不", "抱歉", "对不起", "没听清", "再说一遍",
            "请问", "吗", "？", "?", "哪", "什么",
        )

        /**
         * A request this product can act on.
         *
         * Structured intent + [catalog] first. The word list is not capability truth: 「打电话」
         * maps to `phone.place_call` and is an action only while that id is supported.
         *
         * 「有点热」 names nothing in [CONTROL_WORDS] — no 空调, no 温度, no 调 — yet SPEC-006
         * handles it, and `ContextResolver` resolves it to a concrete adjustment.
         */
        fun isControlRequest(
            text: String,
            catalog: CapabilityCatalog = ProductCapabilities,
        ): Boolean {
            val intent = resolvedIntent(text)
            if (intent != null) return catalog.isSupported(intent.capabilityId)
            return CONTROL_WORDS.any { it in text } ||
                ContextResolver.isImplicitComfortRequest(text) ||
                ContextResolver.asksForClimateChange(text)
        }

        fun isUnsupportedRequest(
            text: String,
            catalog: CapabilityCatalog = ProductCapabilities,
        ): Boolean {
            val intent = resolvedIntent(text)
            if (intent != null) return !catalog.isSupported(intent.capabilityId)
            return fallbackUnsupportedHeuristic(text)
        }

        /** Exposed so tests can show the heuristic still contains 电话 while the catalog wins. */
        fun fallbackUnsupportedHeuristic(text: String): Boolean =
            FALLBACK_UNSUPPORTED_CUES.any { it in text }

        private fun resolvedIntent(text: String): UtteranceIntent? =
            UtteranceIntentResolver.product().resolve(text)
                ?: if (isSpecificMediaRequest(text)) UtteranceIntent(CapabilityIds.MEDIA_LIBRARY) else null

        /**
         * A request for *particular* music. `media` is one bundled track with play/stop — there is
         * no library, no search and no track metadata (`config/capabilities.yaml`), so a request
         * that names a song, an artist or a style cannot be executed. Starting the bundled track
         * instead would be a false claim about which capability ran ([I-2](../docs/INVARIANTS.md)):
         * something plays, the result is `ok=true`, and the driver is told they got what they asked
         * for.
         *
         * Structural rather than a list of artists, which could never be complete: strip the words
         * that make a request *generic* and see whether the driver named anything else. A music
         * noun is required so 「放大地图」 is not mistaken for a media request.
         */
        fun isSpecificMediaRequest(text: String): Boolean = isSpecificMediaRequest(text, false)

        /**
         * [mediaIntentKnown] when something else has already established that this is a request to
         * play music - in practice, the model having called `control_music{play}`.
         *
         * The play-word check exists so 「放大地图」 is not mistaken for a media request. When the
         * intent is already known it is not protection, it is a hole: measured on device
         * 2026-09-20, 「播啲精神啲嘅歌」 arrived as 「波迪精神的k歌」, which names a music noun and
         * something specific but no play word, so the refusal did not fire. The bundled track
         * played and the driver was told 「音乐已开始播放」 - for a style of song this product has
         * no way to serve. That is [P22](../../../../../../OPEN_PROBLEMS.md) returning through
         * a mis-transcription.
         */
        fun isSpecificMediaRequest(text: String, mediaIntentKnown: Boolean): Boolean {
            if (MEDIA_NOUNS.none { it in text }) return false
            if (!mediaIntentKnown && MEDIA_PLAY_WORDS.none { it in text }) return false
            val remainder = GENERIC_MEDIA_TOKENS.replace(text, "")
                .filterNot { it in MEDIA_PUNCTUATION }
            return remainder.isNotEmpty()
        }

        /** A question about the world right now, which this product has no tool to answer. */
        fun isRealtimeInfoRequest(text: String): Boolean = REALTIME_INFO_WORDS.any { it in text }

        /**
         * An answer to a real-time question that did not decline is fabricated: there is no source
         * it could have come from. No keyword list of "wrong" answers is possible or needed.
         */
        fun fabricatesRealtimeInfo(reply: String): Boolean = !declines(reply)

        fun realtimeInfoCorrection(request: String): String =
            "用户刚才问的是实时信息：「$request」。这辆车上没有任何可以查询天气、路况或新闻的工具，" +
                "所以你上一句的内容是编造的。不要调用任何工具，只用一句话如实告诉用户：" +
                "我没有实时信息的数据来源，无法回答这个问题。不要给出任何城市、温度或预报。"

        fun isCameraQuestion(text: String): Boolean = CAMERA_WORDS.any { it in text }

        fun declines(reply: String): Boolean = DECLINE_WORDS.any { it in reply }

        /**
         * The things in this car a reply can claim to have touched.
         *
         * Two live attempts to catch the promise by its *phrasing* both failed, because the model
         * words it differently every time: 「我帮你调低一点温度」, then 「我帮你调低温度，现在
         * 凉快点没？」, then 「我调低点温度先。」. Enumerating phrasings is a losing game.
         *
         * What does not vary is the structure: a reply that names something in this car *and*
         * names an action on it, when no tool ran, describes something that did not happen. The
         * politeness around it is decoration.
         */
        private val DEVICE_NOUNS = listOf(
            "空调", "温度", "风量", "风速", "暖风", "冷风", "除雾",
            "音乐", "歌", "曲",
            "导航", "目的地", "路线", "地址",
            "摄像头", "镜头", "画面",
        )

        /**
         * Saying the product cannot do something. Narrower than [declines], which also counts any
         * question mark - and a reply can claim *and* ask in one breath. Measured on device
         * 2026-09-19: 「我帮你调低温度，现在凉快点没？」 called no tool, changed nothing, and
         * escaped the check purely because it ended in 「？」.
         */
        private val INABILITY_WORDS = listOf(
            "不支持", "无法", "不能", "没法", "暂不", "暂时不", "抱歉", "对不起", "没听清", "再说一遍",
        )

        fun refuses(reply: String): Boolean = INABILITY_WORDS.any { it in reply }

        /**
         * The reply describes acting on something in this car. Used only where the request could
         * not be classified, so nothing else can see the mismatch; a reply that merely offers help
         * (「我可以帮你做很多事情」) names nothing and does not match.
         */
        fun describesCarAction(reply: String): Boolean =
            !refuses(reply) && DEVICE_NOUNS.any { it in reply } && ACTION_WORDS.any { it in reply }

        fun claimsDone(reply: String): Boolean =
            !declines(reply) && DONE_WORDS.any { it in reply } && ACTION_WORDS.any { it in reply }

        /** The tool said it failed; the reply said it worked. */
        fun correctionForFailure(reason: String): String =
            "工具返回的结果是失败（$reason），你上一句说已经完成是错误的。" +
                "不要调用任何工具，只用一句话如实告诉用户：这个操作没有成功，并简单说明原因。"

        /**
         * Sent when the reply described an action that no tool performed, and the app cannot tell
         * what was asked. Performing something would be a guess; the only honest move is to say so
         * and ask again.
         */
        /**
         * The request was understood; which control it refers to was not. SPEC-006's ambiguity
         * policy: ask, in one sentence, and execute nothing.
         */
        const val CLARIFY_REFERENT =
            "用户说的这句话没有说明要调的是温度还是风量，之前也没有记录可以判断，所以没有执行。" +
                "请只用一句话反问用户是温度还是风量，不要调用任何工具，也不要说没听清。"

        const val UNVERIFIED_ACTION_CLAIM =
            "你上一句说的操作实际上没有执行：你没有调用任何工具，车上也没有任何变化。" +
                "而且用户刚才说的话可能没有听清楚。不要调用任何工具，" +
                "只用一句话如实告诉用户：刚才没有听清楚，也没有执行任何操作，请再说一遍。"

        /** Self-contained: it may land in a fresh conversation after a reset. */
        fun nudgeFor(request: String): String =
            if (isHelpRequest(request)) {
                HELP_CAPABILITY_NUDGE
            } else if (isUnsupportedRequest(request)) {
                "用户刚才说：「$request」。你没有能完成这个请求的工具，上一句说已经完成是错误的。" +
                    "不要调用任何工具，只用一句话向用户更正：这个操作没有执行，暂时不支持。"
            } else {
                "用户刚才说：「$request」。你上一句回答没有调用任何工具，所以这个操作实际上并没有执行。" +
                    "现在请调用与这个请求完全对应的工具真正完成它（询问摄像头画面就调用 describe_camera_view），" +
                    "不要调用无关的工具，然后只根据工具返回的结果简短如实回答。"
            }

        /**
         * Owner report 2026-09-20: 「你能做什么」 was answered as if it were noise / 不理解.
         * Capability truth lives in [ProductCapabilities]; this only recognises the *question*.
         */
        fun isHelpRequest(text: String): Boolean = HELP_CUES.any { it in text }

        /** A reply that actually names what this car can do, not a repair or blank refusal. */
        fun answersCapabilityHelp(reply: String): Boolean {
            if (refuses(reply) && HELP_CAPABILITY_WORDS.none { it in reply }) return false
            return HELP_CAPABILITY_WORDS.count { it in reply } >= 2
        }

        const val HELP_CAPABILITY_NUDGE =
            "用户在问你能做什么。不要说没听清或不理解。" +
                "不要调用任何工具。用一两句口语说明你能：导航去某地或回家/公司、播放或关闭音乐、调节空调、" +
                "看摄像头画面、打电话（需确认）、打开地图或设置；不要列长清单，不要提模型或提示词。"

        private val HELP_CUES = listOf(
            "你能做什么", "你会做什么", "你可以做什么", "你可以做什麼", "你能做什麼",
            "有什么功能", "有什麼功能", "你会什么", "你会什麼", "能帮我做什么", "能幫我做什麼",
            "what can you do", "what do you support",
        )

        private val HELP_CAPABILITY_WORDS = listOf(
            "导航", "音乐", "空调", "摄像头", "电话", "地图", "设置", "回家", "公司",
        )
    }
}
