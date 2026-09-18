package com.novadrive.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Keeps `config/capabilities.yaml` honest against the code.
 *
 * The registry is what scripts and future agents read to answer "what does this app actually
 * support?". Prose drifts; this test is why the answer stays true. It deliberately parses the YAML
 * with a few regexes rather than adding a YAML dependency to a test module — the file's shape is
 * fixed and owned by this repository.
 *
 * What it cannot check: whether a `verified:` level is *earned*. Upgrading `unit` to `device`
 * without device evidence is a human dishonesty, not a mechanical one; `ACCEPTANCE_TESTS.md` says
 * what each level requires.
 */
class CapabilityContractTest {
    private val root = File(System.getProperty("nova.repo.root") ?: error("nova.repo.root not set"))

    /** Comments are stripped: the file documents its own schema, and `tool:` appears in the prose. */
    private val registry: String by lazy {
        File(root, "config/capabilities.yaml").also {
            assertTrue(it.isFile, "config/capabilities.yaml is the canonical capability registry")
        }.readLines()
            .filterNot { it.trimStart().startsWith("#") }
            .joinToString(separator = " ")
    }

    private val declaredTools: Set<String> by lazy {
        val protocol = File(root, "app/src/main/kotlin/com/novadrive/app/voice/BaiduFlexProtocol.kt").readText()
        Regex("name = \"([a-z_]+)\"").findAll(protocol).map { it.groupValues[1] }.toSet() +
            Regex("const val [A-Z_]+ = \"([a-z_]+)\"").findAll(protocol).map { it.groupValues[1] }.toSet()
    }

    /** `tool: null` is the registry's way of saying a capability has no function call. */
    private val registryTools: Set<String> by lazy {
        Regex("tool: ([a-z_]+)").findAll(registry).map { it.groupValues[1] }.toSet() - "null"
    }

    @Test
    fun everyToolTheModelCanCallIsInTheRegistry() {
        val missing = declaredTools - registryTools - ARGUMENT_VALUES
        assertTrue(missing.isEmpty()) {
            "Declared to the model but absent from config/capabilities.yaml: $missing. " +
                "A capability the model can invoke but the registry does not list is exactly how " +
                "capability drift starts."
        }
    }

    @Test
    fun everyToolInTheRegistryIsRealAndRouted() {
        val dispatcher = File(root, "app/src/main/kotlin/com/novadrive/app/AndroidToolDispatcher.kt").readText()
        val handlers = listOf(
            "app/src/main/kotlin/com/novadrive/app/vehicle/ClimateToolHandler.kt",
            "app/src/main/kotlin/com/novadrive/app/vision/CameraQuestionHandler.kt",
        ).joinToString(separator = " ") { File(root, it).readText() }
        val routed = Regex("\"([a-z_]+)\" ->").findAll(dispatcher).map { it.groupValues[1] }.toSet() +
            Regex("([A-Z_]{4,}) ->").findAll(dispatcher).map { it.groupValues[1].lowercase() }.toSet() +
            Regex("TOOL = \"([a-z_]+)\"").findAll(handlers).map { it.groupValues[1] }.toSet()
        val phantom = registryTools.filter { it !in declaredTools }
        assertTrue(phantom.isEmpty()) { "Registry lists tools the model is never offered: $phantom" }
        val unrouted = registryTools.filter { it !in routed }
        assertTrue(unrouted.isEmpty()) { "Registry lists tools nothing executes: $unrouted" }
    }

    @Test
    fun unsupportedRequestsNameTheClassifierThatCatchesThem() {
        // An "unsupported" entry is only true if something deterministic recognises the request.
        val guard = File(root, "app/src/main/kotlin/com/novadrive/app/voice/ActionClaimGuard.kt").readText()
        Regex("recognised_by: ([A-Za-z.]+)").findAll(registry).map { it.groupValues[1] }.forEach { ref ->
            val member = ref.substringAfterLast('.')
            assertTrue(guard.contains(member)) {
                "config/capabilities.yaml points at $ref, which does not exist in ActionClaimGuard"
            }
        }
    }

    @Test
    fun everyVerificationLevelIsOneWeDefined() {
        val levels = Regex("verified: ([a-z]+)").findAll(registry).map { it.groupValues[1] }.toSet()
        val known = setOf("implemented", "unit", "device", "human", "unsupported")
        assertTrue((levels - known).isEmpty()) {
            "Unknown verification levels ${levels - known}; allowed: $known"
        }
    }

    @Test
    fun theProseAndTheRegistryAgreeOnWhatIsUnsupported() {
        val prose = File(root, "docs/CAPABILITIES.md").readText()
        // The two documents may word things differently, but a capability the registry calls
        // unsupported must not be advertised as supported in the prose table.
        listOf("volume_control" to "音量", "realtime_weather_traffic_news" to "weather").forEach { (key, _) ->
            assertTrue(registry.contains(key)) { "$key missing from the registry" }
        }
        assertTrue(prose.contains("Not supported")) {
            "docs/CAPABILITIES.md must keep its unsupported section; the registry references it"
        }
    }

    private companion object {
        val ARGUMENT_VALUES = setOf(
            "maps", "settings", "play", "stop", "silent", "spoken", "action", "value",
            "question", "destination", "index", "preference", "name", "app", "mode",
            "power_on", "power_off", "set_temperature", "set_fan", "temperature_up",
            "temperature_down", "fan_up", "fan_down",
        )
    }
}
