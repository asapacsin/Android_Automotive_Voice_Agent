package com.novadrive.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.novadrive.app.R
import com.novadrive.app.voice.ListeningState
import com.novadrive.ingress.realtime.VoiceUiState

class AssistantOverlayView(context: Context) : FrameLayout(context) {
    var onOpenDeveloperSettings: (() -> Unit)? = null

    /** Tap on the listening indicator: stop listening when active, otherwise start. */
    var onListeningToggle: (() -> Unit)? = null

    private var listening: ListeningState = ListeningState.DEEP_IDLE

    private var lastVoiceState: VoiceUiState = VoiceUiState.DISCONNECTED

    /** True while native Amap HUD owns the top of the screen. */
    private var drivingChrome = false

    private val avatar: TextView
    private val stateDot: TextView
    private val stateLabel: TextView
    private val bubble: TextView
    private val actionCard: TextView
    private val settingsEntry: TextView

    init {
        isClickable = false
        isFocusable = false

        avatar =
            TextView(context).apply {
                text = context.getString(R.string.assistant_avatar)
                textSize = 22f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                background =
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor("#FF1565C0"))
                    }
                isClickable = true
                setOnClickListener { onOpenDeveloperSettings?.invoke() }
            }
        stateDot =
            TextView(context).apply {
                text = "●"
                textSize = 14f
                setTextColor(Color.parseColor("#B0BEC5"))
                isClickable = false
            }
        stateLabel =
            TextView(context).apply {
                text = context.getString(R.string.assistant_state_idle)
                textSize = 13f
                setTextColor(Color.WHITE)
                setPadding(dp(6), 0, 0, 0)
                isClickable = false
            }
        bubble =
            TextView(context).apply {
                text = context.getString(R.string.assistant_speech_placeholder)
                textSize = 15f
                setTextColor(Color.WHITE)
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background =
                    GradientDrawable().apply {
                        setColor(Color.parseColor("#CC263238"))
                        cornerRadius = dp(12).toFloat()
                    }
                isClickable = true
            }
        actionCard =
            TextView(context).apply {
                textSize = 15f
                setTextColor(Color.WHITE)
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background =
                    GradientDrawable().apply {
                        setColor(Color.parseColor("#DD1B5E20"))
                        cornerRadius = dp(12).toFloat()
                    }
                visibility = GONE
                isClickable = true
            }
        settingsEntry =
            TextView(context).apply {
                text = context.getString(R.string.developer_settings)
                textSize = 12f
                setTextColor(Color.WHITE)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background =
                    GradientDrawable().apply {
                        setColor(Color.parseColor("#99000000"))
                        cornerRadius = dp(8).toFloat()
                    }
                isClickable = true
                setOnClickListener { onOpenDeveloperSettings?.invoke() }
            }

        val stateRow =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(stateDot)
                addView(stateLabel)
                isClickable = true
                setPadding(0, dp(4), dp(4), dp(4))
                setOnClickListener { onListeningToggle?.invoke() }
            }
        val avatarColumn =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(avatar, LinearLayout.LayoutParams(dp(64), dp(64)))
                addView(
                    stateRow,
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(6)
                    },
                )
                isClickable = false
            }
        val topRow =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                setPadding(dp(16), dp(16), dp(16), 0)
                addView(avatarColumn)
                addView(
                    bubble,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        leftMargin = dp(12)
                    },
                )
                isClickable = false
            }
        addView(
            topRow,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.START
            },
        )
        addView(
            actionCard,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                bottomMargin = dp(96)
            },
        )
        addView(
            settingsEntry,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dp(16)
                rightMargin = dp(16)
            },
        )
    }

    /**
     * Whether microphone audio goes to the cloud. SILENT_WAIT, SLEEP and DEEP_IDLE replace the
     * turn-state label so the driver can always tell "listening" from "asleep".
     */
    fun bindListening(state: ListeningState) {
        listening = state
        bindState(lastVoiceState, null)
    }

    /** Shrink the assistant chrome so Amap's native turn HUD is not covered. */
    fun setDrivingChrome(driving: Boolean) {
        if (drivingChrome == driving) return
        drivingChrome = driving
        bindState(lastVoiceState, null)
    }

    fun bindState(state: VoiceUiState, error: String?) {
        lastVoiceState = state
        val ui = AssistantUiStateMapper.from(state)
        if (drivingChrome) {
            settingsEntry.visibility = GONE
            bubble.maxLines = 1
        } else {
            settingsEntry.visibility = VISIBLE
            bubble.maxLines = Int.MAX_VALUE
        }
        if (listening != ListeningState.ACTIVE && ui != AssistantUiState.ERROR) {
            stateLabel.text = context.getString(
                when (listening) {
                    ListeningState.SILENT_WAIT -> R.string.assistant_listening_silent
                    ListeningState.SLEEP -> R.string.assistant_listening_sleep
                    else -> R.string.assistant_listening_deep_idle
                },
            )
            stateDot.setTextColor(
                Color.parseColor(
                    when (listening) {
                        ListeningState.SILENT_WAIT -> "#FF66BB6A"
                        ListeningState.SLEEP -> "#FF78909C"
                        else -> "#FF455A64"
                    },
                ),
            )
            actionCard.visibility = GONE
            return
        }
        stateLabel.text = if (ui == AssistantUiState.LISTENING) {
            context.getString(R.string.assistant_listening_active)
        } else {
            AssistantUiStateMapper.displayLabel(ui)
        }
        stateDot.setTextColor(
            when (ui) {
                AssistantUiState.LISTENING -> Color.parseColor("#FF4CAF50")
                AssistantUiState.PROCESSING -> Color.parseColor("#FFFFC107")
                AssistantUiState.RESPONDING -> Color.parseColor("#FF42A5F5")
                AssistantUiState.ERROR -> Color.parseColor("#FFEF5350")
                else -> Color.parseColor("#B0BEC5")
            },
        )
        if (ui == AssistantUiState.ERROR && !error.isNullOrBlank()) {
            bubble.text = error
        }
        actionCard.visibility = GONE
    }

    fun appendTranscript(line: String) {
        val current = bubble.text?.toString().orEmpty()
        val previous = if (current == context.getString(R.string.assistant_speech_placeholder)) "" else current
        bubble.text = recentTranscript(previous, line)
    }

    fun showError(code: String, message: String) {
        bubble.text = "$code\n$message"
        stateLabel.text = context.getString(R.string.assistant_state_error)
        stateDot.setTextColor(Color.parseColor("#FFEF5350"))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = false

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

/** Number of transcript lines the bubble keeps: the latest exchange only (driver + 小诺). */
internal const val TRANSCRIPT_MAX_LINES = 2

/** Appends [line] and keeps only the last [TRANSCRIPT_MAX_LINES] lines, so the bubble never grows. */
internal fun recentTranscript(current: String, line: String, maxLines: Int = TRANSCRIPT_MAX_LINES): String =
    (current.lines().filter { it.isNotBlank() } + line).takeLast(maxLines).joinToString("\n")
