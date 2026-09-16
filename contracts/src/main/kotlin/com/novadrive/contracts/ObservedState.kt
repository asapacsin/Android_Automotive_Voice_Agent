package com.novadrive.contracts

data class NavigationObservedState(
    val active: Boolean,
    val destinationLabel: String? = null,
    val destinationCoordinate: GeoCoordinate? = null,
)

data class MediaObservedState(
    val playing: Boolean,
    val title: String? = null,
    val volumePercent: Int,
)

data class PhoneObservedState(
    val inCall: Boolean,
    val remoteNumber: String? = null,
    val remoteDisplayName: String? = null,
)

data class HvacObservedState(
    val cabinTemperatureCelsius: Double,
    val fanLevel: Int,
    val powerOn: Boolean = false,
)

data class ObservedVehicleState(
    val navigation: NavigationObservedState,
    val media: MediaObservedState,
    val phone: PhoneObservedState,
    val hvac: HvacObservedState,
) {
    fun safetySnapshot(callsBlocked: Boolean = false, speedKmh: Double = 0.0): VehicleSafetySnapshot =
        VehicleSafetySnapshot(
            cabinTemperatureCelsius = hvac.cabinTemperatureCelsius,
            callsBlocked = callsBlocked,
            speedKmh = speedKmh,
        )
}

enum class VerificationStatus {
    MATCHED,
    MISMATCHED,
}

data class VerificationReport(
    val status: VerificationStatus,
    val expectedSummary: String,
    val observedSummary: String,
)
