package com.novadrive.app.nav

import android.content.Context

/**
 * Where this driver's saved places live.
 *
 * Private app preferences, not the Keystore credential store: a home address is personal, but it
 * is not a secret the app authenticates with, and putting it behind the credential mechanism would
 * blur what that mechanism is for ([I-7](../../../../../../../docs/INVARIANTS.md)).
 */
class SavedPlaceStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("nova_saved_places", Context.MODE_PRIVATE)

    fun get(slot: PlaceSlot): SavedPlace? {
        val label = prefs.getString(key(slot, "label"), null) ?: return null
        val lat = prefs.getString(key(slot, "lat"), null)?.toDoubleOrNull() ?: return null
        val lon = prefs.getString(key(slot, "lon"), null)?.toDoubleOrNull() ?: return null
        val address = prefs.getString(key(slot, "address"), "").orEmpty()
        return runCatching { SavedPlace(slot, label, address, lat, lon) }.getOrNull()
    }

    fun set(place: SavedPlace) {
        prefs.edit()
            .putString(key(place.slot, "label"), place.label)
            .putString(key(place.slot, "address"), place.address)
            // Stored as text: SharedPreferences has no double, and putLong(toRawBits) makes a
            // stored coordinate unreadable in a bug report for no gain.
            .putString(key(place.slot, "lat"), place.latitude.toString())
            .putString(key(place.slot, "lon"), place.longitude.toString())
            .apply()
    }

    fun clear(slot: PlaceSlot) {
        prefs.edit()
            .remove(key(slot, "label"))
            .remove(key(slot, "address"))
            .remove(key(slot, "lat"))
            .remove(key(slot, "lon"))
            .apply()
    }

    private fun key(slot: PlaceSlot, field: String) = "${slot.name.lowercase()}_$field"
}
