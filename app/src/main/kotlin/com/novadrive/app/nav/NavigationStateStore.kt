package com.novadrive.app.nav

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** v2 section 14: navigation callbacks update this store; the UI must not parse Amap callbacks directly. */
class NavigationStateStore {
    private val _phase = MutableStateFlow(NavigationPhase.IDLE)
    val phase: StateFlow<NavigationPhase> = _phase.asStateFlow()

    private val _destination = MutableStateFlow<Destination?>(null)
    val destination: StateFlow<Destination?> = _destination.asStateFlow()

    fun update(phase: NavigationPhase, destination: Destination? = this.destination.value) {
        _phase.value = phase
        _destination.value = destination
    }

    fun reset() {
        _phase.value = NavigationPhase.IDLE
        _destination.value = null
    }
}
