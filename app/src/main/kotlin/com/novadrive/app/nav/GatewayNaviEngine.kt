package com.novadrive.app.nav

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Reaches the live [com.novadrive.app.nav.amap.AmapNaviViewHost] — never constructs a second one. */
class GatewayNaviEngine : NaviEngine {
    @Volatile
    private var onSuccess: ((IntArray) -> Unit)? = null

    @Volatile
    private var onFailure: ((Int) -> Unit)? = null

    @Volatile
    private var onEnded: ((String) -> Unit)? = null

    override fun attachRouteCallbacks(
        onSuccess: (IntArray) -> Unit,
        onFailure: (Int) -> Unit,
    ) {
        this.onSuccess = onSuccess
        this.onFailure = onFailure
    }

    override fun attachNavigationEndedCallback(onEnded: (reason: String) -> Unit) {
        this.onEnded = onEnded
    }

    override fun calculateDriveRoute(
        endLat: Double,
        endLon: Double,
        endName: String,
        strategy: Int,
    ): Boolean = onMain {
        val host = NavigationHostGateway.current() ?: return@onMain false
        host.setRouteCalculationCallbacks(onSuccess, onFailure)
        // Re-pushed here rather than at construction: the controller is a process-scoped
        // singleton that can outlive any one host, and this is the point where a live host
        // is guaranteed to exist for a controller-driven session.
        host.setNavigationEndedCallback(onEnded)
        host.calculateDriveRoute(endLat, endLon, endName, strategy)
    }

    override fun selectRoute(routeId: Int): Boolean = onMain {
        NavigationHostGateway.current()?.selectRoute(routeId) ?: false
    }

    override fun startNavigation(emulator: Boolean): Boolean = onMain {
        NavigationHostGateway.current()?.startNavigation(emulator) ?: false
    }

    override fun stopNavigation(reason: String): Boolean = onMain {
        NavigationHostGateway.current()?.stopNavigation(reason) ?: false
    }

    override fun routeCandidates(): List<RouteCandidate> =
        NavigationHostGateway.current()?.routeCandidates() ?: emptyList()

    private fun onMain(block: () -> Boolean): Boolean {
        val main = Looper.getMainLooper()
        if (Looper.myLooper() == main) return block()
        val box = booleanArrayOf(false)
        val latch = CountDownLatch(1)
        Handler(main).post {
            box[0] = block()
            latch.countDown()
        }
        if (!latch.await(5, TimeUnit.SECONDS)) return false
        return box[0]
    }
}
