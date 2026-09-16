package com.novadrive.simulator

import com.novadrive.contracts.AdapterOutcome
import com.novadrive.contracts.ContactMatchKind
import com.novadrive.contracts.ContactQuery
import com.novadrive.contracts.ContactResolution
import com.novadrive.contracts.HvacObservedState
import com.novadrive.contracts.MediaObservedState
import com.novadrive.contracts.NavigationObservedState
import com.novadrive.contracts.NavigationProviderKind
import com.novadrive.contracts.ObservedVehicleState
import com.novadrive.contracts.PhoneObservedState
import com.novadrive.contracts.ResolvedContact
import com.novadrive.contracts.VehicleSafetySnapshot
import com.novadrive.vehicle.ClimateState
import com.novadrive.vehicle.MediaProvider
import com.novadrive.vehicle.NavigationProvider
import com.novadrive.vehicle.PhoneProvider
import com.novadrive.vehicle.VehicleActionResult
import com.novadrive.vehicle.VehicleCommand
import com.novadrive.vehicle.VehiclePort
import com.novadrive.vehicle.toAdapterOutcome

data class SimulatorFaults(
    var failNextExecution: Boolean = false,
    var desyncNextObservation: Boolean = false,
)

class InMemoryVehicleSimulator(
    private val faults: SimulatorFaults = SimulatorFaults(),
    contacts: List<ResolvedContact> = defaultContacts(),
    var callsBlocked: Boolean = false,
    /** Climate lives in the shared simulated climate backend, so there is one source of truth. */
    val climate: SimulatedVehicleControl = SimulatedVehicleControl(
        ClimateState(powerOn = false, targetTemperatureCelsius = 24.0, fanLevel = 3),
    ),
) : VehiclePort, NavigationProvider, MediaProvider, PhoneProvider {
    override val kind: NavigationProviderKind = NavigationProviderKind.FAKE

    private var navigation = NavigationObservedState(active = false)
    private var media = MediaObservedState(playing = false, title = null, volumePercent = 40)
    private var phone = PhoneObservedState(inCall = false)
    private var published = snapshot()
    private val directory = contacts

    override fun execute(command: VehicleCommand): AdapterOutcome {
        if (faults.failNextExecution) {
            faults.failNextExecution = false
            return AdapterOutcome.Failed("simulator.injected_execution_failure")
        }
        when (command) {
            is VehicleCommand.StartRoute ->
                navigation = NavigationObservedState(
                    active = true,
                    destinationLabel = command.destination.label,
                    destinationCoordinate = command.destination.coordinate,
                )
            is VehicleCommand.CancelRoute ->
                navigation = NavigationObservedState(active = false)
            is VehicleCommand.Play ->
                media = media.copy(playing = true, title = command.query)
            is VehicleCommand.Pause ->
                media = media.copy(playing = false)
            is VehicleCommand.SetVolume ->
                media = media.copy(volumePercent = command.volumePercent)
            is VehicleCommand.Dial ->
                phone = PhoneObservedState(
                    inCall = true,
                    remoteNumber = command.contact.phoneNumber,
                    remoteDisplayName = command.contact.displayName,
                )
            is VehicleCommand.HangUp ->
                phone = PhoneObservedState(inCall = false)
            is VehicleCommand.SetCabinTemperature ->
                climate.applySetTemperature(command.celsius).failureOrNull()?.let { return it }
            is VehicleCommand.SetFanLevel ->
                climate.applySetFan(command.level).failureOrNull()?.let { return it }
        }
        if (!faults.desyncNextObservation) {
            published = snapshot()
        } else {
            faults.desyncNextObservation = false
        }
        return AdapterOutcome.Applied
    }

    override fun observe(): ObservedVehicleState = published

    override fun safetySnapshot(): VehicleSafetySnapshot =
        observe().safetySnapshot(callsBlocked = callsBlocked)

    override fun observeNavigation(): NavigationObservedState = published.navigation

    override fun observeMedia(): MediaObservedState = published.media

    override fun observePhone(): PhoneObservedState = published.phone

    fun observeHvac(): HvacObservedState = published.hvac

    override fun resolveContact(query: ContactQuery): ContactResolution = resolve(query)

    override fun resolve(query: ContactQuery): ContactResolution {
        val matches = directory.filter { contact ->
            query.phoneNumber?.let { it == contact.phoneNumber } == true ||
                query.spokenName?.let { name ->
                    contact.displayName == name || contact.aliases.contains(name)
                } == true ||
                query.alias?.let { contact.aliases.contains(it) } == true ||
                query.pinyin?.let { it.equals(contact.pinyin, ignoreCase = true) } == true
        }.distinctBy { it.contactId }
        return when (matches.size) {
            0 -> ContactResolution(ContactMatchKind.NONE)
            1 -> ContactResolution(ContactMatchKind.UNIQUE, matches)
            else -> ContactResolution(ContactMatchKind.AMBIGUOUS, matches)
        }
    }

    private fun snapshot() =
        ObservedVehicleState(navigation, media, phone, climate.climateState.value.toObserved())

    private fun VehicleActionResult.failureOrNull(): AdapterOutcome? =
        toAdapterOutcome().takeIf { it is AdapterOutcome.Failed }

    companion object {
        fun defaultContacts(): List<ResolvedContact> = listOf(
            ResolvedContact("c-zhang-wei-1", "张伟", "13800001111", aliases = listOf("阿伟"), pinyin = "zhang wei"),
            ResolvedContact("c-zhang-wei-2", "张伟", "13800002222", aliases = listOf("小伟"), pinyin = "zhang wei"),
            ResolvedContact("c-li-na", "李娜", "13900003333", aliases = listOf("娜娜"), pinyin = "li na"),
        )
    }
}
