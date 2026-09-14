package com.novadrive.contracts

enum class PolicyDecision {
    ALLOW,
    CONFIRM,
    DENY,
}

data class SafetyVerdict(
    val decision: PolicyDecision,
    val reasonCode: String,
    val messageZhCn: String,
)

data class VehicleSafetySnapshot(
    val cabinTemperatureCelsius: Double,
    val callsBlocked: Boolean = false,
    val speedKmh: Double = 0.0,
)

object PolicyReasons {
    const val ALLOW_DEFAULT: String = "allow.default"
    const val DENY_RESTRICTED_DESTINATION: String = "deny.navigation.restricted"
    const val DENY_CALLS_BLOCKED: String = "deny.phone.calls_blocked"
    const val DENY_NUMBER_BLOCKED: String = "deny.phone.number_blocked"
    const val CONFIRM_PLACE_CALL: String = "confirm.phone.place_call"
    const val CONFIRM_LARGE_HVAC: String = "confirm.hvac.large_delta"
    const val CONFIRM_HIGH_VOLUME: String = "confirm.media.high_volume"
}
