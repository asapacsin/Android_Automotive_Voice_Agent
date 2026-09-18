package com.novadrive.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The rules in `docs/INVARIANTS.md` that a machine can check.
 *
 * `DependencyBoundaryTest` guards the module graph and `SecretScanTest` the credentials; this file
 * holds the rules that are about *ownership* — who is allowed to execute, who may touch a vendor
 * SDK, and what may be held back from the driver. Each failure message names the invariant, so a
 * deliberate change is made explicit here rather than argued in a review comment.
 */
class ArchitectureRulesTest {
    private val root = File(System.getProperty("nova.repo.root") ?: error("nova.repo.root not set"))

    private fun kotlinFiles(path: String): List<File> =
        File(root, path).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun text(path: String): String = File(root, path).also {
        assertTrue(it.isFile, "missing file: $path")
    }.readText()

    // ---- I-9: vendor types stay inside their adapter ----

    @Test
    fun onlyOneFileImportsAmap() {
        val importers = kotlinFiles("app/src/main").filter { file ->
            file.readLines().any { it.trim().startsWith("import com.amap") }
        }.map { it.relativeTo(root).path.replace('\\', '/') }
        // AmapNaviViewHost owns the SDK; NavigationTraceListener implements its listener interface.
        val allowed = setOf(
            "app/src/main/kotlin/com/novadrive/app/nav/amap/AmapNaviViewHost.kt",
            "app/src/main/kotlin/com/novadrive/app/nav/amap/NavigationTraceListener.kt",
            "app/src/main/kotlin/com/novadrive/app/nav/amap/NoOpNaviViewListener.kt",
            "app/src/main/kotlin/com/novadrive/app/nav/amap/AmapPrivacyCompliance.kt",
        )
        val unexpected = importers - allowed
        assertTrue(unexpected.isEmpty()) {
            "INVARIANT I-9: com.amap may only be imported inside app/nav/amap. Found: $unexpected"
        }
    }

    // ---- I-6: the UI must not execute vehicle or navigation actions directly ----

    @Test
    fun uiDoesNotReachIntoExecution() {
        // Recorded exceptions, with their debt entry. Adding to this set needs a reason in the
        // commit message; the point of the test is that a NEW one cannot appear silently.
        val knownDebt = setOf(
            "app/src/main/kotlin/com/novadrive/app/ui/BottomBarView.kt", // TECH_DEBT D-3
            "app/src/main/kotlin/com/novadrive/app/ui/AssistantNavigationScreen.kt", // TECH_DEBT D-3
        )
        val executors = listOf("BundledMusicPlayer", "VehicleControlProvider", "SafeAndroidActionExecutor")
        val offenders = kotlinFiles("app/src/main/kotlin/com/novadrive/app/ui")
            .filter { file -> executors.any { file.readText().contains(it) } }
            .map { it.relativeTo(root).path.replace('\\', '/') }
            .toSet() - knownDebt
        assertTrue(offenders.isEmpty()) {
            "INVARIANT I-6: UI must reach executors through the same ports as the voice path. " +
                "New offenders: $offenders (see docs/TECH_DEBT.md D-3)"
        }
    }

    // ---- I-5: only what the driver hears and reads may be held back ----

    @Test
    fun onlyAudioAndSubtitleMayBeHeld() {
        val client = text("app/src/main/kotlin/com/novadrive/app/voice/BaiduFlexClient.kt")
        assertTrue(client.contains("if (!holdable) return false")) {
            "INVARIANT I-5: everything that is not reply audio or its subtitle must pass through"
        }
        // A tool call, an error or the driver's transcript must never be holdable.
        val holdableBlock = client.substringAfter("val holdable =").substringBefore("if (!holdable)")
        listOf("ToolCall", "Error", "UserTranscript").forEach { forbidden ->
            assertTrue(!holdableBlock.contains(forbidden)) {
                "INVARIANT I-5 / I-4: $forbidden must never be held back from the session"
            }
        }
    }

    // ---- I-1 / I-2: execution results, not sentences, decide what is claimed ----

    @Test
    fun theTruthAboutExecutionComesFromTheExecutor() {
        val dispatcher = text("app/src/main/kotlin/com/novadrive/app/AndroidToolDispatcher.kt")
        assertTrue(dispatcher.contains("\"ok\", false")) {
            "INVARIANT I-1: a failed action must reach the model as ok=false"
        }
        assertTrue(dispatcher.contains("ToolFailureAdvice.forCode(code)")) {
            "INVARIANT I-2: a refusal must carry what to say, not rely on the tool description"
        }
        val guard = text("app/src/main/kotlin/com/novadrive/app/voice/ActionClaimGuard.kt")
        assertTrue(guard.contains("fun isUnsupportedRequest") && guard.contains("fun isRealtimeInfoRequest")) {
            "INVARIANT I-2 / I-3: the unsupported and no-source request categories live here"
        }
    }

    // ---- I-8: diagnostics carry durations and distances, never positions ----

    @Test
    fun locationIsNotLogged() {
        val offenders = mutableListOf<String>()
        kotlinFiles("app/src/main").forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                if (!line.contains("DebugVoiceLog.log")) return@forEachIndexed
                // A log line that interpolates a coordinate-shaped property.
                val leaks = Regex("\\$\\{?[A-Za-z.]*(latitude|longitude|\\blat\\b|\\blon\\b)").containsMatchIn(line)
                if (leaks) offenders += "${file.relativeTo(root).path.replace('\\', '/')}:${index + 1}"
            }
        }
        assertTrue(offenders.isEmpty()) {
            "INVARIANT I-8: no coordinate may be logged. Found: $offenders"
        }
    }

    // ---- maintenance: files do not grow without somebody noticing ----

    @Test
    fun noFileGrowsWithoutNotice() {
        // Budgets recorded 2026-09-18. Raising one is allowed — with a reason in the commit
        // message. See docs/AGENT_MAINTENANCE.md step 4 and docs/TECH_DEBT.md D-1.
        val budgets = mapOf(
            "app/src/main/kotlin/com/novadrive/app/voice/BaiduFlexClient.kt" to 900,
            "app/src/main/kotlin/com/novadrive/app/nav/amap/AmapNaviViewHost.kt" to 750,
            "app/src/main/kotlin/com/novadrive/app/AndroidToolDispatcher.kt" to 450,
            "app/src/main/kotlin/com/novadrive/app/voice/VoiceSessionController.kt" to 500,
            "app/src/main/kotlin/com/novadrive/app/nav/EmbeddedNavigationController.kt" to 500,
        )
        val over = budgets.mapNotNull { (path, budget) ->
            val lines = File(root, path).readLines().size
            if (lines > budget) "$path is $lines lines (budget $budget)" else null
        }
        assertTrue(over.isEmpty()) {
            "These files grew past their recorded budget: $over — extract, or raise the budget " +
                "deliberately and say why (docs/AGENT_MAINTENANCE.md)"
        }
    }

    // ---- the harness itself must stay usable by a fresh agent ----

    @Test
    fun theHarnessDocumentsExistAndPointAtEachOther() {
        listOf(
            "AGENTS.md",
            "docs/ARCHITECTURE.md",
            "docs/INVARIANTS.md",
            "docs/CAPABILITIES.md",
            "docs/TECH_DEBT.md",
            "docs/AGENT_MAINTENANCE.md",
        ).forEach { path ->
            assertTrue(File(root, path).isFile, "harness document missing: $path")
        }
        val agents = text("AGENTS.md")
        listOf("docs/ARCHITECTURE.md", "docs/INVARIANTS.md", "docs/CAPABILITIES.md").forEach {
            assertTrue(agents.contains(it), "AGENTS.md must link $it — it is the map")
        }
    }

    // ---- every declared tool is routed, and every routed tool is declared ----

    @Test
    fun declaredToolsAndDispatchedToolsAgree() {
        val protocol = text("app/src/main/kotlin/com/novadrive/app/voice/BaiduFlexProtocol.kt")
        val dispatcher = text("app/src/main/kotlin/com/novadrive/app/AndroidToolDispatcher.kt")
        val declared = Regex("name = \"([a-z_]+)\"").findAll(protocol).map { it.groupValues[1] }.toSet() +
            Regex("const val [A-Z_]+ = \"([a-z_]+)\"").findAll(protocol).map { it.groupValues[1] }.toSet()
        // A tool reaches an executor either as a literal branch, via a constant branch, or through
        // a handler that declares TOOL = "name". All three count as routed.
        val handlers = listOf(
            "app/src/main/kotlin/com/novadrive/app/vehicle/ClimateToolHandler.kt",
            "app/src/main/kotlin/com/novadrive/app/vision/CameraQuestionHandler.kt",
        ).joinToString(separator = " ") { text(it) }
        val routed = Regex("\"([a-z_]+)\" ->").findAll(dispatcher).map { it.groupValues[1] }.toSet() +
            Regex("([A-Z_]{4,}) ->").findAll(dispatcher).map { it.groupValues[1].lowercase() }.toSet() +
            Regex("TOOL = \"([a-z_]+)\"").findAll(handlers).map { it.groupValues[1] }.toSet()
        val capabilities = text("docs/CAPABILITIES.md")
        val unrouted = declared.filter { it !in routed && it !in ARGUMENT_VALUES }
        assertTrue(unrouted.isEmpty()) {
            "Declared to the model but not dispatched anywhere: $unrouted"
        }
        val undocumented = declared.filter { it !in ARGUMENT_VALUES && !capabilities.contains(it) }
        assertTrue(undocumented.isEmpty()) {
            "INVARIANT I-10: every tool must appear in docs/CAPABILITIES.md. Missing: $undocumented"
        }
    }

    private companion object {
        /** Enum values and argument names that share the regex shape of a tool name. */
        val ARGUMENT_VALUES = setOf(
            "maps", "settings", "play", "stop", "silent", "spoken", "action", "value",
            "question", "destination", "index", "preference", "name", "app", "mode",
            "power_on", "power_off", "set_temperature", "set_fan", "temperature_up",
            "temperature_down", "fan_up", "fan_down",
        )
    }
}
