package com.novadrive.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.novadrive.app.R

class BottomBarView(context: Context) : LinearLayout(context) {
    var onCameraClick: (() -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setBackgroundColor(Color.parseColor("#E6121820"))
        isClickable = true

        addView(inertLabel(context.getString(R.string.bottom_bar_music), 16f))
        addView(inertControl(context.getString(R.string.bottom_bar_prev)))
        addView(inertControl(context.getString(R.string.bottom_bar_pause)))
        addView(inertControl(context.getString(R.string.bottom_bar_next)))
        addView(
            inertLabel(context.getString(R.string.bottom_bar_climate), 16f),
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
        )
        val camera =
            Button(context).apply {
                text = context.getString(R.string.camera_button)
                textSize = 18f
                setOnClickListener { onCameraClick?.invoke() }
            }
        addView(camera, LayoutParams(dp(72), LayoutParams.WRAP_CONTENT))
    }

    private fun inertLabel(label: String, size: Float): TextView =
        TextView(context).apply {
            text = label
            textSize = size
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            isClickable = true
            isFocusable = true
        }

    private fun inertControl(label: String): TextView =
        TextView(context).apply {
            text = label
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
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
