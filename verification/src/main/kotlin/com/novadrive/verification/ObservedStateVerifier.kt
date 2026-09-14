package com.novadrive.verification

import com.novadrive.contracts.ObservedVehicleState
import com.novadrive.contracts.StructuredCommand
import com.novadrive.contracts.VerificationReport
import com.novadrive.contracts.VerificationStatus
import com.novadrive.vehicle.VehicleCommand

class ObservedStateVerifier {
    fun verify(
        command: StructuredCommand,
        vehicleCommand: VehicleCommand,
        observed: ObservedVehicleState,
    ): VerificationReport {
        val (ok, expected, observedSummary) = when (vehicleCommand) {
            is VehicleCommand.StartRoute -> {
                val expectedLabel = vehicleCommand.destination.label
                val match = observed.navigation.active && observed.navigation.destinationLabel == expectedLabel
                Triple(match, "navigation.active=$expectedLabel", navSummary(observed))
            }

            is VehicleCommand.CancelRoute -> {
                val match = !observed.navigation.active && observed.navigation.destinationLabel == null
                Triple(match, "navigation.inactive", navSummary(observed))
            }

            is VehicleCommand.Play -> {
                val match = observed.media.playing && observed.media.title == vehicleCommand.query
                Triple(match, "media.playing=${vehicleCommand.query}", mediaSummary(observed))
            }

            is VehicleCommand.Pause -> {
                val match = !observed.media.playing
                Triple(match, "media.paused", mediaSummary(observed))
            }

            is VehicleCommand.SetVolume -> {
                val match = observed.media.volumePercent == vehicleCommand.volumePercent
                Triple(match, "media.volume=${vehicleCommand.volumePercent}", mediaSummary(observed))
            }

            is VehicleCommand.Dial -> {
                val match = observed.phone.inCall &&
                    observed.phone.remoteNumber == vehicleCommand.contact.phoneNumber
                Triple(match, "phone.inCall=${vehicleCommand.contact.phoneNumber}", phoneSummary(observed))
            }

            is VehicleCommand.HangUp -> {
                val match = !observed.phone.inCall
                Triple(match, "phone.idle", phoneSummary(observed))
            }

            is VehicleCommand.SetCabinTemperature -> {
                val match = observed.hvac.cabinTemperatureCelsius == vehicleCommand.celsius
                Triple(match, "hvac.tempC=${vehicleCommand.celsius}", hvacSummary(observed))
            }

            is VehicleCommand.SetFanLevel -> {
                val match = observed.hvac.fanLevel == vehicleCommand.level
                Triple(match, "hvac.fan=${vehicleCommand.level}", hvacSummary(observed))
            }
        }
        return VerificationReport(
            status = if (ok) VerificationStatus.MATCHED else VerificationStatus.MISMATCHED,
            expectedSummary = "${command::class.simpleName}:$expected",
            observedSummary = observedSummary,
        )
    }

    private fun navSummary(state: ObservedVehicleState) =
        "navigation.active=${state.navigation.active},label=${state.navigation.destinationLabel}"

    private fun mediaSummary(state: ObservedVehicleState) =
        "media.playing=${state.media.playing},title=${state.media.title},volume=${state.media.volumePercent}"

    private fun phoneSummary(state: ObservedVehicleState) =
        "phone.inCall=${state.phone.inCall},number=${state.phone.remoteNumber}"

    private fun hvacSummary(state: ObservedVehicleState) =
        "hvac.tempC=${state.hvac.cabinTemperatureCelsius},fan=${state.hvac.fanLevel}"
}
