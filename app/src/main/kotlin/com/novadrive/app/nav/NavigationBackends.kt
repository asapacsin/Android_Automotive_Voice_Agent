package com.novadrive.app.nav

/**
 * The navigation backend seam: destination search + route engine. Production uses Amap
 * ([LiveDestinationCandidateSource], [GatewayNaviEngine]); the benchmark swaps in a simulated
 * world without the controller, the UI list or the tools knowing.
 */
interface NavigationBackend : DestinationCandidateSource, NaviEngine

object NavigationBackends {
    /** Non-null while a simulated world replaces Amap (debug benchmark runs only). */
    @Volatile var simulated: NavigationBackend? = null
        private set

    private val switches = java.util.concurrent.CopyOnWriteArrayList<SwitchingNavigationBackend>()

    fun useSimulation(world: NavigationBackend) {
        simulated = world
        switches.forEach { it.bind(world) }
    }

    fun useLive() {
        simulated = null
    }

    internal fun register(switch: SwitchingNavigationBackend) {
        switches += switch
        simulated?.let(switch::bind)
    }
}

/**
 * Forwards to the simulated world when one is set, otherwise to the live backend. Callbacks are
 * attached to both, so whichever engine is in use reaches the controller.
 */
class SwitchingNavigationBackend(
    private val liveSource: DestinationCandidateSource,
    private val liveEngine: NaviEngine,
) : NavigationBackend {
    private var onSuccess: ((IntArray) -> Unit)? = null
    private var onFailure: ((Int) -> Unit)? = null
    private var onEnded: ((String) -> Unit)? = null

    init {
        NavigationBackends.register(this)
    }

    internal fun bind(world: NavigationBackend) {
        val s = onSuccess
        val f = onFailure
        if (s != null && f != null) world.attachRouteCallbacks(s, f)
        onEnded?.let(world::attachNavigationEndedCallback)
    }

    private val engine: NaviEngine get() = NavigationBackends.simulated ?: liveEngine

    override suspend fun resolve(query: String): List<DestinationCandidate> =
        (NavigationBackends.simulated ?: liveSource).resolve(query)

    override fun calculateDriveRoute(endLat: Double, endLon: Double, endName: String, strategy: Int): Boolean =
        engine.calculateDriveRoute(endLat, endLon, endName, strategy)

    override fun selectRoute(routeId: Int): Boolean = engine.selectRoute(routeId)

    override fun startNavigation(emulator: Boolean): Boolean = engine.startNavigation(emulator)

    override fun stopNavigation(reason: String): Boolean = engine.stopNavigation(reason)

    override fun routeCandidates(): List<RouteCandidate> = engine.routeCandidates()

    override fun attachRouteCallbacks(onSuccess: (IntArray) -> Unit, onFailure: (Int) -> Unit) {
        this.onSuccess = onSuccess
        this.onFailure = onFailure
        liveEngine.attachRouteCallbacks(onSuccess, onFailure)
        NavigationBackends.simulated?.attachRouteCallbacks(onSuccess, onFailure)
    }

    override fun attachNavigationEndedCallback(onEnded: (reason: String) -> Unit) {
        this.onEnded = onEnded
        liveEngine.attachNavigationEndedCallback(onEnded)
        NavigationBackends.simulated?.attachNavigationEndedCallback(onEnded)
    }
}
