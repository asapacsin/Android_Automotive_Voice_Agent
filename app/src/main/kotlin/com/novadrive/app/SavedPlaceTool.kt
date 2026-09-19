package com.novadrive.app

import com.novadrive.app.nav.DestinationCandidate
import com.novadrive.app.nav.PlaceSlot
import com.novadrive.app.nav.SavedPlace
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.ToolDispatchResult
import org.json.JSONObject

/**
 * This driver's 「家」 and 「公司」: reading them, and changing them by voice.
 *
 * Saved places are not search results. Measured on device 2026-09-19, `navigate_to destination=回家`
 * went to Amap's POI search verbatim and returned `count=0` — so the thing drivers say most often
 * was the one thing the product could not do. A luckier search would have been worse: 家 matches
 * shops and strangers' addresses, and being routed somewhere confidently wrong beats being told
 * nothing only in the sense that it takes longer to notice.
 */
class SavedPlaceTool(
    private val read: (PlaceSlot) -> SavedPlace?,
    private val write: (SavedPlace) -> Unit,
    /** Turns a spoken address into coordinates, or null when no place matches it. */
    private val resolve: (String) -> DestinationCandidate?,
) {
    fun get(slot: PlaceSlot): SavedPlace? = read(slot)

    /**
     * The address is resolved to coordinates *before* it is stored, and failing to resolve is
     * failing to save. Storing an unresolvable string would move the failure to the moment the
     * driver says 「回家」 while already driving, which is the worst time to discover it.
     */
    fun save(
        call: DomainVoiceEvent.ToolCall,
        failed: (DomainVoiceEvent.ToolCall, String) -> ToolDispatchResult,
    ): ToolDispatchResult {
        val slot = when (call.arguments["slot"]) {
            "home" -> PlaceSlot.HOME
            "work" -> PlaceSlot.WORK
            else -> return failed(call, "SLOT_NOT_ALLOWED")
        }
        val address = call.arguments["address"]?.trim().orEmpty()
        if (address.isBlank()) return failed(call, "BLANK_ADDRESS")
        if (address.length > 120) return failed(call, "ADDRESS_TOO_LONG")
        val resolved = resolve(address) ?: return failed(call, "ADDRESS_NOT_FOUND")
        write(
            SavedPlace(
                slot = slot,
                label = slot.spokenName,
                address = resolved.address.ifBlank { resolved.name },
                latitude = resolved.latitude,
                longitude = resolved.longitude,
            ),
        )
        DebugVoiceLog.log("saved_place_set slot=${slot.name}")
        return ToolDispatchResult(
            null, null, successChip = "✓ ${call.name}",
            output = JSONObject()
                .put("ok", true)
                .put("tool", call.name)
                .put("status", "saved")
                .put("slot", call.arguments["slot"])
                // The resolved name, so the assistant confirms the place it actually stored
                // rather than repeating what it heard.
                .put("place", resolved.name)
                .toString(),
        )
    }

    companion object {
        /** A dispatcher with no driver behind it: every slot is unset and nothing can be saved. */
        fun none(): SavedPlaceTool = SavedPlaceTool({ null }, {}, { null })
    }
}
