package com.novadrive.vehicle

import com.novadrive.contracts.AdapterOutcome
import com.novadrive.contracts.ContactQuery
import com.novadrive.contracts.ContactResolution
import com.novadrive.contracts.Destination
import com.novadrive.contracts.HvacObservedState
import com.novadrive.contracts.MediaObservedState
import com.novadrive.contracts.NavigationObservedState
import com.novadrive.contracts.NavigationProviderKind
import com.novadrive.contracts.ObservedVehicleState
import com.novadrive.contracts.PhoneObservedState
import com.novadrive.contracts.ResolvedContact
import com.novadrive.contracts.VehicleSafetySnapshot

sealed interface VehicleCommand {
    data class StartRoute(val destination: Destination) : VehicleCommand
    data object CancelRoute : VehicleCommand
    data class Play(val query: String) : VehicleCommand
    data object Pause : VehicleCommand
    data class SetVolume(val volumePercent: Int) : VehicleCommand
    data class Dial(val contact: ResolvedContact) : VehicleCommand
    data object HangUp : VehicleCommand
    data class SetCabinTemperature(val celsius: Double) : VehicleCommand
    data class SetFanLevel(val level: Int) : VehicleCommand
}

interface NavigationProvider {
    val kind: NavigationProviderKind
    fun execute(command: VehicleCommand): AdapterOutcome
    fun observeNavigation(): NavigationObservedState
}

interface MediaProvider {
    fun execute(command: VehicleCommand): AdapterOutcome
    fun observeMedia(): MediaObservedState
}

interface PhoneProvider {
    fun resolve(query: ContactQuery): ContactResolution
    fun execute(command: VehicleCommand): AdapterOutcome
    fun observePhone(): PhoneObservedState
}

interface HvacController {
    fun execute(command: VehicleCommand): AdapterOutcome
    fun observeHvac(): HvacObservedState
}

interface VehiclePort {
    fun execute(command: VehicleCommand): AdapterOutcome
    fun observe(): ObservedVehicleState
    fun resolveContact(query: ContactQuery): ContactResolution
    fun safetySnapshot(): VehicleSafetySnapshot = observe().safetySnapshot()
}

class CompositeVehiclePort(
    private val navigation: NavigationProvider,
    private val media: MediaProvider,
    private val phone: PhoneProvider,
    private val hvac: HvacController,
) : VehiclePort {
    override fun execute(command: VehicleCommand): AdapterOutcome =
        when (command) {
            is VehicleCommand.StartRoute,
            is VehicleCommand.CancelRoute,
            -> navigation.execute(command)

            is VehicleCommand.Play,
            is VehicleCommand.Pause,
            is VehicleCommand.SetVolume,
            -> media.execute(command)

            is VehicleCommand.Dial,
            is VehicleCommand.HangUp,
            -> phone.execute(command)

            is VehicleCommand.SetCabinTemperature,
            is VehicleCommand.SetFanLevel,
            -> hvac.execute(command)
        }

    override fun observe(): ObservedVehicleState =
        ObservedVehicleState(
            navigation = navigation.observeNavigation(),
            media = media.observeMedia(),
            phone = phone.observePhone(),
            hvac = hvac.observeHvac(),
        )

    override fun resolveContact(query: ContactQuery): ContactResolution = phone.resolve(query)
}
