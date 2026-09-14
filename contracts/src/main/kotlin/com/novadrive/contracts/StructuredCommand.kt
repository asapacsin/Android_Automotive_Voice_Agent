package com.novadrive.contracts

/**
 * Typed structured function calls produced by a provider-neutral S2S/NLU boundary.
 * Natural-language strings are not parsed in this module.
 */
sealed interface StructuredCommand {
    val correlationId: String

    data class StartNavigation(
        override val correlationId: String,
        val destination: Destination,
    ) : StructuredCommand

    data class CancelNavigation(
        override val correlationId: String,
    ) : StructuredCommand

    data class PlayMedia(
        override val correlationId: String,
        val query: String,
    ) : StructuredCommand

    data class PauseMedia(
        override val correlationId: String,
    ) : StructuredCommand

    data class SetVolume(
        override val correlationId: String,
        val volumePercent: Int,
    ) : StructuredCommand

    data class PlaceCall(
        override val correlationId: String,
        val query: ContactQuery,
        val blocked: Boolean = false,
    ) : StructuredCommand

    data class EndCall(
        override val correlationId: String,
    ) : StructuredCommand

    data class SetCabinTemperature(
        override val correlationId: String,
        val celsius: Double,
    ) : StructuredCommand

    data class SetFanLevel(
        override val correlationId: String,
        val level: Int,
    ) : StructuredCommand
}
