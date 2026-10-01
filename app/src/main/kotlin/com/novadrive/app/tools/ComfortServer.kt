package com.novadrive.app.tools

import com.novadrive.app.DebugVoiceLog
import com.novadrive.app.vehicle.ActionAnnouncement
import com.novadrive.app.vehicle.ClimateToolHandler
import com.novadrive.app.vehicle.ComfortScenarios
import com.novadrive.app.vehicle.WindowToolHandler
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.ToolDispatchResult
import com.novadrive.vehicle.CabinLimits
import com.novadrive.vehicle.CabinState
import org.json.JSONArray
import org.json.JSONObject

/**
 * Executes [ComfortDomain]. Every step goes through [route] — the dispatcher's own server path, the
 * same one a direct call takes (I-10) — so validation, handlers, DriverContext referents and
 * results are identical. [route] skips the driver-turn repeat guard: a sub-step is not a repeat.
 */
class ComfortServer(
    private val route: (DomainVoiceEvent.ToolCall) -> ToolDispatchResult,
    private val cabinState: () -> CabinState? = { null },
) : ToolServer {
    override val domain: ToolDomain = ComfortDomain

    private class StepRun(val step: ComfortScenarios.Step, val result: ToolDispatchResult?) {
        val skipped get() = result == null
    }

    override fun call(call: DomainVoiceEvent.ToolCall, env: ToolCallEnv): ToolDispatchResult =
        when (call.name) {
            "run_scenario" -> runScenario(call, env)
            else -> env.failed(call, "UNKNOWN_TOOL")
        }

    private fun runScenario(call: DomainVoiceEvent.ToolCall, env: ToolCallEnv): ToolDispatchResult {
        val name = call.arguments["name"].orEmpty()
        val steps = ComfortScenarios.steps(name) ?: return env.failed(call, "INVALID_FIELD_VALUE")
        if (name == ComfortScenarios.MOSQUITO_DONE && windowsAlreadyClosed()) {
            DebugVoiceLog.log("fz_scenario name=$name steps=0 ok=0 skipped=0")
            return ToolDispatchResult(
                null, null,
                output = JSONObject().put("ok", true).put("tool", call.name).put("name", name)
                    .put("status", "already_closed").put("steps", JSONArray())
                    .put("announce", "车窗本来就是关着的。").toString(),
            )
        }
        val runs = ArrayList<StepRun>()
        steps.forEachIndexed { index, step ->
            val prerequisiteFailed = step.requires?.let { runs[it].skipped || !syncOk(runs[it].result!!) } ?: false
            runs += if (prerequisiteFailed) {
                StepRun(step, null)
            } else {
                StepRun(step, route(DomainVoiceEvent.ToolCall("${call.callId}#$index", step.tool, step.arguments)))
            }
        }
        val deferred = runs.any { it.result?.deferredOutput != null }
        val chip = "✓ 场景 " + runs.joinToString(" · ") { "${it.step.tool}·${it.step.action}" }
        if (!deferred) {
            return ToolDispatchResult(null, null, successChip = chip, output = build(call, name, runs.map { it to it.result?.output }))
        }
        return ToolDispatchResult(null, null, successChip = chip, deferredOutput = {
            val outputs = runs.map { run -> run to (run.result?.deferredOutput?.invoke() ?: run.result?.output) }
            build(call, name, outputs)
        })
    }

    private fun windowsAlreadyClosed(): Boolean =
        cabinState()?.windows?.values?.all { it == CabinLimits.WINDOW_MIN } == true

    private fun syncOk(result: ToolDispatchResult): Boolean =
        result.blockedReason == null && result.output?.let(::okOf) == true

    private fun okOf(output: String): Boolean = runCatching { JSONObject(output).optBoolean("ok") }.getOrDefault(false)

    private fun build(call: DomainVoiceEvent.ToolCall, name: String, outputs: List<Pair<StepRun, String?>>): String {
        val steps = JSONArray()
        val clauses = ArrayList<String>()
        var okCount = 0
        var skipped = 0
        outputs.forEach { (run, output) ->
            val step = run.step
            val json = output?.let { runCatching { JSONObject(it) }.getOrNull() }
            val ok = !run.skipped && run.result?.blockedReason == null && json?.optBoolean("ok") == true
            val entry = JSONObject().put("tool", step.tool).put("action", step.action).put("ok", ok)
            when {
                run.skipped -> { skipped++; entry.put("error", "SKIPPED_PREREQUISITE_FAILED") }
                !ok -> entry.put("error", run.result?.blockedReason ?: json?.optString("error")?.ifEmpty { null } ?: "FAILED")
                else -> okCount++
            }
            steps.put(entry)
            clauses += if (ok) doneClause(step, json!!) else ActionAnnouncement.notDone(step.tool, step.action, step.arguments["value"]?.toDoubleOrNull())
        }
        DebugVoiceLog.log("fz_scenario name=$name steps=${outputs.size} ok=$okCount skipped=$skipped")
        var announce = clauses.joinToString("，") + "。"
        if (name == ComfortScenarios.DROWSY) announce += ComfortScenarios.REST_STOP_OFFER
        val allOk = okCount == outputs.size
        return JSONObject()
            .put("ok", allOk)
            .put("tool", call.name)
            .put("name", name)
            .put("status", if (allOk) "done" else "partial")
            .put("steps", steps)
            .put("announce", announce)
            .put("instruction", INSTRUCTION)
            .toString()
    }

    private fun doneClause(step: ComfortScenarios.Step, json: JSONObject): String = when (step.tool) {
        WindowToolHandler.TOOL -> json.optString("announce")
        ClimateToolHandler.TOOL -> ActionAnnouncement.climate(
            step.action,
            json.optBoolean("power_on"),
            json.optDouble("temperature_c"),
            json.optInt("fan_level"),
        )
        ComfortScenarios.MUSIC_TOOL -> "音乐放起来了"
        else -> "${step.tool}做好了"
    }

    private companion object {
        const val INSTRUCTION = "按 announce 说做了什么，没做成的也要说，不要说都弄好了"
    }
}
