package com.novadrive.contracts

/**
 * Whether a capability exists is a catalog fact, not a keyword list.
 *
 * [config/capabilities.yaml] is the durable registry; [ProductCapabilities] is the in-process
 * view the voice path consults. `behavior-test/CapabilityContractTest` fails if they drift.
 *
 * Status is not "the driver used a word we dislike". An utterance resolver may map a phrase onto
 * an id; only this catalog may say whether that id is something the product can do.
 */
enum class CapabilityStatus {
    SUPPORTED,
    UNSUPPORTED,
    UNREGISTERED,
}

data class CapabilityRecord(
    val id: String,
    val tool: String?,
    val status: CapabilityStatus,
) {
    val isSupported: Boolean get() = status == CapabilityStatus.SUPPORTED
}

interface CapabilityCatalog {
    fun record(id: String): CapabilityRecord?
    fun ids(): Set<String>
    fun status(id: String): CapabilityStatus = record(id)?.status ?: CapabilityStatus.UNREGISTERED
    fun isSupported(id: String): Boolean = status(id) == CapabilityStatus.SUPPORTED

    /**
     * Test seam: change availability without editing the product catalog or a word list.
     * Ids not already present are added; existing tool names are kept.
     */
    fun overlay(supported: Map<String, Boolean>): CapabilityCatalog
}

class MapCapabilityCatalog(
    private val records: Map<String, CapabilityRecord>,
) : CapabilityCatalog {
    constructor(records: Collection<CapabilityRecord>) : this(records.associateBy { it.id })

    override fun record(id: String): CapabilityRecord? = records[id]
    override fun ids(): Set<String> = records.keys
    override fun overlay(supported: Map<String, Boolean>): CapabilityCatalog {
        val next = records.toMutableMap()
        supported.forEach { (id, on) ->
            val existing = next[id]
            next[id] = CapabilityRecord(
                id = id,
                tool = existing?.tool,
                status = if (on) CapabilityStatus.SUPPORTED else CapabilityStatus.UNSUPPORTED,
            )
        }
        return MapCapabilityCatalog(next)
    }
}

/** Canonical ids, matching `config/capabilities.yaml` group.entry keys. */
object CapabilityIds {
    const val PHONE_PLACE_CALL = "phone.place_call"
    const val VOLUME_CONTROL = "unsupported.volume_control"
    const val WINDOWS_SEATS_DOORS_LIGHTS_WIPERS = "unsupported.windows_seats_doors_lights_wipers"
    const val MEDIA_LIBRARY = "unsupported.media_library"
    const val MEDIA_NEXT_TRACK = "media.next_track"
    const val REALTIME_INFO = "unsupported.realtime_weather_traffic_news"
}

/**
 * In-process capability truth. Keep in step with `config/capabilities.yaml` —
 * [CapabilityContractTest] compares the id sets.
 */
object ProductCapabilities : CapabilityCatalog {
    private val backing = MapCapabilityCatalog(
        listOf(
            rec("navigation.search_place", "navigate_to", true),
            rec("navigation.search_nearby_brand", "navigate_to", true),
            rec("navigation.select_candidate_by_ordinal", "choose_navigation_option", true),
            rec("navigation.select_candidate_by_name", "choose_navigation_option", true),
            rec("navigation.select_route", "choose_navigation_option", true),
            rec("navigation.start_navigation", "choose_navigation_option", true),
            rec("navigation.navigate_to_saved_place", "navigate_to", true),
            rec("navigation.save_place", "save_place", true),
            rec("navigation.stop_navigation", "exit_navigation_mode", true),
            rec("navigation.guidance_voice", null, true),
            rec("navigation.arrival_lifecycle", null, true),
            rec("media.play_music", "control_music", true),
            rec("media.stop_music", "control_music", true),
            rec("media.next_track", null, false),
            rec("climate.power", "control_climate", true),
            rec("climate.set_temperature", "control_climate", true),
            rec("climate.set_fan", "control_climate", true),
            rec("climate.relative_adjustment_from_context", "control_climate", true),
            rec("climate.ambiguous_relative_request", null, true),
            rec("vision.describe_camera_view", "describe_camera_view", true),
            rec("speech.tts", null, true),
            rec("speech.silent_mode", "set_speech_output", true),
            rec("speech.sleep", "end_conversation", true),
            rec("speech.interrupt_tts_by_voice", null, false),
            rec("speech.wake_word", null, true),
            rec("phone.place_call", "place_call", true),
            rec("phone.call_without_sim", "place_call", true),
            rec("apps.open_maps", "open_app", true),
            rec("apps.open_settings", "open_app", true),
            rec("unsupported.volume_control", null, false),
            rec("unsupported.windows_seats_doors_lights_wipers", null, false),
            rec("unsupported.media_library", null, false),
            rec("unsupported.realtime_weather_traffic_news", null, false),
        ),
    )

    override fun record(id: String) = backing.record(id)
    override fun ids() = backing.ids()
    override fun overlay(supported: Map<String, Boolean>) = backing.overlay(supported)

    private fun rec(id: String, tool: String?, supported: Boolean) = CapabilityRecord(
        id = id,
        tool = tool,
        status = if (supported) CapabilityStatus.SUPPORTED else CapabilityStatus.UNSUPPORTED,
    )
}
