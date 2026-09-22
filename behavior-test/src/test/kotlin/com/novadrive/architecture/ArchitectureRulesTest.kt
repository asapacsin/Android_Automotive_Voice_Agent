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
            "app/src/main/kotlin/com/novadrive/app/nav/amap/AmapDrivingPresentation.kt",
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
        // Empty since 2026-09-19: D-3 is resolved, both screens go through ScreenControls. A file
        // added back here needs a debt entry and a reason in the commit message.
        val knownDebt = emptySet<String>()
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
        assertTrue(client.contains("private fun holdOrEmit")) {
            "INVARIANT I-5: there must be exactly one place that decides what is held"
        }
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

    // ---- I-1: only execution evidence may establish that an action happened ----

    @Test
    fun executionProofOwnsActionClaims() {
        val client = text("app/src/main/kotlin/com/novadrive/app/voice/BaiduFlexClient.kt")
        // Proof enters the system at exactly one place: the tool result.
        assertTrue(client.contains("onExecutionResult(output)")) {
            "INVARIANT I-1: sendFunctionResult is the only source of execution evidence"
        }
        val turn = text("app/src/main/kotlin/com/novadrive/app/voice/DriverTurn.kt")
        assertTrue(turn.contains("AWAITING_EXECUTION_PROOF")) {
            "INVARIANT I-1: an action reply must be able to wait for proof"
        }
        assertTrue(turn.contains("if (!ok) {")) {
            "INVARIANT I-1: a failed execution must not count as proof"
        }
        // Per-utterance state has one owner; the loose flags it replaced must not come back.
        listOf(
            "userSpokeThisTurn", "toolCalledThisTurn", "unsupportedRequestThisTurn",
            "realtimeInfoThisTurn", "holdingTurn",
        ).forEach { flag ->
            assertTrue(!client.contains(flag)) {
                "INVARIANT I-1 / I-10: per-turn state belongs to DriverTurn, not to a `$flag` flag"
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

    // ---- I-11 / D-2: the persona may shape tone, never state a false capability ----

    @Test
    fun thePersonaDoesNotDescribeAnArchitectureWeNoLongerHave() {
        val persona = text("app/src/main/kotlin/com/novadrive/app/PersonaProfiles.kt")
        // Under the deep-link model (ADR-003) the driver really did have to leave Amap themselves.
        // ADR-007 embedded the SDK, so exit_navigation_mode ends the drive - and the persona was
        // still telling the model to say otherwise, while FLEX_TOOL_RULE said the opposite two
        // paragraphs below. A persona that contradicts itself about what the product can do is
        // worse than one that says nothing.
        val stale = listOf(
            "高德地图的导航需要用户自己退出",
            "需要用户自己退出",
            "打开高德地图",
            "切换到高德",
        ).filter { persona.contains(it) }
        assertTrue(stale.isEmpty()) {
            "INVARIANT I-11 / TECH_DEBT D-2: the persona still describes the external-Amap " +
                "architecture that ADR-007 replaced: $stale"
        }
    }

    // ---- maintenance: files do not grow without somebody noticing ----

    @Test
    fun noFileGrowsWithoutNotice() {
        // Budgets recorded 2026-09-18; AmapNaviViewHost raised again 2026-09-21 after desk-origin
        // + code-3 explicit-start retry (P31 E2E unblock) landed in the host (~883 lines).
        // Raising one is allowed — with a reason in the commit
        // message. See docs/AGENT_MAINTENANCE.md step 4 and docs/TECH_DEBT.md D-1.
        val budgets = mapOf(
            "app/src/main/kotlin/com/novadrive/app/voice/BaiduFlexClient.kt" to 900,
            "app/src/main/kotlin/com/novadrive/app/nav/amap/AmapNaviViewHost.kt" to 900,
            "app/src/main/kotlin/com/novadrive/app/AndroidToolDispatcher.kt" to 460,
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

    // ---- capability truth has one owner: the catalog, not a keyword list ----

    @Test
    fun unsupportedClassificationConsultsTheCatalogBeforeAnyWordList() {
        val guard = text("app/src/main/kotlin/com/novadrive/app/voice/ActionClaimGuard.kt")
        val body = guard.substringAfter("fun isUnsupportedRequest(").substringBefore("fun fallbackUnsupportedHeuristic")
        assertTrue(body.contains("catalog.isSupported")) {
            "isUnsupportedRequest must ask CapabilityCatalog; keyword lists are not capability truth"
        }
        val catalogAt = body.indexOf("catalog.isSupported")
        val fallbackAt = body.indexOf("fallbackUnsupportedHeuristic")
        assertTrue(catalogAt >= 0 && fallbackAt > catalogAt) {
            "the catalog must decide before the fallback heuristic; catalog@$catalogAt fallback@$fallbackAt"
        }
    }

    @Test
    fun utteranceParsingDoesNotImportAndroidCallingApis() {
        listOf(
            "app/src/main/kotlin/com/novadrive/app/voice/UtteranceIntentResolver.kt",
            "app/src/main/kotlin/com/novadrive/app/voice/ActionClaimGuard.kt",
            "contracts/src/main/kotlin/com/novadrive/contracts/Capability.kt",
        ).forEach { path ->
            val src = text(path)
            listOf("android.telephony", "android.content.Intent", "ACTION_CALL", "AndroidContacts").forEach { forbidden ->
                assertTrue(!src.contains(forbidden)) {
                    "$path must not couple NLU/catalog to Android calling ($forbidden)"
                }
            }
        }
    }

    @Test
    fun onlyPhoneProviderConstructsTheAndroidPhoneAdapter() {
        val allowed = setOf(
            "app/src/main/kotlin/com/novadrive/app/phone/PhoneProvider.kt",
            "app/src/main/kotlin/com/novadrive/app/AndroidContacts.kt",
        )
        val offenders = kotlinFiles("app/src/main").filter { file ->
            val rel = file.relativeTo(root).path.replace('\\', '/')
            rel !in allowed && file.readText().contains("AndroidContacts(")
        }.map { it.relativeTo(root).path.replace('\\', '/') }
        assertTrue(offenders.isEmpty()) {
            "PhonePort implementations are selected in PhoneProvider, like VehicleControlPort. Found: $offenders"
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
