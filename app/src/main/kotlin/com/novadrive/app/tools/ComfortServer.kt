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
    private val now: () -> Long = System::currentTimeMillis,
) : ToolServer {
    override val domain: ToolDomain = ComfortDomain

    /** When mosquito last succeeded; mosquito_done is only meaningful within [MOSQUITO_TTL_MS]. */
    @Volatile
    private var mosquitoAt: Long? = null

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
        if (name == ComfortScenarios.MOSQUITO_DONE) {
            if (windowsAlreadyClosed()) {
                DebugVoiceLog.log("fz_scenario name=$name steps=0 ok=0 skipped=0")
                return ToolDispatchResult(
                    null, null,
                    output = JSONObject().put("ok", true).put("tool", call.name).put("name", name)
                        .put("status", "already_closed").put("steps", JSONArray())
                        .put("announce", "车窗本来就是关着的。").toString(),
                )
            }
            val at = mosquitoAt
            if (at == null || now() - at > MOSQUITO_TTL_MS) {
                DebugVoiceLog.log("fz_scenario name=$name steps=0 ok=0 skipped=0")
                return env.failed(call, NO_RECENT_MOSQUITO)
            }
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
        if (runs.none { it.result?.deferredOutput != null }) {
            val out = build(call, name, runs.map { it to it.result?.output })
            val failed = out.getString("status") == STATUS_FAILED
            return ToolDispatchResult(
                null, null,
                blockedReason = if (failed) firstError(out) else null,
                successChip = chip(out),
                output = out.toString(),
            )
        }
        val pending = "场景 " + runs.joinToString(" · ") { "${it.step.tool}·${it.step.action}" }
        return ToolDispatchResult(null, null, successChip = pending, deferredOutput = {
            val outputs = runs.map { run -> run to (run.result?.deferredOutput?.invoke() ?: run.result?.output) }
            build(call, name, outputs).toString()
        })
    }

    private fun firstError(out: JSONObject): String {
        val steps = out.getJSONArray("steps")
        return (0 until steps.length()).map { steps.getJSONObject(it) }.firstOrNull { it.has("error") }
            ?.getString("error") ?: STATUS_FAILED.uppercase()
    }

    /** ✓ done, ✗ failed, – skipped, per step. */
    private fun chip(out: JSONObject): String {
        val steps = out.getJSONArray("steps")
        return "场景 " + (0 until steps.length()).map { steps.getJSONObject(it) }.joinToString(" · ") { s ->
            val mark = when {
                s.getBoolean("ok") -> "✓"
                s.optString("error") == SKIPPED -> "–"
                else -> "✗"
            }
            "$mark ${s.getString("tool")}·${s.getString("action")}"
        }
    }

    private fun windowsAlreadyClosed(): Boolean =
        cabinState()?.windows?.values?.all { it == CabinLimits.WINDOW_MIN } == true

    private fun syncOk(result: ToolDispatchResult): Boolean =
        result.blockedReason == null && result.output?.let(::okOf) == true

    private fun okOf(output: String): Boolean = runCatching { JSONObject(output).optBoolean("ok") }.getOrDefault(false)

    private fun build(call: DomainVoiceEvent.ToolCall, name: String, outputs: List<Pair<StepRun, String?>>): JSONObject {
        val steps = JSONArray()
        val clauses = ArrayList<String>()
        val notDone = JSONArray()
        var okCount = 0
        var skipped = 0
        outputs.forEach { (run, output) ->
            val step = run.step
            val json = output?.let { runCatching { JSONObject(it) }.getOrNull() }
            val ok = !run.skipped && run.result?.blockedReason == null && json?.optBoolean("ok") == true
            val entry = JSONObject().put("tool", step.tool).put("action", step.action).put("ok", ok)
            when {
                run.skipped -> { skipped++; entry.put("error", SKIPPED) }
                !ok -> entry.put("error", run.result?.blockedReason ?: json?.optString("error")?.ifEmpty { null } ?: "FAILED")
                else -> okCount++
            }
            steps.put(entry)
            if (ok) {
                clauses += doneClause(step, json!!)
            } else {
                val clause = ActionAnnouncement.notDone(step.tool, step.action, step.arguments["value"]?.toDoubleOrNull())
                clauses += clause
                notDone.put(clause)
            }
        }
        DebugVoiceLog.log("fz_scenario name=$name steps=${outputs.size} ok=$okCount skipped=$skipped")
        var announce = clauses.joinToString("，") + "。"
        if (name == ComfortScenarios.DROWSY) announce += ComfortScenarios.REST_STOP_OFFER
        val allOk = okCount == outputs.size
        if (allOk && name == ComfortScenarios.MOSQUITO) mosquitoAt = now()
        if (allOk && name == ComfortScenarios.MOSQUITO_DONE) mosquitoAt = null
        return JSONObject()
            .put("ok", allOk)
            .put("tool", call.name)
            .put("name", name)
            .put("status", if (allOk) "done" else if (okCount == 0) STATUS_FAILED else STATUS_PARTIAL)
            .put("steps", steps)
            .put("not_done", notDone)
            .put("announce", announce)
            .put("instruction", INSTRUCTION)
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

    companion object {
        const val NO_RECENT_MOSQUITO = "NO_RECENT_MOSQUITO"
        const val MOSQUITO_TTL_MS = 10 * 60 * 1000L
        val STATUS_PARTIAL: String = "partial"
        val STATUS_FAILED: String = "failed"
        const val SKIPPED = "SKIPPED_PREREQUISITE_FAILED"
        private const val INSTRUCTION = "按 announce 说做了什么，没做成的也要说，不要说都弄好了"
    }
}
