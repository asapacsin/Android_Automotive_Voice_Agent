package com.novadrive.ingress

import com.novadrive.contracts.OrchestrationResult
import com.novadrive.contracts.StructuredCommand

/**
 * Only typed structured commands may enter orchestration.
 * Speech-native S2S adapters must implement this boundary and must never
 * import simulator, provider SDK, AAOS, or VHAL types.
 */
fun interface StructuredCommandIngress {
    fun submit(command: StructuredCommand): OrchestrationResult
}

/**
 * Provider-neutral realtime speech-to-speech session.
 * Compatible with Mainland deployment: no GMS, no implied Google STT/TTS.
 * Implementations talk to a [StructuredCommandIngress], not to vehicle adapters.
 */
interface SpeechToSpeechPort {
    val providerId: String
    fun startSession(ingress: StructuredCommandIngress): SpeechSession
}

interface SpeechSession {
    fun onStructuredFunctionCall(command: StructuredCommand): OrchestrationResult
    fun close()
}

/**
 * Bootstrap replay adapter: tests and the local demo inject already-typed commands.
 * This is not a natural-language parser.
 */
class ReplaySpeechToSpeechPort(
    override val providerId: String = "replay.local",
) : SpeechToSpeechPort {
    override fun startSession(ingress: StructuredCommandIngress): SpeechSession =
        object : SpeechSession {
            override fun onStructuredFunctionCall(command: StructuredCommand): OrchestrationResult =
                ingress.submit(command)

            override fun close() = Unit
        }
}
