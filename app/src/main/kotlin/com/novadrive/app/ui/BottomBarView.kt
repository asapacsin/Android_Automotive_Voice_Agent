package com.novadrive.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.novadrive.app.DebugVoiceLog
import com.novadrive.app.R
import com.novadrive.app.ScreenAffordanceRunner
import com.novadrive.app.ScreenControls
import com.novadrive.app.vehicle.ClimateToolHandler
import com.novadrive.vehicle.ClimateLimits
import com.novadrive.vehicle.ClimateState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Media + climate bar. Every control does real work, and does it through [ScreenControls] — the
 * same validated route to the executors that a spoken command takes
 * ([I-6](../../../../../../../docs/INVARIANTS.md)). This view states intent and renders state; it
 * decides nothing and reaches no backend directly.
 */
class BottomBarView(context: Context) : LinearLayout(context) {
    private var scope: CoroutineScope? = null
    private var controls: ScreenControls? = null

    private val playPause: TextView
    private val climateLabel: TextView

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setBackgroundColor(Color.parseColor("#E6121820"))
        isClickable = true

        // No separate 「音乐」 label: the bar is phone-width and the climate readout needs the room.
        addView(control(context.getString(R.string.bottom_bar_prev), R.string.voice_music_restart) { act("restart") { it.restartMusic() } })
        playPause = control(context.getString(R.string.bottom_bar_play), R.string.voice_music_play) { act("toggle_music") { it.toggleMusic() } }
        addView(playPause)
        // One bundled track: there is no "next". Shown disabled rather than as a button that does nothing.
        addView(
            control(context.getString(R.string.bottom_bar_next)) {}.apply {
                isEnabled = false
                isClickable = false
                alpha = DISABLED_ALPHA
            },
        )
        addView(control(context.getString(R.string.bottom_bar_temp_down), R.string.voice_temp_down) { adjustTemperature(-ClimateLimits.DEFAULT_TEMPERATURE_STEP_C) })
        climateLabel =
            label("", 14f).apply {
                gravity = Gravity.CENTER
                // One line: wrapping split "24°C" into "2" / "4°C" on a phone-width bar.
                maxLines = 1
                isSingleLine = true
                setPadding(dp(2), dp(4), dp(2), dp(4))
                setOnClickListener { togglePower() }
            }
        addView(climateLabel, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(control(context.getString(R.string.bottom_bar_temp_up), R.string.voice_temp_up) { adjustTemperature(ClimateLimits.DEFAULT_TEMPERATURE_STEP_C) })
        // Recentre sits next to the camera rather than floating over the map: the map's own
        // corners are taken by the assistant overlay, the settings entry and the camera window.
        val recenter =
            Button(context).apply {
                text = context.getString(R.string.map_recenter_button)
                textSize = 18f
                contentDescription = context.getString(R.string.map_recenter_description)
                setOnClickListener { act("recenter", climateFailure = false) { it.recenter() } }
            }
        addView(recenter, LayoutParams(dp(56), LayoutParams.WRAP_CONTENT))
        val camera =
            Button(context).apply {
                text = context.getString(R.string.camera_button)
                textSize = 18f
                contentDescription = ScreenAffordanceRunner.names(context.getString(R.string.voice_camera)).first()
                setOnClickListener { act("camera", climateFailure = false) { it.toggleCamera() } }
            }
        addView(camera, LayoutParams(dp(72), LayoutParams.WRAP_CONTENT))
    }

    /** The one way this bar reaches anything outside itself. */
    fun bind(screenControls: ScreenControls) {
        controls = screenControls
        renderClimate(screenControls.climate.value)
        if (isAttachedToWindow) startCollecting()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startCollecting()
        ScreenAffordances.shared.publish(AFFORDANCE_SOURCE, spokenControls())
    }

    override fun onDetachedFromWindow() {
        ScreenAffordances.shared.withdraw(AFFORDANCE_SOURCE)
        scope?.cancel()
        scope = null
        super.onDetachedFromWindow()
    }

    private fun startCollecting() {
        scope?.cancel()
        val created = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = created
        val bound = controls ?: return
        created.launch {
            bound.musicPlaying.collect { playing ->
                playPause.text = context.getString(if (playing) R.string.bottom_bar_pause else R.string.bottom_bar_play)
            }
        }
        created.launch { bound.climate.collect(::renderClimate) }
    }

    /**
     * SPEC-010 B1: what the driver may say for each control. ⏭ is disabled and not published, so
     * 「下一首」 still reaches the model's honest refusal.
     */
    private fun spokenControls(): List<Affordance> = listOf(
        ScreenAffordanceRunner.MUSIC_RESTART to R.string.voice_music_restart,
        ScreenAffordanceRunner.MUSIC_PLAY to R.string.voice_music_play,
        ScreenAffordanceRunner.MUSIC_STOP to R.string.voice_music_stop,
        ScreenAffordanceRunner.TEMP_DOWN to R.string.voice_temp_down,
        ScreenAffordanceRunner.TEMP_UP to R.string.voice_temp_up,
        ScreenAffordanceRunner.CLIMATE to R.string.voice_climate,
        ScreenAffordanceRunner.RECENTER to R.string.voice_recenter,
        ScreenAffordanceRunner.CAMERA to R.string.voice_camera,
    ).map { (id, names) -> Affordance(id, ScreenAffordanceRunner.names(context.getString(names))) }

    private fun togglePower() = act("climate_power") { it.toggleClimatePower() }

    private fun adjustTemperature(delta: Double) = act("climate_temperature") { it.adjustTemperature(delta) }

    /**
     * Runs one screen action and shows the driver the same truth the voice path would: the label
     * changes only when the executor says the action succeeded, never because the button was
     * pressed.
     */
    private fun act(
        name: String,
        climateFailure: Boolean = true,
        action: suspend (ScreenControls) -> ScreenControls.Outcome,
    ) {
        val bound = controls ?: return
        scope?.launch {
            val outcome = action(bound)
            DebugVoiceLog.log("screen_action=$name ok=${outcome.ok} error=${outcome.errorCode ?: "-"}")
            if (!outcome.ok && climateFailure) climateLabel.text = context.getString(R.string.bottom_bar_climate_failed)
        }
    }

    private fun renderClimate(state: ClimateState) {
        val temperature = ClimateToolHandler.formatTemperature(state.targetTemperatureCelsius)
        climateLabel.text =
            if (state.powerOn) {
                context.getString(R.string.bottom_bar_climate_on, temperature, state.fanLevel)
            } else {
                context.getString(R.string.bottom_bar_climate_off, temperature)
            }
    }

    private fun label(text: String, size: Float): TextView =
        TextView(context).apply {
            this.text = text
            textSize = size
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            isClickable = true
            isFocusable = true
        }

    private fun control(text: String, spoken: Int? = null, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            spoken?.let { contentDescription = ScreenAffordanceRunner.names(context.getString(it)).first() }
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background =
                GradientDrawable().apply {
                    setColor(Color.parseColor("#33FFFFFF"))
                    cornerRadius = dp(8).toFloat()
                }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(4)
            }
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val DISABLED_ALPHA = 0.35f
        const val AFFORDANCE_SOURCE = "bottom_bar"
    }
}
