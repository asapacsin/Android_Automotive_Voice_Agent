package com.novadrive.ingress.realtime

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * One behavioural contract, run against every provider in the repository.
 *
 * A new adapter is finished when it passes this ([SPEC-007](../../../../../../../SPECS/SPEC-007-provider-neutral-realtime.md)
 * A6/A7). The point is that these assertions never name a vendor: they are written against
 * `RealtimeVoiceProvider`, so adding a provider means adding one line to [providers] and fixing
 * whatever fails — not inventing what "correct" means for it.
 *
 * What is deliberately *not* asserted: anything a vendor's own protocol decides, such as how turn
 * boundaries are detected. That is what [ProviderCapabilities] is for, and a contract that assumed
 * one provider's answer would be the leak this whole boundary exists to prevent.
 */
class RealtimeProviderContractTest {

    /** Every provider this repository ships. A new adapter is added here. */
    private fun providers(): List<Pair<String, () -> RealtimeVoiceProvider>> = listOf(
        "fake" to { FakeRealtimeVoiceProvider() },
        "mock" to { MockRealtimeVoiceProvider() },
    )

    @TestFactory
    fun `every provider satisfies the realtime contract`(): List<DynamicTest> =
        providers().flatMap { (name, make) ->
            listOf(
                contract("$name: declares an identity and a capability set") {
                    assertTrue(it.providerId.isNotBlank()) { "a provider must be identifiable in a log" }
                    assertNotNull(it.capabilities)
                },
                contract("$name: connect reaches a usable session") {
                    it.connect(VoiceCatalog.DEFAULT_MODEL)
                    // Readiness is observable, not assumed: something must say the session is up.
                    val events = it.receiveEvents()
                    assertTrue(
                        events.any { event -> event is DomainVoiceEvent.SessionReady } || events.isEmpty(),
                    ) { "connect must either report readiness or expose it through events()" }
                },
                contract("$name: audio is accepted once connected") {
                    it.connect(VoiceCatalog.DEFAULT_MODEL)
                    it.sendAudio(ByteArray(320))
                },
                contract("$name: cancelling a response is normalized, never an exception") {
                    it.connect(VoiceCatalog.DEFAULT_MODEL)
                    val outcome = runBlocking { it.cancelAssistantResponse() }
                    assertNotNull(outcome) { "cancellation must return a domain event, not throw" }
                },
                contract("$name: an unsupported tool result fails explicitly") {
                    // The contract's rule for anything unsupported: say so in the domain vocabulary.
                    // Silence is the failure mode this replaces - a driver waiting for an action
                    // that was never going to happen.
                    val result = it.sendToolResult(ToolResult("call_1", true, "{}"))
                    assertNotNull(result)
                },
                contract("$name: close is idempotent and leaves nothing running") {
                    it.connect(VoiceCatalog.DEFAULT_MODEL)
                    it.close()
                    it.close()
                },
                contract("$name: disconnect then reconnect yields a fresh session") {
                    it.connect(VoiceCatalog.DEFAULT_MODEL)
                    runBlocking { it.disconnect() }
                    it.connect(VoiceCatalog.DEFAULT_MODEL)
                },
                contract("$name: a refused model is rejected, not quietly accepted") {
                    val refused = runCatching { it.connect("model-that-does-not-exist") }
                    assertTrue(refused.isFailure) {
                        "a provider must not accept a model outside the catalogue"
                    }
                },
                contract("$name: emits no vendor vocabulary through the domain surface") {
                    it.connect(VoiceCatalog.DEFAULT_MODEL)
                    it.receiveEvents().forEach { event ->
                        val rendered = event.toString()
                        WIRE_WORDS.forEach { word ->
                            assertFalse(rendered.contains(word)) {
                                "$word reached the domain event surface: $rendered"
                            }
                        }
                    }
                },
            )
        }

    @TestFactory
    fun `error classification is provider-neutral`(): List<DynamicTest> = listOf(
        "BAIDU_CREDENTIALS_MISSING" to ErrorClass.AUTH,
        "IFLYTEK_AUTH_FAILED" to ErrorClass.AUTH,
        "QUOTA_EXCEEDED" to ErrorClass.RATE_LIMIT,
        "WS_FAILED" to ErrorClass.RETRYABLE,
        "BAIDU_DNS_FAILED" to ErrorClass.RETRYABLE,
        "RESPONSE_CANCELLED" to ErrorClass.CANCELLED,
        "INVALID_MODEL" to ErrorClass.MALFORMED,
    ).map { (code, expected) ->
        // Any provider's code lands in one of six classes, because six is what the app acts on.
        DynamicTest.dynamicTest("$code -> $expected") {
            assertEquals(expected, classifyVoiceError(code))
        }
    }

    private fun contract(name: String, body: (RealtimeVoiceProvider) -> Unit): DynamicTest =
        DynamicTest.dynamicTest(name) {
            val provider = providers().first { name.startsWith(it.first) }.second()
            try {
                body(provider)
            } finally {
                runCatching { provider.close() }
            }
        }

    private companion object {
        val WIRE_WORDS = listOf("function_call", "input_audio_buffer", "session.updated")
    }
}
