package com.novadrive.vehicle

import com.novadrive.contracts.AdapterOutcome
import com.novadrive.contracts.ContactQuery
import com.novadrive.contracts.ContactResolution
import com.novadrive.contracts.Destination
import com.novadrive.contracts.MediaObservedState
import com.novadrive.contracts.NavigationObservedState
import com.novadrive.contracts.NavigationProviderKind
import com.novadrive.contracts.ObservedVehicleState
import com.novadrive.contracts.PhoneObservedState
import com.novadrive.contracts.ResolvedContact
import com.novadrive.contracts.VehicleSafetySnapshot
import kotlinx.coroutines.runBlocking

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
    private val climate: VehicleControlPort,
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

            // The legacy synchronous command path routes climate through the same port the
            // voice tools use, so there is exactly one climate abstraction.
            is VehicleCommand.SetCabinTemperature ->
                runBlocking { climate.setCabinTemperature(command.celsius) }.toAdapterOutcome()
            is VehicleCommand.SetFanLevel ->
                runBlocking { climate.setFanLevel(command.level) }.toAdapterOutcome()
        }

    override fun observe(): ObservedVehicleState =
        ObservedVehicleState(
            navigation = navigation.observeNavigation(),
            media = media.observeMedia(),
            phone = phone.observePhone(),
            hvac = climate.climateState.value.toObserved(),
        )

    override fun resolveContact(query: ContactQuery): ContactResolution = phone.resolve(query)
}

fun VehicleActionResult.toAdapterOutcome(): AdapterOutcome =
    when (this) {
        is VehicleActionResult.Success -> AdapterOutcome.Applied
        is VehicleActionResult.InvalidArgument -> AdapterOutcome.Failed("invalid_argument:$reason")
        is VehicleActionResult.Unsupported -> AdapterOutcome.Failed("unsupported:$feature")
        is VehicleActionResult.Unavailable -> AdapterOutcome.Failed("unavailable:$reason")
        is VehicleActionResult.PermissionDenied -> AdapterOutcome.Failed("permission_denied:$reason")
        is VehicleActionResult.Failure -> AdapterOutcome.Failed("failure:$reason")
    }
