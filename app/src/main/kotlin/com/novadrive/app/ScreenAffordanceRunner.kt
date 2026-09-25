package com.novadrive.app

import com.novadrive.app.ui.AffordanceMatcher
import com.novadrive.app.ui.ScreenAffordances
import com.novadrive.app.vehicle.ClimateToolHandler
import com.novadrive.app.voice.ClimateToolActions
import com.novadrive.app.voice.DriverContext
import com.novadrive.vehicle.ClimateLimits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * SPEC-010 B3–B5: a whole utterance that names one control on screen runs that control through
 * [ScreenControls] — the route a tap takes (I-6) — without asking the model.
 *
 * The decision is synchronous, so the caller can cancel the model's reply before it speaks; the
 * action itself runs on [scope]. Success is shown by the effect; a failure is handed to
 * [reportFailure] so the model says one sentence about what did not happen (I-1).
 */
class ScreenAffordanceRunner(
    private val affordances: ScreenAffordances,
    private val controls: ScreenControls,
    private val context: () -> DriverContext?,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    private val reportFailure: (String) -> Unit,
) {
    /** What a matched affordance would do now. [tool]/[action] are the model's names for it, or null. */
    data class Plan(
        val tool: String?,
        val action: String?,
        val run: suspend (ScreenControls) -> ScreenControls.Outcome,
    )

    /** true when the utterance was taken; the model's reply for it should be cancelled. */
    fun tryHandle(text: String): Boolean {
        val result = AffordanceMatcher.match(text, affordances.current.value)
        if (result is AffordanceMatcher.Result.Ambiguous) log("affordance_ambiguous count=${result.ids.size}")
        val match = result as? AffordanceMatcher.Result.Match ?: return false
        val plan = plan(match.affordance.id, match.verb, controls.musicPlaying.value, controls.climate.value.powerOn)
            ?: return false
        val driver = context()
        val epoch = driver?.currentEpoch() ?: 0
        if (plan.tool != null && driver != null && epoch > 0 &&
            !driver.claimCapability(epoch, plan.tool, plan.action, DriverContext.ClaimSource.LOCAL)
        ) {
            log("affordance_skip reason=claimed")
            return false
        }
        log("affordance_match id=${match.affordance.id} verb=${match.verb != null}")
        val id = match.affordance.id
        scope.launch {
            // B-table "screen changed": act only if the control is still on screen.
            val outcome = if (affordances.current.value.none { it.id == id }) {
                ScreenControls.Outcome(false, AFFORDANCE_GONE)
            } else {
                plan.run(controls)
            }
            log("screen_action=voice_$id ok=${outcome.ok} error=${outcome.errorCode ?: "-"}")
            if (!outcome.ok) reportFailure(outcome.errorCode ?: "UNKNOWN")
        }
        return true
    }

    companion object {
        const val AFFORDANCE_GONE = "AFFORDANCE_GONE"

        const val MUSIC_RESTART = "music_restart"
        const val MUSIC_PLAY = "music_play"
        const val MUSIC_STOP = "music_stop"
        const val TEMP_DOWN = "temp_down"
        const val TEMP_UP = "temp_up"
        const val CLIMATE = "climate"
        const val RECENTER = "recenter"
        const val CAMERA = "camera"

        private const val MUSIC = "control_music"
        private val OFF_VERBS = setOf("关掉", "关闭")

        /**
         * Pure: what [id] means in the current state. Null when it would do nothing (暂停 while
         * already stopped) — the model then answers, as it would without the screen.
         */
        fun plan(id: String, verb: String?, musicPlaying: Boolean, climateOn: Boolean): Plan? = when (id) {
            MUSIC_RESTART -> Plan(MUSIC, "play") { it.restartMusic() }
            MUSIC_PLAY -> if (musicPlaying) null else Plan(MUSIC, "play") { it.toggleMusic() }
            MUSIC_STOP -> if (!musicPlaying) null else Plan(MUSIC, "stop") { it.toggleMusic() }
            TEMP_DOWN -> Plan(ClimateToolHandler.TOOL, ClimateToolActions.ADJUST_TEMPERATURE) {
                it.adjustTemperature(-ClimateLimits.DEFAULT_TEMPERATURE_STEP_C)
            }
            TEMP_UP -> Plan(ClimateToolHandler.TOOL, ClimateToolActions.ADJUST_TEMPERATURE) {
                it.adjustTemperature(ClimateLimits.DEFAULT_TEMPERATURE_STEP_C)
            }
            CLIMATE -> {
                val wantOn = when (verb) {
                    in OFF_VERBS -> false
                    "打开" -> true
                    else -> !climateOn
                }
                if (wantOn == climateOn) {
                    null
                } else {
                    val action = if (wantOn) ClimateToolActions.POWER_ON else ClimateToolActions.POWER_OFF
                    Plan(ClimateToolHandler.TOOL, action) { it.toggleClimatePower() }
                }
            }
            RECENTER -> Plan(null, null) { it.recenter() }
            CAMERA -> Plan(null, null) { it.toggleCamera() }
            else -> null
        }

        /** The failure line for the model; a code, never the driver's words (I-8 applies to logs). */
        fun failureMessage(code: String): String =
            "（系统消息：驾驶员刚才用语音操作屏幕上的按钮，但没有成功，错误码 $code。" +
                "请用一句话告诉驾驶员没有成功，不要说已经完成。）"

        /** Names come from resources: "a|b" -> [a, b]. */
        fun names(resource: String): List<String> = resource.split('|').map { it.trim() }.filter { it.isNotEmpty() }

    }
}
