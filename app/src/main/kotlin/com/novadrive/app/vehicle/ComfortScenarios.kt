package com.novadrive.app.vehicle

/**
 * The fixed comfort playbooks behind `run_scenario` (SPEC-015 FZ-09/FZ-10, B7/B8). Each step is an
 * ordinary tool call; ComfortServer routes it through the same server path a direct call uses, so
 * there is no second execution path (I-10). A step with [Step.requires] is skipped when that
 * earlier step failed; independent steps always run.
 */
object ComfortScenarios {
    data class Step(val tool: String, val arguments: Map<String, String>, val requires: Int? = null) {
        val action: String get() = arguments["action"].orEmpty()
    }

    const val TOOL = "run_scenario"
    const val MOSQUITO = "mosquito"
    const val MOSQUITO_DONE = "mosquito_done"
    const val STUFFY = "stuffy"
    const val DROWSY = "drowsy"
    val NAMES = listOf(MOSQUITO, MOSQUITO_DONE, STUFFY, DROWSY)
    const val MOSQUITO_WINDOW_PERCENT = 50

    /** Offer only; the scenario never navigates. */
    const val REST_STOP_OFFER = "要不要找个服务区歇一下？"

    private fun window(vararg args: Pair<String, String>) = Step(WindowToolHandler.TOOL, mapOf(*args))
    private fun climate(vararg args: Pair<String, String>, requires: Int? = null) =
        Step(ClimateToolHandler.TOOL, mapOf(*args), requires)

    fun steps(name: String): List<Step>? = when (name) {
        MOSQUITO -> listOf(window("action" to "set", "window" to "all", "value" to MOSQUITO_WINDOW_PERCENT.toString()))
        MOSQUITO_DONE -> listOf(window("action" to "close", "window" to "all"))
        STUFFY -> listOf(
            climate("action" to ClimateToolHandler.ACTION_POWER_ON),
            climate("action" to ClimateToolHandler.ACTION_ADJUST_FAN, "value" to "1", requires = 0),
            window("action" to "set", "window" to "front", "value" to "20"),
        )
        DROWSY -> listOf(
            climate("action" to ClimateToolHandler.ACTION_POWER_ON),
            climate("action" to ClimateToolHandler.ACTION_ADJUST_TEMPERATURE, "value" to "-2", requires = 0),
            window("action" to "set", "window" to "front", "value" to "20"),
            Step(MUSIC_TOOL, mapOf("action" to "play")),
        )
        else -> null
    }

    const val MUSIC_TOOL = "control_music"
}
