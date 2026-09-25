package com.novadrive.app.nav

data class Destination(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val poiId: String? = null,
    val address: String? = null,
    val adcode: String? = null,
) {
    // v2 section 10 says the LLM must never invent coordinates, so the type refuses impossible ones.
    init {
        require(latitude in -90.0..90.0) { "latitude $latitude is outside -90..90" }
        require(longitude in -180.0..180.0) { "longitude $longitude is outside -180..180" }
    }
}

sealed interface DestinationResult {
    data class Resolved(val destination: Destination) : DestinationResult
    /** v2 section 10 / TC10: never invent a choice. */
    data class Ambiguous(val candidates: List<Destination>) : DestinationResult
    data class Failed(val errorCode: String) : DestinationResult
}

sealed interface RoutePlanResult {
    data class Success(val routeId: Int) : RoutePlanResult
    data class Failed(val errorCode: String) : RoutePlanResult
}

sealed interface NavigationResult {
    data object Started : NavigationResult
    data class Failed(val errorCode: String) : NavigationResult
}
