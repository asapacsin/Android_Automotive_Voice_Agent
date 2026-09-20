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
        val hit = cues.firstOrNull { it.first in text } ?: return null
        return UtteranceIntent(hit.second)
    }

    companion object {
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
