package com.novadrive.architecture

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards the rule that a run does not end because the thing that was asked for is finished
 * ([CONSTITUTION.md](../../harness/CONSTITUTION.md) rule 12).
 *
 * The rule exists because agents kept stopping with obvious work still in the repository. A rule
 * like that decays quietly: the text stays, the mechanism that made it true gets refactored away,
 * and nobody notices until an agent stops early again. So these assert the *mechanism* — the
 * generated stop state and its fields — and only assert prose where the prose is the machine-
 * readable identifier itself.
 */
class AutonomyPolicyTest {
    private val root = File(System.getProperty("nova.repo.root") ?: error("nova.repo.root not set"))

    private fun text(path: String): String = File(root, path).also {
        assertTrue(it.isFile, "missing file: $path")
    }.readText()

    /** The stop-state block of the generated state file, as flat key -> value. */
    private fun stopState(): Map<String, String> {
        val state = text("state/PROJECT_STATE.json")
        val frontier = state.substringAfter("\"work_frontier\"", "")
        assertTrue(frontier.isNotBlank()) {
            "state/PROJECT_STATE.json has no work_frontier block; run scripts/collect_state.py"
        }
        return FIELDS.associateWith { field ->
            Regex("\"$field\"\\s*:\\s*\"([^\"]*)\"").find(frontier)?.groupValues?.get(1)
                ?: error("work_frontier is missing $field")
        }
    }

    @Test
    fun theAutonomyRuleIsStatedWhereAgentsAreToldHowToWork() {
        val constitution = text("harness/CONSTITUTION.md")
        assertTrue(constitution.contains(IDENTIFIER)) {
            "CONSTITUTION.md must carry the identifier $IDENTIFIER so the rule can be referred to"
        }
        assertTrue(constitution.contains("skills/continue.md")) {
            "the rule must point at the procedure that implements it"
        }
        // The entry point every agent reads first has to say it, or the rule is not discoverable.
        val agents = text("AGENTS.md")
        assertTrue(agents.contains("skills/continue.md") && agents.contains("discover_work.py")) {
            "AGENTS.md must point at the continue procedure and the discovery script"
        }
    }

    @Test
    fun theProcedureAndTheSpecTemplateExist() {
        val procedure = text("skills/continue.md")
        FIELDS.forEach { field ->
            assertTrue(procedure.contains(field)) { "skills/continue.md must define $field" }
        }
        val template = text("SPECS/SPEC-TEMPLATE.md")
        assertTrue(template.contains("discover_work.py")) {
            "the SPEC template must tell the next agent to rediscover work after Done"
        }
        // Reaching Done on a SPEC is the exact moment agents used to stop.
        assertTrue(template.contains("does not end the run")) {
            "the SPEC template must say that finishing a SPEC is not finishing a run"
        }
    }

    @Test
    fun theGeneratedStateCarriesAStopDecision() {
        val stop = stopState()
        assertTrue(stop.getValue("AUTONOMOUS_ACTION_AVAILABLE") in setOf("YES", "NO"))
        assertTrue(stop.getValue("HUMAN_ACTION_REQUIRED") in setOf("YES", "NO"))
        assertTrue(stop.getValue("STOP_REASON").isNotBlank())
    }

    @Test
    fun theStopDecisionIsNotSelfContradictory() {
        val stop = stopState()
        val available = stop.getValue("AUTONOMOUS_ACTION_AVAILABLE") == "YES"
        val human = stop.getValue("HUMAN_ACTION_REQUIRED") == "YES"
        val next = stop.getValue("NEXT_ACTION")

        assertFalse(available && next == "NONE") {
            "AUTONOMOUS_ACTION_AVAILABLE=YES with NEXT_ACTION=NONE: one of them is wrong"
        }
        assertFalse(available && human) {
            "a run cannot both have autonomous work left and require a human"
        }
    }

    @Test
    fun claimingAHumanIsNeededRequiresNamingWhatIsMissing() {
        val stop = stopState()
        if (stop.getValue("HUMAN_ACTION_REQUIRED") != "YES") return
        val dependency = stop.getValue("BLOCKING_DEPENDENCY").trim()
        assertTrue(dependency.length >= 20 && VAGUE.none { dependency.lowercase().startsWith(it) }) {
            "HUMAN_ACTION_REQUIRED=YES must name the concrete unavailable thing, not a wish for " +
                "attention. Got: \"$dependency\""
        }
    }

    @Test
    fun aDeclaredBlockerNamesSomethingConcrete() {
        // Anywhere a document takes an item off the autonomous frontier, it must say what is
        // missing — otherwise "blocked" quietly becomes a synonym for "finished".
        val vague = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "md" && !it.path.contains("${File.separator}.git") }
            .forEach { file ->
                Regex("^\\s*BLOCKED_BY:\\s*(.*)$", RegexOption.MULTILINE)
                    .findAll(file.readText())
                    .forEach { match ->
                        val reason = match.groupValues[1].trim()
                        if (reason.length < 20 || VAGUE.any { reason.lowercase().startsWith(it) }) {
                            vague += "${file.relativeTo(root).path}: \"$reason\""
                        }
                    }
            }
        assertTrue(vague.isEmpty()) { "BLOCKED_BY must name what a person has to supply: $vague" }
    }

    @Test
    fun everySpecDeclaresItsStatus() {
        // discover_work.py reads these; a SPEC with no status is invisible to the frontier.
        val missing = File(root, "SPECS").listFiles()
            .orEmpty()
            .filter { it.name.startsWith("SPEC-") && it.extension == "md" }
            .filterNot { it.readText().contains("Status:") }
            .map { it.name }
        assertTrue(missing.isEmpty()) { "SPECs with no Status line: $missing" }
    }

    private companion object {
        const val IDENTIFIER = "AUTONOMOUS_WORK_EXHAUSTION_REQUIRED"

        val FIELDS = listOf(
            "AUTONOMOUS_ACTION_AVAILABLE",
            "HUMAN_ACTION_REQUIRED",
            "STOP_REASON",
            "BLOCKING_DEPENDENCY",
            "NEXT_ACTION",
        )

        /** Phrases that ask for attention instead of naming what is unavailable. */
        val VAGUE = listOf(
            "none", "unknown", "unclear", "tbd", "n/a", "pending", "later", "see above",
            "needs review", "review needed", "human input", "ask the user", "blocked", "external",
        )
    }
}
