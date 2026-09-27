package com.novadrive.app.nav.amap

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.widget.LinearLayout
import android.widget.TextView
import com.novadrive.app.nav.DestinationCandidate
import com.novadrive.app.nav.NavigationPhase
import com.novadrive.app.nav.RouteCandidate
import com.novadrive.app.nav.TextNavigationPanelModel

/**
 * Plain-View stand-in for the map on a translated-ABI build (x86 emulator), where the Amap GL
 * surface cannot run. It only keeps the last values it was handed for display — the flow state
 * belongs to the controller, progress to the SDK listener — and renders them through
 * [TextNavigationPanelModel]. Nothing here is logged.
 */
internal class TextNavigationPanel(context: Context) : LinearLayout(context) {
    private val title = TextView(context).apply {
        textSize = 26f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE)
    }
    private val body = TextView(context).apply {
        textSize = 22f
        setLineSpacing(0f, 1.25f)
        setTextColor(Color.parseColor("#DDE6EE"))
    }

    @Volatile private var phase = NavigationPhase.IDLE
    @Volatile private var destinations: List<DestinationCandidate> = emptyList()
    @Volatile private var routes: List<RouteCandidate> = emptyList()
    @Volatile private var destinationName: String? = null
    @Volatile private var progress: TextNavigationPanelModel.Progress? = null
    @Volatile private var guidanceText: String? = null

    /** Supplied by the host: whether it is navigating (the debug path bypasses the controller). */
    var navigating: () -> Boolean = { false }
    var limitKmh: () -> Int = { 0 }

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#0E141B"))
        val pad = (20 * resources.displayMetrics.density).toInt()
        // Clears the assistant overlay's avatar and reply bubble, which sit above this view.
        setPadding(pad, pad * 11, pad, pad)
        addView(title)
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = pad / 2 })
    }

    fun onState(
        phase: NavigationPhase,
        destinations: List<DestinationCandidate>,
        routes: List<RouteCandidate>,
        destinationName: String?,
    ) {
        if (phase != NavigationPhase.NAVIGATING) clearProgress()
        this.phase = phase
        this.destinations = destinations
        this.routes = routes
        this.destinationName = destinationName
        render()
    }

    fun onProgress(next: TextNavigationPanelModel.Progress) {
        if (next == progress) return
        progress = next
        render()
    }

    fun onGuidanceText(text: String) {
        guidanceText = text
        render()
    }

    fun clearProgress() {
        progress = null
        guidanceText = null
    }

    fun render() {
        val lines = TextNavigationPanelModel.render(
            phase = if (navigating()) NavigationPhase.NAVIGATING else phase,
            destinations = destinations,
            routes = routes,
            destinationName = destinationName,
            progress = progress,
            limitKmh = limitKmh(),
            guidanceText = guidanceText,
        )
        post {
            title.text = lines.title
            body.text = lines.body.joinToString("\n")
        }
    }
}
