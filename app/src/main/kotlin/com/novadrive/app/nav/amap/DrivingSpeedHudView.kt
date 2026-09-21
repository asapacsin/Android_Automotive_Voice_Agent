package com.novadrive.app.nav.amap

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.novadrive.app.R

/**
 * Current speed + posted limit, filled from [DrivingSpeedHud] (Amap location/cameras).
 * Not a second navi engine: AMapNaviView has no speedometer of its own.
 */
internal class DrivingSpeedHudView(context: Context) : LinearLayout(context) {
    private val speedValue: TextView
    private val limitValue: TextView

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(Color.parseColor("#CC121820"))
        }
        visibility = GONE

        speedValue = TextView(context).apply {
            textSize = 28f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        addView(speedValue)

        addView(
            TextView(context).apply {
                text = context.getString(R.string.nav_speed_unit)
                textSize = 11f
                setTextColor(Color.parseColor("#B0BEC5"))
                gravity = Gravity.CENTER
            },
        )

        limitValue = TextView(context).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#FFECB3"))
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }
        addView(limitValue)
    }

    fun bind(snapshot: DrivingSpeedHud.Snapshot) {
        if (!snapshot.visible) {
            visibility = GONE
            return
        }
        visibility = VISIBLE
        speedValue.text = snapshot.speedKmh.toString()
        speedValue.setTextColor(if (snapshot.overspeed) Color.parseColor("#FFEF5350") else Color.WHITE)
        limitValue.text = if (snapshot.limitKmh > 0) {
            context.getString(R.string.nav_speed_limit, snapshot.limitKmh)
        } else {
            context.getString(R.string.nav_speed_limit_unknown)
        }
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(
                if (snapshot.overspeed) Color.parseColor("#CCB71C1C") else Color.parseColor("#CC121820"),
            )
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
