package com.novadrive.app.nav.amap

import android.content.Context
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Typeface
import android.os.SystemClock
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.novadrive.app.AmapSettingsRepository
import com.novadrive.app.nav.DestinationCandidate
import com.novadrive.app.nav.NavigationPhase
import com.novadrive.app.nav.RouteCandidate
import com.novadrive.app.nav.StaticMapModel
import com.novadrive.app.nav.StaticMapModel.GeoPoint
import com.novadrive.app.nav.TextNavigationPanelModel

/**
 * Plain-View stand-in for the map on a translated-ABI build (x86 emulator), where the Amap GL
 * surface cannot run. It only keeps the last values it was handed for display — the flow state
 * belongs to the controller, progress to the SDK listener — and renders them through
 * [TextNavigationPanelModel]. Nothing here is logged.
 *
 * Beside the text, a picture from the Amap Web Service static map API ([StaticMapModel]) with the
 * route, the car and the destination or candidates, using the Web key saved in settings. Without
 * a key the text stays and a one-line hint says why there is no map.
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
    @Volatile private var car: GeoPoint? = null
    @Volatile private var webKey: String? = null
    @Volatile private var keyChecked = false
    @Volatile private var mapNote: String? = null
    private var routeCacheKey: Any? = null
    private var routeCache: List<List<GeoPoint>> = emptyList()
    private val throttle = StaticMapModel.Throttle()
    private val mapImage = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(Color.parseColor("#16202A"))
    }
    private val fetcher = StaticMapFetcher { bitmap: Bitmap?, error: String? ->
        post {
            if (bitmap != null) {
                mapImage.setImageBitmap(bitmap)
                mapNote = null
            } else {
                mapNote = "地图加载失败（$error）"
            }
            render()
        }
    }

    /** Supplied by the host: whether it is navigating (the debug path bypasses the controller). */
    var navigating: () -> Boolean = { false }
    var limitKmh: () -> Int = { 0 }

    init {
        val portrait = resources.displayMetrics.heightPixels > resources.displayMetrics.widthPixels
        orientation = if (portrait) VERTICAL else HORIZONTAL
        setBackgroundColor(Color.parseColor("#0E141B"))
        val pad = (20 * resources.displayMetrics.density).toInt()
        val text = LinearLayout(context).apply {
            orientation = VERTICAL
            // Clears the assistant overlay's avatar and reply bubble, which sit above this view.
            setPadding(pad, if (portrait) pad * 9 else pad * 11, pad, pad / 2)
            addView(title)
            addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = pad / 2 })
        }
        if (portrait) {
            addView(text, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(mapImage, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        } else {
            addView(text, LayoutParams(0, LayoutParams.MATCH_PARENT, 2f))
            addView(mapImage, LayoutParams(0, LayoutParams.MATCH_PARENT, 3f))
        }
        mapImage.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) post { render() }
        }
        Thread({
            webKey = runCatching { AmapSettingsRepository(context).loadWebKey() }.getOrNull()
            keyChecked = true
            post { render() }
        }, "nova-static-map-key").start()
    }

    /** Latest vehicle position (GCJ-02) from the host: SDK fix, or the emulator car. */
    fun onCar(latitude: Double, longitude: Double) {
        if (!latitude.isFinite() || !longitude.isFinite()) return
        car = GeoPoint(latitude, longitude)
        post { refreshMap() }
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
            val note = if (keyChecked && webKey == null) "地图需要高德 Web 服务 Key（在设置中填写）" else mapNote
            body.text = (lines.body + listOfNotNull(note)).joinToString("\n")
            refreshMap()
        }
    }

    /** Main thread. Asks for a new picture only when [StaticMapModel.Throttle] says it changed. */
    private fun refreshMap() {
        val key = webKey ?: return
        val w = mapImage.width
        val h = mapImage.height
        if (w <= 0 || h <= 0) return
        val scene = scene()
        if (scene.empty) return
        if (!throttle.shouldFetch(scene, w, h, SystemClock.elapsedRealtime())) return
        fetcher.request(StaticMapModel.url(scene, key, w, h))
    }

    private fun scene(): StaticMapModel.Scene {
        val nav = navigating()
        val shown = if (nav) NavigationPhase.NAVIGATING else phase
        val preview = shown == NavigationPhase.AWAITING_ROUTE_SELECTION || shown == NavigationPhase.ROUTE_READY
        val routeKey = listOf(shown, routes.map { it.routeId })
        if (routeKey != routeCacheKey) {
            routeCacheKey = routeKey
            routeCache = if (nav || preview) AmapRouteGeometry.routes(context, navigating = nav) else emptyList()
        }
        val lines = routeCache
        return when {
            nav -> StaticMapModel.Scene(car, lines.firstOrNull()?.lastOrNull(), routes = lines.take(1), followCar = car != null)
            preview -> StaticMapModel.Scene(car, lines.firstOrNull()?.lastOrNull(), routes = lines)
            shown == NavigationPhase.AWAITING_DESTINATION_SELECTION ->
                StaticMapModel.Scene(car, null, candidates = destinations.map { GeoPoint(it.latitude, it.longitude) })
            else -> StaticMapModel.Scene(car, null, followCar = true)
        }
    }
}
