package com.novadrive.app.voice

import com.novadrive.contracts.CapabilityIds

/**
 * Utterance → capability id. Parsing only: it does not say whether the product can do the thing.
 *
 * Availability is [com.novadrive.contracts.CapabilityCatalog]. A phrase that maps to
 * `phone.place_call` is a call request even if some other list still contains 电话.
 */
data class UtteranceIntent(val capabilityId: String)

class UtteranceIntentResolver(
    private val cues: List<Pair<String, String>>,
) {
    fun resolve(text: String): UtteranceIntent? {
        if (matchesCapabilityHelpGrammar(text)) {
            return UtteranceIntent(CapabilityIds.SPEECH_CAPABILITY_HELP)
        }
        if (matchesSpeakingStyle(text)) return UtteranceIntent(SPEECH_SPEAKING_STYLE)
        val hit = cues.firstOrNull { it.first in text } ?: return null
        return UtteranceIntent(hit.second)
    }

    /** The words that name [capabilityId], longest first: the vocabulary guards share with this resolver. */
    fun cuesFor(capabilityId: String): List<String> = cues.filter { it.second == capabilityId }.map { it.first }

    companion object {
        /** `speech.speaking_style` (contracts Capability.kt); CapabilityIds has no constant for it yet. */
        const val SPEECH_SPEAKING_STYLE = "speech.speaking_style"

        /**
         * P55: a request for how 小诺 *speaks*. Bounded: a style adjective counts only next to a
         * speaking-manner word (说话/语气/口气/腔调), so a bare 「甜一点」 or 「温柔一点开」 is not one.
         * 嗲 / 撒娇 / 傲娇 name a manner on their own.
         */
        private val STYLE_MANNER_GRAMMAR = Regex(
            "(说话|语气|口气|腔调).{0,4}(嗲|甜|温柔|可爱|元气|活泼|霸道|正常|傲娇|凶|酷)",
        )
        private val STYLE_WORDS = listOf("嗲", "撒娇", "撒个娇", "傲娇", "傲嬌")

        fun matchesSpeakingStyle(text: String): Boolean =
            STYLE_WORDS.any { it in text } || STYLE_MANNER_GRAMMAR.containsMatchIn(text)

        private val PUNCTUATION = Regex("[，。！？、,.!?\\s\t　]+")
        private val WAKE_PREFIX = Regex("^(你好小诺|小诺)+")

        /** Pattern (a): (你)?(到底|究竟)?(都)?(能|会|可以)(帮我|替我)?(做|干)?(些|点)?(什么|啥) */
        private val HELP_GRAMMAR_A = Regex(
            "(你)?(到底|究竟)?(都)?(能|会|可以)(帮我|替我)?(做|干)?(些|点)?(什么|啥)",
        )

        /** Pattern (b): capability inventory questions. */
        private val HELP_GRAMMAR_B = Regex(
            "(有(哪些|什么)?(功能|本事|技能|能力)|介绍(一下)?(你的)?(功能|能力))",
        )

        fun normalizeForHelp(text: String): String {
            var s = text.lowercase()
            s = s.replace("什麼", "什么").replace("甚么", "什么")
            s = s.replace("會", "会").replace("幫", "帮")
            s = PUNCTUATION.replace(s, "")
            s = WAKE_PREFIX.replace(s, "")
            return s
        }

        /**
         * Bounded help grammar — not a growing synonym list. Bare 「干啥」 / 「你干啥」 are excluded
         * because pattern (a) requires 能/会/可以 before 什么/啥.
         */
        fun matchesCapabilityHelpGrammar(text: String): Boolean {
            val normalized = normalizeForHelp(text)
            if (normalized == "whatcanyoudo" || normalized == "whatdoyousupport") return true
            if (HELP_GRAMMAR_B.containsMatchIn(normalized)) return true
            if (HELP_GRAMMAR_A.containsMatchIn(normalized)) return true
            return false
        }

        /**
         * Longer cues first so 「打电话」 wins over 「电话」. This list is not capability truth —
         * it is how a sentence names an id.
         */
        fun product(): UtteranceIntentResolver = UtteranceIntentResolver(
            listOf(
                "打电话" to CapabilityIds.PHONE_PLACE_CALL,
                "打个电话" to CapabilityIds.PHONE_PLACE_CALL,
                "拨打" to CapabilityIds.PHONE_PLACE_CALL,
                "电话" to CapabilityIds.PHONE_PLACE_CALL,
                "下一首" to CapabilityIds.MEDIA_NEXT_TRACK,
                "上一首" to CapabilityIds.MEDIA_NEXT_TRACK,
                "换一首" to CapabilityIds.MEDIA_NEXT_TRACK,
                "换首歌" to CapabilityIds.MEDIA_NEXT_TRACK,
                "切歌" to CapabilityIds.MEDIA_NEXT_TRACK,
                "音量" to CapabilityIds.VOLUME_CONTROL,
                "大声" to CapabilityIds.VOLUME_CONTROL,
                "小声" to CapabilityIds.VOLUME_CONTROL,
                "声音" to CapabilityIds.VOLUME_CONTROL,
                "车窗" to CapabilityIds.BODY_WINDOW,
                "窗户" to CapabilityIds.BODY_WINDOW,
                "开窗" to CapabilityIds.BODY_WINDOW,
                "关窗" to CapabilityIds.BODY_WINDOW,
                // SPEC-015 FZ-01/02/09/10: comfort scenarios. Not bare 困 (「困难」「被困」).
                "蚊子" to CapabilityIds.COMFORT_SCENARIO,
                "虫子" to CapabilityIds.COMFORT_SCENARIO,
                "闷" to CapabilityIds.COMFORT_SCENARIO,
                "空气不好" to CapabilityIds.COMFORT_SCENARIO,
                "异味" to CapabilityIds.COMFORT_SCENARIO,
                "好困" to CapabilityIds.COMFORT_SCENARIO,
                "犯困" to CapabilityIds.COMFORT_SCENARIO,
                "有点困" to CapabilityIds.COMFORT_SCENARIO,
                "座椅" to CapabilityIds.BODY_SEAT_HEIGHT,
                "座位" to CapabilityIds.BODY_SEAT_HEIGHT,
                "天窗" to CapabilityIds.SUNROOF_DOORS_LIGHTS_WIPERS,
                "天幕" to CapabilityIds.SUNROOF_DOORS_LIGHTS_WIPERS,
                "后备箱" to CapabilityIds.SUNROOF_DOORS_LIGHTS_WIPERS,
                "车门" to CapabilityIds.SUNROOF_DOORS_LIGHTS_WIPERS,
                "车灯" to CapabilityIds.SUNROOF_DOORS_LIGHTS_WIPERS,
                "雨刷" to CapabilityIds.SUNROOF_DOORS_LIGHTS_WIPERS,
            ).sortedByDescending { it.first.length },
        )
    }
}

