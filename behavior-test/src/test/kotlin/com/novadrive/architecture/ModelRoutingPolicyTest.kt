package com.novadrive.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards the hard-gate Cursor model router
 * ([scripts/model_route.py](../../scripts/model_route.py) plus
 * [.cursor/rules/hybrid-model-routing.mdc](../../.cursor/rules/hybrid-model-routing.mdc)).
 *
 * The failure this prevents: a session treats "difficult" or "large" as a reason to spend
 * Grok 4.7 Extra High, or treats "needs Grok" as a human stop, or continues through GROK_REQUIRED
 * without delegation, or silently downgrades when Grok is unavailable, or forks a second
 * A–F / H1–H8 list that drifts. The mechanism is the classifier script (non-zero exit,
 * fail-closed, ledger) plus the always-apply rule and pinned subagent `model:` fields.
 */
class ModelRoutingPolicyTest {
    private val root = File(System.getProperty("nova.repo.root") ?: error("nova.repo.root not set"))

    private fun text(path: String): String = File(root, path).also {
        assertTrue(it.isFile, "missing file: $path")
    }.readText()

    private fun flat(path: String): String = text(path).lines().joinToString(" ") { it.trim() }

    private fun python(): String = System.getenv("PYTHON") ?: "python"

    private fun route(evalJson: String, vararg extra: String): Pair<Int, String> {
        val script = File(root, "scripts/model_route.py")
        assertTrue(script.isFile, "missing scripts/model_route.py")
        val command = mutableListOf(python(), script.absolutePath, "--eval", evalJson)
        command.addAll(extra)
        val proc = ProcessBuilder(command)
            .directory(root)
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().use { it.readText() }
        return proc.waitFor() to out
    }

    @Test
    fun oneAlwaysApplyRuleIsTheRoutingAuthority() {
        val rule = text(".cursor/rules/hybrid-model-routing.mdc")
        assertTrue(rule.contains("alwaysApply: true"), "routing must apply to every Cursor session")
        assertTrue(
            rule.contains("only") && rule.contains("routing policy"),
            "the rule must claim uniqueness so A–F / H1–H8 is not forked",
        )
        assertTrue(
            rule.contains("scripts/model_route.py"),
            "the rule must name the classifier as enforcement",
        )
        val agents = text("AGENTS.md")
        assertTrue(
            agents.contains(".cursor/rules/hybrid-model-routing.mdc"),
            "AGENTS.md must point at the rule rather than restating a second list",
        )
        assertTrue(
            agents.contains("scripts/model_route.py"),
            "AGENTS.md must point at the hard-gate script",
        )
        assertFalse(
            agents.contains("**H1**") || agents.contains("H1 Architecture"),
            "AGENTS.md must not duplicate H1–H8 (a second list will drift)",
        )
        assertFalse(
            agents.contains("**A**") && agents.contains("Architecture / design"),
            "AGENTS.md must not duplicate the A–F trigger table",
        )
    }

    @Test
    fun defaultExecutorIsComposerStandardNotFast() {
        for (path in listOf(
            ".cursor/agents/implementer.md",
            ".cursor/agents/repo-explorer.md",
        )) {
            val body = text(path)
            assertTrue(
                body.contains("model: composer-2.5[fast=false]"),
                "$path must pin Composer 2.5 Standard",
            )
            assertFalse(
                body.contains("model: composer-2.5-fast"),
                "$path must not pin Composer Fast",
            )
        }
        val rule = text(".cursor/rules/hybrid-model-routing.mdc")
        assertTrue(rule.contains("composer-2.5[fast=false]"))
        assertTrue(rule.contains("No Fast mode") || rule.contains("never Composer Fast"))
        // Absolute ban: no labor agent may pin any *-fast slug.
        for (path in listOf(
            ".cursor/agents/implementer.md",
            ".cursor/agents/repo-explorer.md",
            ".cursor/agents/grok-high.md",
        )) {
            val modelLines = text(path).lines()
                .dropWhile { it.trim() != "---" }
                .drop(1)
                .takeWhile { it.trim() != "---" }
                .map { it.trim() }
                .filter { it.startsWith("model:") }
            assertTrue(modelLines.isNotEmpty(), "$path must declare model:")
            for (line in modelLines) {
                val value = line.substringAfter("model:").trim().lowercase()
                assertFalse(
                    value.endsWith("-fast") || value == "composer-2.5-fast",
                    "$path must not pin Fast: $line",
                )
            }
        }
    }

    @Test
    fun grokHighIsPinnedAndReadOnly() {
        val grok = text(".cursor/agents/grok-high.md")
        assertTrue(grok.contains("model: grok-4.7-xhigh"), "must match this Cursor build's slug")
        assertTrue(grok.contains("readonly: true"), "Grok High plans/reviews; DEFAULT implements")
        assertTrue(
            grok.contains("must **not** launch `grok-high`") ||
                grok.contains("Recursion is forbidden") ||
                grok.contains("Launch `grok-high`"),
            "grok-high must forbid launching itself",
        )
        val rule = text(".cursor/rules/hybrid-model-routing.mdc")
        assertTrue(rule.contains("grok-4.7-xhigh"))
        assertTrue(rule.contains("grok-high"))
        assertTrue(rule.contains("BLOCKED_GROK_UNAVAILABLE"))
        assertTrue(rule.contains("fail closed") || rule.contains("fail-closed") || rule.contains("Fail-closed"))
    }

    @Test
    fun triggersAFAndH1ThroughH8AreNamed() {
        val rule = flat(".cursor/rules/hybrid-model-routing.mdc")
        listOf("**A**", "**B**", "**C**", "**D**", "**E**", "**F**").forEach { letter ->
            assertTrue(rule.contains(letter), "routing rule must name trigger $letter")
        }
        listOf(
            "**H1**", "**H2**", "**H3**", "**H4**", "**H5**", "**H6**", "**H7**", "**H8**",
        ).forEach { code ->
            assertTrue(rule.contains(code), "routing rule must name $code")
        }
        assertTrue(rule.contains("Architecture"))
        assertTrue(rule.contains("high-impact") || rule.contains("High-impact"))
        assertTrue(rule.contains("Difficult debugging") || rule.contains("root cause"))
        assertTrue(rule.contains("2") && rule.contains("fix"))
        assertTrue(rule.contains("Critical review"))
    }

    @Test
    fun threeRoutesExistAndDefaultIsPreferred() {
        val rule = text(".cursor/rules/hybrid-model-routing.mdc")
        assertTrue(rule.contains("ROUTE = DEFAULT"))
        assertTrue(rule.contains("GROK_REQUIRED"))
        assertTrue(rule.contains("BLOCKED_GROK_UNAVAILABLE"))
        assertTrue(rule.contains("GROK_HIGH_PLAN_THEN_DEFAULT_EXECUTE"))
        val flattened = flat(".cursor/rules/hybrid-model-routing.mdc")
        assertTrue(
            flattened.contains("Prefer `DEFAULT`") || flattened.contains("**Prefer `DEFAULT`.**"),
            "DEFAULT must be the preferred route",
        )
    }

    @Test
    fun escalationContractIsStable() {
        val fields = listOf(
            "ESCALATION_REASON",
            "DIAGNOSIS",
            "DECISION / PLAN",
            "FILES_OR_COMPONENTS_AFFECTED",
            "RISKS / INVARIANTS",
            "EXECUTION_STEPS_FOR_DEFAULT_AGENT",
            "VALIDATION_REQUIRED",
            "NEEDS_FURTHER_HIGH_REASONING",
        )
        for (path in listOf(
            ".cursor/rules/hybrid-model-routing.mdc",
            ".cursor/agents/grok-high.md",
        )) {
            val body = text(path)
            fields.forEach { field ->
                assertTrue(body.contains(field), "$path must include contract field $field")
            }
        }
    }

    @Test
    fun grokIsNotAHumanStopAndMechanicalWorkStaysDefault() {
        val rule = flat(".cursor/rules/hybrid-model-routing.mdc")
        assertTrue(
            rule.contains("Needs Grok High") && rule.contains("not") && rule.contains("human"),
            "Grok escalation must be independent of HUMAN_REQUIRED",
        )
        assertTrue(rule.contains("Rename a variable") && rule.contains("`DEFAULT`"))
        assertTrue(rule.contains("mechanical rename") && rule.contains("`DEFAULT`"))
        assertTrue(rule.contains("documentation generation") && rule.contains("`DEFAULT`"))
        assertTrue(rule.contains("Architecture redesign") && rule.contains("`GROK_REQUIRED`"))
        assertTrue(rule.contains("survives 2 reasonable fixes") && rule.contains("`GROK_REQUIRED`"))
        assertTrue(rule.contains("missing E2E evidence") && rule.contains("`GROK_REQUIRED`"))
        assertTrue(
            rule.contains("Do **not** route to Grok only because") ||
                rule.contains("Do **not** escalate"),
        )
        val implementer = text(".cursor/agents/implementer.md")
        assertTrue(
            implementer.contains("Do not launch `grok-high`") ||
                implementer.contains("Launch `grok-high`"),
            "implementer must not recurse into grok-high",
        )
        assertTrue(implementer.contains("needs a human"))
        assertTrue(
            implementer.contains("GROK_REQUIRED") && implementer.contains("BLOCKED_GROK_UNAVAILABLE"),
            "implementer must refuse both gated routes",
        )
    }

    @Test
    fun antiLoopForbidsRecursiveGrokAndSameEvidenceReescalation() {
        val rule = flat(".cursor/rules/hybrid-model-routing.mdc")
        assertTrue(
            rule.contains("must **not** launch `grok-high`") ||
                rule.contains("no recursive Grok"),
        )
        assertTrue(rule.contains("same unchanged evidence") || rule.contains("same evidence"))
        assertTrue(rule.contains("model_route_ledger.json"))
        val grok = text(".cursor/agents/grok-high.md")
        assertTrue(grok.contains("Recursion is forbidden") || grok.contains("must **not** launch"))
        assertTrue(grok.contains("--role grok-high"))
    }

    @Test
    fun classifierSelftestPasses() {
        val script = File(root, "scripts/model_route.py")
        val proc = ProcessBuilder(python(), script.absolutePath, "--selftest")
            .directory(root)
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().use { it.readText() }
        val code = proc.waitFor()
        assertEquals(0, code) { "model_route.py --selftest failed:\n$out" }
        assertTrue(out.contains("model_route selftest OK"), out)
    }

    @Test
    fun requiredScenariosMatchTheGate() {
        data class Case(val name: String, val json: String, val route: String, val exitIfEval: Int)

        val cases = listOf(
            Case("simple_one_file_obvious_fix", "obvious_local_fix=true", "DEFAULT", 0),
            Case(
                "deterministic_unit_test_repair",
                "obvious_local_fix=true,deterministic_low_uncertainty=true",
                "DEFAULT",
                0,
            ),
            Case(
                "architecture_redesign",
                "architecture_decision=true,major_design_choice=true,new_subsystem=true",
                "GROK_REQUIRED",
                2,
            ),
            Case(
                "bug_survives_two_fixes",
                "inspection_complete=true,failed_fix_attempts=2",
                "GROK_REQUIRED",
                2,
            ),
            Case(
                "repo_wide_mechanical_rename",
                "cross_module_change=true,mechanical_only=true,deterministic_low_uncertainty=true",
                "DEFAULT",
                0,
            ),
            Case(
                "concurrency_lifecycle_unclear",
                "concurrency_async_threading=true,lifecycle_or_state_machine=true,inspection_complete=true,root_cause_unclear_after_inspection=true",
                "GROK_REQUIRED",
                2,
            ),
            Case(
                "release_critical_contract",
                "interface_or_contract_change=true,release_or_production_critical=true",
                "GROK_REQUIRED",
                2,
            ),
            Case("long_documentation_generation", "docs_only=true", "DEFAULT", 0),
        )
        for (case in cases) {
            val (code, out) = route(case.json)
            assertTrue(out.contains("route: ${case.route}"), "${case.name}: $out")
            assertEquals(case.exitIfEval, code) { "${case.name} exit $code\n$out" }
        }
    }

    @Test
    fun grokRequiredCannotContinueWithoutDelegation() {
        val (code, out) = route("architecture_decision=true", "--action", "continue")
        assertEquals(2, code) { out }
        assertTrue(out.contains("route: GROK_REQUIRED"), out)
        assertTrue(out.contains("status: blocked"), out)
        assertTrue(out.contains("must not continue") || out.contains("delegate"), out)
    }

    @Test
    fun grokUnavailableFailsClosed() {
        val (code, out) = route(
            "architecture_decision=true",
            "--grok-available",
            "false",
        )
        assertEquals(3, code) { out }
        assertTrue(out.contains("route: BLOCKED_GROK_UNAVAILABLE"), out)
        assertFalse(out.contains("route: DEFAULT"), out)
        assertTrue(out.contains("status: blocked"), out)
    }

    @Test
    fun grokHighRoleDoesNotLoop() {
        val (code, out) = route(
            "architecture_decision=true",
            "--role",
            "grok-high",
        )
        assertEquals(0, code) { out }
        assertTrue(out.contains("route: DEFAULT"), out)
        assertTrue(out.contains("delegate: -"), out)
    }

    @Test
    fun terminationGateForbidsSelfAuthorization() {
        val rule = flat(".cursor/rules/hybrid-model-routing.mdc")
        assertTrue(rule.contains("TERMINATION_REVIEW_REQUIRED") || rule.contains("terminate-request"))
        assertTrue(rule.contains("TERMINAL_APPROVED"))
        assertTrue(rule.contains("no agent may authorize") || rule.contains("No agent may authorize"))
        assertTrue(rule.contains("MAX_GROK") || rule.contains("terminate-review"))
        assertTrue(rule.contains("single-use") || rule.contains("Single-use"))
        val continueSkill = text("skills/continue.md")
        assertTrue(continueSkill.contains("TERMINATION_AUTHORIZED"))
        assertTrue(continueSkill.contains("terminate-request"))
        assertTrue(continueSkill.contains("TERMINAL_APPROVED"))
        val grok = text(".cursor/agents/grok-high.md")
        assertTrue(grok.contains("TERMINATION_VERDICT") || grok.contains("TERMINAL_APPROVED"))
        assertTrue(grok.contains("CONTINUE"))
        assertTrue(grok.contains("REVIEW_UNAVAILABLE"))
    }

    @Test
    fun terminateRequestNeverSelfApproves() {
        val script = File(root, "scripts/model_route.py")
        val frontierFile = File.createTempFile("frontier", ".json")
        frontierFile.writeText(
            """{"AUTONOMOUS_ACTION_AVAILABLE":"NO","STOP_REASON":"WORK_FRONTIER_EXHAUSTED","actionable_count":0,"blocked_count":0,"NEXT_ACTION":"NONE"}""",
        )
        val proc = ProcessBuilder(
            python(),
            script.absolutePath,
            "--action",
            "terminate-request",
            "--frontier-file",
            frontierFile.absolutePath,
            "--grok-available",
            "true",
            "--json",
        )
            .directory(root)
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().use { it.readText() }
        val code = proc.waitFor()
        frontierFile.delete()
        assertEquals(4, code) { out }
        assertTrue(out.contains("TERMINATION_REVIEW_REQUIRED") || out.contains("termination_denied"), out)
        assertFalse(out.contains("\"status\": \"termination_consumed\""), out)
    }

    @Test
    fun terminateReviewContinueWhenActionableWorkRemains() {
        val script = File(root, "scripts/model_route.py")
        val frontierFile = File.createTempFile("frontier", ".json")
        frontierFile.writeText(
            """{"AUTONOMOUS_ACTION_AVAILABLE":"YES","STOP_REASON":"NOT_STOPPING","actionable_count":1,"blocked_count":1,"NEXT_ACTION":"arrival_lifecycle","actionable_items":["arrival_lifecycle"]}""",
        )
        val req = ProcessBuilder(
            python(), script.absolutePath,
            "--action", "terminate-request",
            "--frontier-file", frontierFile.absolutePath,
            "--grok-available", "true",
            "--json",
        ).directory(root).redirectErrorStream(true).start()
        val reqOut = req.inputStream.bufferedReader().use { it.readText() }
        assertEquals(4, req.waitFor()) { reqOut }
        val fp = Regex(""""fingerprint":\s*"([a-f0-9]+)"""").find(reqOut)?.groupValues?.get(1)
            ?: error("no fingerprint in $reqOut")
        val review = ProcessBuilder(
            python(), script.absolutePath,
            "--action", "terminate-review",
            "--verdict", "CONTINUE",
            "--next-action", "arrival_lifecycle: prove production wiring",
            "--role", "grok-high",
            "--fingerprint", fp,
            "--frontier-file", frontierFile.absolutePath,
            "--actionable-count", "1",
            "--grok-available", "true",
            "--json",
        ).directory(root).redirectErrorStream(true).start()
        val out = review.inputStream.bufferedReader().use { it.readText() }
        val code = review.waitFor()
        frontierFile.delete()
        assertEquals(5, code) { out }
        assertTrue(out.contains("CONTINUE"), out)
        assertTrue(out.contains("arrival_lifecycle"), out)
    }

    @Test
    fun terminateApproveRejectedWhenActionableWorkRemains() {
        val script = File(root, "scripts/model_route.py")
        val frontierFile = File.createTempFile("frontier", ".json")
        frontierFile.writeText(
            """{"AUTONOMOUS_ACTION_AVAILABLE":"YES","actionable_count":1,"blocked_count":1,"NEXT_ACTION":"x","actionable_items":["x"]}""",
        )
        val req = ProcessBuilder(
            python(), script.absolutePath,
            "--action", "terminate-request",
            "--frontier-file", frontierFile.absolutePath,
            "--grok-available", "true",
            "--json",
        ).directory(root).redirectErrorStream(true).start()
        val reqOut = req.inputStream.bufferedReader().use { it.readText() }
        val fp = Regex(""""fingerprint":\s*"([a-f0-9]+)"""").find(reqOut)?.groupValues?.get(1)
            ?: error(reqOut)
        val review = ProcessBuilder(
            python(), script.absolutePath,
            "--action", "terminate-review",
            "--verdict", "TERMINAL_APPROVED",
            "--role", "grok-high",
            "--fingerprint", fp,
            "--frontier-file", frontierFile.absolutePath,
            "--actionable-count", "1",
            "--grok-available", "true",
            "--json",
        ).directory(root).redirectErrorStream(true).start()
        val out = review.inputStream.bufferedReader().use { it.readText() }
        frontierFile.delete()
        assertEquals(5, review.waitFor()) { out }
        assertTrue(out.contains("actionable") || out.contains("CONTINUE"), out)
    }

    @Test
    fun terminateUnavailableFailsClosed() {
        val script = File(root, "scripts/model_route.py")
        val frontierFile = File.createTempFile("frontier", ".json")
        frontierFile.writeText(
            """{"AUTONOMOUS_ACTION_AVAILABLE":"NO","actionable_count":0,"NEXT_ACTION":"NONE"}""",
        )
        val proc = ProcessBuilder(
            python(), script.absolutePath,
            "--action", "terminate-request",
            "--frontier-file", frontierFile.absolutePath,
            "--grok-available", "false",
            "--json",
        ).directory(root).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().use { it.readText() }
        frontierFile.delete()
        assertEquals(4, proc.waitFor()) { out }
        assertTrue(out.contains("REVIEW_UNAVAILABLE") || out.contains("BLOCKED_GROK_UNAVAILABLE"), out)
    }
}
