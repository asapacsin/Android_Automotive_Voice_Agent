package com.novadrive.contracts

/**
 * Coordinate-system metadata travels with every location value.
 * Conversion between WGS84, GCJ-02, and provider-defined systems belongs only at
 * provider adapter boundaries, never in core orchestration or safety logic.
 */
enum class CoordinateSystem {
    WGS84,
    GCJ02,
    PROVIDER_DEFINED,
}

data class GeoCoordinate(
    val latitudeDegrees: Double,
    val longitudeDegrees: Double,
    val coordinateSystem: CoordinateSystem,
    val providerDefinedSystemId: String? = null,
) {
    init {
        if (coordinateSystem == CoordinateSystem.PROVIDER_DEFINED) {
            require(!providerDefinedSystemId.isNullOrBlank()) {
                "PROVIDER_DEFINED coordinates require providerDefinedSystemId"
            }
        }
    }

    fun hasPlausibleWgsOrGcjRange(): Boolean =
        latitudeDegrees in -90.0..90.0 && longitudeDegrees in -180.0..180.0
}

data class Destination(
    val label: String,
    val coordinate: GeoCoordinate? = null,
    val poiName: String? = null,
    val restricted: Boolean = false,
)

/**
 * Marker for future Fake / AMap / Baidu / OEM navigation adapters.
 * Core modules must depend on this seam, never on a provider SDK type.
 */
enum class NavigationProviderKind {
    FAKE,
    AMAP,
    BAIDU,
    OEM,
}
