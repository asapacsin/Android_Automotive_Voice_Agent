package com.novadrive.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.novadrive.app.R
import com.novadrive.app.nav.DestinationCandidate
import com.novadrive.app.nav.EmbeddedNavigationController
import com.novadrive.app.nav.NavigationFormatters
import com.novadrive.app.nav.RouteCandidate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Destination / route picker. Holds no navigation state — it renders the controller's
 * StateFlows and forwards taps to [EmbeddedNavigationController.selectDestination] /
 * [EmbeddedNavigationController.selectRoute] / [EmbeddedNavigationController.cancel].
 */
class NavigationChoiceOverlay(context: Context) : LinearLayout(context) {
    private var controller: EmbeddedNavigationController? = null
    private var collectJob: Job? = null

    private val title: TextView
    private val rows: LinearLayout
    private val cancel: TextView

    init {
        orientation = VERTICAL
        visibility = GONE
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background =
            GradientDrawable().apply {
                setColor(Color.parseColor("#E6121820"))
                cornerRadius = dp(12).toFloat()
            }

        title =
            TextView(context).apply {
                textSize = 16f
                setTextColor(Color.WHITE)
            }
        rows = LinearLayout(context).apply { orientation = VERTICAL }
        cancel =
            TextView(context).apply {
                text = context.getString(R.string.nav_choice_cancel)
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background =
                    GradientDrawable().apply {
                        setColor(Color.parseColor("#33FFFFFF"))
                        cornerRadius = dp(8).toFloat()
                    }
                isClickable = true
                isFocusable = true
                setOnClickListener { controller?.cancel() }
            }

        addView(title)
        addView(
            ScrollView(context).apply {
                addView(
                    rows,
                    LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
                )
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            },
        )
        addView(
            cancel,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            },
        )
    }

    fun bind(controller: EmbeddedNavigationController) {
        this.controller = controller
        if (isAttachedToWindow) startCollecting()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startCollecting()
    }

    override fun onDetachedFromWindow() {
        collectJob?.cancel()
        collectJob = null
        super.onDetachedFromWindow()
    }

    private fun startCollecting() {
        val controller = this.controller ?: return
        collectJob?.cancel()
        collectJob =
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
                combine(controller.destinationCandidates, controller.routeCandidates) { destinations, routes ->
                    destinations to routes
                }.collect { (destinations, routes) ->
                    render(destinations, routes)
                }
            }
    }

    private fun render(destinations: List<DestinationCandidate>, routes: List<RouteCandidate>) {
        rows.removeAllViews()
        when {
            destinations.isNotEmpty() -> {
                visibility = VISIBLE
                title.text = context.getString(R.string.nav_choice_pick_destination)
                destinations.forEach { candidate ->
                    rows.addView(destinationRow(candidate))
                }
            }
            routes.isNotEmpty() -> {
                visibility = VISIBLE
                title.text = context.getString(R.string.nav_choice_pick_route)
                routes.forEach { route ->
                    rows.addView(routeRow(route))
                }
            }
            else -> visibility = GONE
        }
    }

    private fun destinationRow(candidate: DestinationCandidate): TextView {
        val distance = candidate.distanceMeters?.let { " · ${NavigationFormatters.formatDistanceMeters(it)}" }.orEmpty()
        val subtitle = listOf(candidate.address, candidate.district).filter { it.isNotBlank() }.joinToString(" ")
        return choiceRow("${candidate.name}$distance\n$subtitle") {
            controller?.selectDestination(candidate.id)
        }
    }

    private fun routeRow(route: RouteCandidate): TextView {
        val distance = NavigationFormatters.formatDistanceMeters(route.distanceMeters)
        val duration = NavigationFormatters.formatDurationSeconds(route.durationSeconds)
        val label = route.labels?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
        return choiceRow("$distance · $duration$label") {
            controller?.selectRoute(route.routeId)
        }
    }

    private fun choiceRow(label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background =
                GradientDrawable().apply {
                    setColor(Color.parseColor("#22FFFFFF"))
                    cornerRadius = dp(8).toFloat()
                }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            val params = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            params.topMargin = dp(6)
            layoutParams = params
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
