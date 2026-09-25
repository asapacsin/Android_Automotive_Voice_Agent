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
        if (isHelpRequest(request)) return null
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
         * Questions about the world right now that `query_live_info` can answer (SPEC-011), by kind.
         * An answer is true only when a lookup of that kind succeeded in the same turn; any other
         * answer containing a forecast is invented.
         *
         * Measured 2026-09-18: 「今天天气怎么样」 was answered honestly once and, in a later session,
         * with an invented forecast for **Beijing** (「今天北京天气晴转多云，气温20到28度」). Checklist
         * row T09 required a fixed refusal while there was no source.
         */
        private val LIVE_INFO_WORDS = mapOf(
            "weather" to listOf("天气", "气温", "温度多少度", "下雨", "下雪", "台风"),
            "route_traffic" to listOf("路况", "堵车", "拥堵", "堵不堵"),
        )

        /**
         * Real-time questions with **no** source on this car, SPEC-011 included: Amap's weather
         * endpoint has no air quality, and there is no news, market or price feed. Always refused.
         */
        private val NO_SOURCE_INFO_WORDS = listOf(
            "空气质量", "雾霾", "紫外线", "限行", "油价", "股票", "股价", "新闻", "汇率",
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
            // SPEC-011: the honest outcomes of a live lookup that did not return data.
            "查不到", "没查到", "用完了",
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
            if (intent?.capabilityId == CapabilityIds.SPEECH_CAPABILITY_HELP) return false
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

        /** A question about the world right now: answerable from a live lookup, or not at all. */
        fun isRealtimeInfoRequest(text: String): Boolean =
            NO_SOURCE_INFO_WORDS.any { it in text } || LIVE_INFO_WORDS.values.any { words -> words.any { it in text } }

        /**
         * The `query_live_info` kind whose successful result may answer [text], or null. Null
         * whenever the question also asks for something with no source: 「天气和新闻」 cannot be
         * made true by a weather lookup.
         */
        fun liveInfoKindFor(text: String): String? {
            if (NO_SOURCE_INFO_WORDS.any { it in text }) return null
            return LIVE_INFO_WORDS.entries.firstOrNull { (_, words) -> words.any { it in text } }?.key
        }

        /**
         * An answer to a real-time question that did not decline, with no lookup behind it, is
         * fabricated: there is nothing it could have been read from.
         */
        fun fabricatesRealtimeInfo(reply: String): Boolean = !declines(reply)

        /**
         * Self-contained: it may land in a fresh conversation. Three cases, so the model is never
         * told something false about its own tools: no source exists; a lookup ran and failed (do
         * not retry — measured 2026-09-24, a re-request hid the failure); or nothing was looked up.
         */
        fun realtimeInfoCorrection(request: String, lookupFailed: Boolean = false): String {
            val kind = liveInfoKindFor(request)
            return when {
                kind == null ->
                    "用户刚才问的是实时信息：「$request」。这辆车上没有可以查询新闻、股价、油价、汇率或空气质量的数据来源，" +
                        "所以你上一句的内容是编造的。不要调用任何工具，只用一句话如实告诉用户：" +
                        "我没有这类实时信息的数据来源，无法回答这个问题。不要给出任何城市、温度或预报，也不要给出任何数字。"
                lookupFailed ->
                    "用户刚才问的是实时信息：「$request」。刚才的查询没有成功，没有拿到任何结果，所以你上一句的内容是编造的。" +
                        "不要调用任何工具，只用一句话如实告诉用户现在查不到。不要给出任何城市、温度或预报，也不要描述路况。"
                else ->
                    "用户刚才问的是实时信息：「$request」。你没有查询，所以你上一句的内容是编造的。" +
                        "请调用 query_live_info（kind=$kind）查询，然后只根据返回结果回答；如果查询失败，就如实说查不到。" +
                        "拿到结果之前，不要给出任何城市、温度或预报，也不要描述路况。"
            }
        }

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

        /**
         * The tool said it failed and the reply claiming success was dropped before the driver
         * heard it. Unlike [correctionForFailure] there is no heard sentence to retract, and the
         * conversation may have been reset since, so this names the failure on its own terms.
         */
        fun reportFailure(reason: String): String =
            "工具返回的结果是失败（$reason），操作没有完成。" +
                "不要调用任何工具，只用一句话如实告诉用户：这个操作没有成功，并简单说明原因。"

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
        fun nudgeFor(
            request: String,
            catalog: CapabilityCatalog = ProductCapabilities,
        ): String =
            if (isHelpRequest(request)) {
                val summary = ProductCapabilities.spokenHelpSummary(catalog)
                "用户在问你能做什么。不要说没听清或不理解。不要调用任何工具。请原样说出：$summary"
            } else if (isUnsupportedRequest(request)) {
                "用户刚才说：「$request」。你没有能完成这个请求的工具，上一句说已经完成是错误的。" +
                    "不要调用任何工具，只用一句话向用户更正：这个操作没有执行，暂时不支持。"
            } else {
                "用户刚才说：「$request」。你上一句回答没有调用任何工具，所以这个操作实际上并没有执行。" +
                    "现在请调用与这个请求完全对应的工具真正完成它（询问摄像头画面就调用 describe_camera_view），" +
                    "不要调用无关的工具，然后只根据工具返回的结果简短如实回答。"
            }

        /** Recognised by [UtteranceIntentResolver] → [CapabilityIds.SPEECH_CAPABILITY_HELP]. */
        fun isHelpRequest(text: String): Boolean =
            UtteranceIntentResolver.product().resolve(text)?.capabilityId ==
                CapabilityIds.SPEECH_CAPABILITY_HELP

        /** A reply that actually names supported catalog groups, not a repair or blank refusal. */
        fun answersCapabilityHelp(
            reply: String,
            catalog: CapabilityCatalog = ProductCapabilities,
        ): Boolean {
            val nouns = capabilityHelpNouns(catalog)
            if (refuses(reply) && nouns.none { it in reply }) return false
            return nouns.count { it in reply } >= 2
        }

        private fun capabilityHelpNouns(catalog: CapabilityCatalog): List<String> {
            val nouns = mutableListOf<String>()
            if (catalog.isSupported("navigation.search_place") ||
                catalog.isSupported("navigation.navigate_to_saved_place")
            ) {
                nouns += "导航"
            }
            if (catalog.isSupported("media.play_music") || catalog.isSupported("media.stop_music")) {
                nouns += "音乐"
            }
            if (catalog.ids().any { it.startsWith("climate.") && catalog.isSupported(it) }) {
                nouns += "空调"
            }
            if (catalog.isSupported("vision.describe_camera_view")) {
                nouns += "摄像头"
            }
            if (catalog.isSupported("phone.place_call")) {
                nouns += "电话"
            }
            if (catalog.isSupported("apps.open_maps") || catalog.isSupported("apps.open_settings")) {
                nouns += "地图"
                nouns += "设置"
            }
            return nouns
        }
    }
}
