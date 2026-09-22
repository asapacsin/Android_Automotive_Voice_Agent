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
        val hit = cues.firstOrNull { it.first in text } ?: return null
        return UtteranceIntent(hit.second)
    }

    companion object {
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
                "车窗" to CapabilityIds.WINDOWS_SEATS_DOORS_LIGHTS_WIPERS,
                "窗户" to CapabilityIds.WINDOWS_SEATS_DOORS_LIGHTS_WIPERS,
                "天窗" to CapabilityIds.WINDOWS_SEATS_DOORS_LIGHTS_WIPERS,
                "座椅" to CapabilityIds.WINDOWS_SEATS_DOORS_LIGHTS_WIPERS,
                "后备箱" to CapabilityIds.WINDOWS_SEATS_DOORS_LIGHTS_WIPERS,
                "车门" to CapabilityIds.WINDOWS_SEATS_DOORS_LIGHTS_WIPERS,
                "车灯" to CapabilityIds.WINDOWS_SEATS_DOORS_LIGHTS_WIPERS,
                "雨刷" to CapabilityIds.WINDOWS_SEATS_DOORS_LIGHTS_WIPERS,
            ).sortedByDescending { it.first.length },
        )
    }
}
