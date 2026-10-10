package com.novadrive.app.livedemo

import com.novadrive.app.AndroidToolDispatcher
import com.novadrive.app.AmapPoiClient
import com.novadrive.app.CoreActionExecutor
import com.novadrive.app.DebugVoiceLog
import com.novadrive.app.GeminiAppSettings
import com.novadrive.app.GeminiSettingsValidator
import com.novadrive.app.LiveInfoTool
import com.novadrive.app.NavigationState
import com.novadrive.app.PersonaProfiles
import com.novadrive.app.QwenAppSettings
import com.novadrive.app.QwenSettingsValidator
import com.novadrive.app.SpeakingStyle
import com.novadrive.app.SpeakingStyleState
import com.novadrive.app.media.HandoffResult
import com.novadrive.app.media.MusicHandoffTool
import com.novadrive.app.media.MusicRequest
import com.novadrive.app.media.NowPlaying
import com.novadrive.app.media.PauseResult
import com.novadrive.app.media.PlaybackCheck
import com.novadrive.app.nav.EmbeddedNavigation
import com.novadrive.app.nav.EmbeddedNavigationController
import com.novadrive.app.sim.FixtureVision
import com.novadrive.app.sim.SimulatedCamera
import com.novadrive.app.sim.SimulatedMusic
import com.novadrive.app.vehicle.ClimateToolHandler
import com.novadrive.app.vision.CameraQuestionHandler
import com.novadrive.app.voice.MicInputGain
import com.novadrive.app.voice.RealtimeProviderFactory
import com.novadrive.app.voice.SessionProviderConfig
import com.novadrive.app.voice.SpeechUplinkGate
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.InMemoryMicrophonePort
import com.novadrive.ingress.realtime.ToolDispatchResult
import com.novadrive.ingress.realtime.VoiceSessionCallbacks
import com.novadrive.simulator.SimulatedVehicleControl
import com.novadrive.vehicle.SeatId
import com.novadrive.vehicle.WindowId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import com.novadrive.ingress.realtime.VoiceSessionController as CoreSession

/**
 * The commute demo (docs/DEMO_COMMUTE.md) recorded on the JVM against the LIVE model: the real
 * provider client, the core session and the app's tool dispatch, with a simulated car and route.
 * Skipped unless NOVA_LIVE_DEMO=1, so `gradlew test` never touches the network. See
 * tools/demo/live/README.md. Never prints a key: only the names of missing variables.
 */
class LiveDemoRun {
    @Test
    fun record() {
        assumeTrue(System.getenv("NOVA_LIVE_DEMO") == "1", "live demo runs only with NOVA_LIVE_DEMO=1")
        val outPath = requireNotNull(System.getenv("NOVA_LIVE_OUT")?.takeIf { it.isNotBlank() }) { "NOVA_LIVE_OUT is not set" }
        val out = File(outPath).absoluteFile.apply { mkdirs() }
        val choice = System.getenv("NOVA_LIVE_PROVIDER")?.ifBlank { null } ?: "qwen"
        val scenes = System.getenv("NOVA_LIVE_SCENES")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: SCENES.keys.toList()
        scenes.forEach { require(it in SCENES) { "unknown scene $it" } }
        LiveDemo(out, choice).use { it.run(scenes) }
    }

    companion object {
        val SCENES: LinkedHashMap<String, (LiveDemo) -> Unit> = linkedMapOf(
            "commute_board" to { s ->
                s.timeline.title("1  上车：换个说话方式，调座椅", "Getting in: a sweeter voice, the seat higher, then higher again")
                s.turn("c_sweet"); s.turn("c_seat"); s.turn("c_seat2")
            },
            "commute_depart" to { s ->
                s.timeline.title("2  出发：导航去横琴镇", "Navigate to 横琴镇: choose the place, start; the car drives (simulated)")
                s.turn("c_nav", 40.0); s.turn("c_pick", 40.0); s.turn("c_go", 40.0)
                s.world.speedKmh = 40.0
                Thread.sleep(4_000)
            },
            "commute_mosq" to { s ->
                s.timeline.title("3  路上：有蚊子", "A mosquito: she opens the windows; it stays, and she does something about it")
                s.turn("c_mosq"); s.turn("c_mosq2")
            },
            "commute_music" to { s ->
                s.timeline.title("4  路上：说不全的歌名", "A song she has to work out from a vague description")
                s.turn("c_music", 40.0)
            },
            "commute_ask" to { s ->
                s.timeline.title("5  路上：问路况、天气，关窗", "Traffic ahead, the weather here, and close the windows")
                s.turn("c_traffic", 40.0); s.turn("c_weather", 40.0); s.turn("c_close")
            },
            "commute_arrive" to { s ->
                s.timeline.title("6  到达横琴镇", "Arrival")
                s.world.speedKmh = 120.0
                s.timeline.mark("cut_start")
                val begin = System.currentTimeMillis()
                while (System.currentTimeMillis() - begin < 150_000 && !s.world.arrived) Thread.sleep(500)
                s.timeline.mark("cut_end")
                if (!s.world.arrived) s.turn("c_end")
                Thread.sleep(6_000)
                s.snapshot("arrive")
            },
        )
    }
}

/** One live session: the composition, the real-time microphone and the per-turn state log. */
class LiveDemo(private val out: File, private val choice: String) : AutoCloseable {
    private val repoRoot = File(System.getProperty("nova.repo.root") ?: "..")
    val timeline = LiveDemoTimeline(out, File(repoRoot, "tools/demo/recorder/clips"))
    val world = HengqinWorld()
    private val vehicle = SimulatedVehicleControl()
    private val music = SimulatedMusic()
    private val handoff = SimulatedHandoff()
    private val log = File(out, "logcat.txt").bufferedWriter(Charsets.UTF_8)
    private val mainThread = Executors.newSingleThreadExecutor { r -> Thread(r, "livedemo-main").apply { isDaemon = true } }
    private val scope = CoroutineScope(SupervisorJob() + mainThread.asCoroutineDispatcher())
    private val mic = InMemoryMicrophonePort()
    private val gate = SpeechUplinkGate()
    private val gain = MicInputGain()
    private val nav = EmbeddedNavigationController(resolver = world, engine = world)
    private lateinit var core: CoreSession
    private lateinit var playback: LiveDemoTimeline.Playback
    @Volatile private var running = true

    private val turnTools = JSONArray()
    private val turnTranscripts = mutableListOf<String>()
    private val turns = JSONArray()
    private val track = JSONArray()

    init {
        DebugVoiceLog.sink = { msg ->
            val line = String.format(Locale.US, "%.3f D NovaVoice: %s\n", System.currentTimeMillis() / 1000.0, msg)
            synchronized(log) { log.write(line); log.flush() }
        }
    }

    private fun providerConfig(): SessionProviderConfig {
        val instructions = PersonaProfiles.DEFAULT_INSTRUCTIONS
        fun env(name: String) = System.getenv(name)?.takeIf { it.isNotBlank() } ?: error("missing environment variable $name")
        return when (choice) {
            "qwen" -> SessionProviderConfig.Qwen(
                QwenSettingsValidator.configOrThrow(
                    QwenAppSettings(consentAccepted = true, workspaceId = env("DASHSCOPE_WORKSPACE_ID"), voice = QwenAppSettings.DEFAULT_VOICE),
                    env("DASHSCOPE_API_KEY"), instructions,
                ),
            )
            // Gemini speaks in its own voice: no assistant (Azure) voice.
            "gemini" -> SessionProviderConfig.Gemini(
                GeminiSettingsValidator.configOrThrow(GeminiAppSettings(consentAccepted = true), env("GEMINI_API_KEY"), instructions)
                    .copy(assistantVoice = null),
            )
            else -> error("NOVA_LIVE_PROVIDER must be qwen or gemini")
        }
    }

    private fun dispatcher(): AndroidToolDispatcher {
        val liveInfo = LiveInfoTool(
            webKey = { System.getenv("AMAP_WEB_KEY") },
            rest = AmapPoiClient(),
            // LiveInfoTool takes the WGS-84 fix; the car stays in the origin's district on this route.
            location = { HengqinWorld.ORIGIN_LAT to HengqinWorld.ORIGIN_LON },
            navigation = { nav },
            route = world,
        )
        liveInfo.warmHere()
        return AndroidToolDispatcher(
            CoreActionExecutor(navigationFlow = nav, music = { music }),
            ClimateToolHandler(vehicle),
            CameraQuestionHandler(surface = { SimulatedCamera() }, vision = FixtureVision()),
            liveInfo = liveInfo,
            cabin = vehicle,
            music = handoff,
        )
    }

    private fun onToolCall(dispatcher: AndroidToolDispatcher, call: DomainVoiceEvent.ToolCall): ToolDispatchResult {
        val result = dispatcher.dispatch(call)
        DebugVoiceLog.log("tool=${call.name} args=${call.arguments.keys} result=${result.successChip ?: result.blockedReason}")
        val record = JSONObject().put("name", call.name).put("t", timeline.now())
        val immediateOk = result.blockedReason == null && result.output?.contains("\"ok\":false") != true
        val deferred = result.deferredOutput
        synchronized(turnTools) { turnTools.put(record.put("ok", immediateOk).put("pending", deferred != null)) }
        if (deferred == null) return result
        return result.copy(deferredOutput = {
            val output = deferred()
            synchronized(turnTools) { record.put("ok", !output.contains("\"ok\":false")).put("pending", false) }
            output
        })
    }

    fun run(scenes: List<String>) {
        NavigationState.reset()
        SpeakingStyleState.restore(SpeakingStyle.DEFAULT)
        setSharedNavigation(nav)
        DebugVoiceLog.log("session_provider choice=$choice")
        val dispatcher = dispatcher()
        val built = RealtimeProviderFactory.build(
            providerConfig(),
            lastAudioSegment = { gate.snapshot() },
            speechEvidence = { gate.hasRecentSpeech() },
            repliesSpoken = { true },
        )
        providerRef = built.provider
        playback = timeline.Playback(built.outputSampleRateHz)
        core = CoreSession(
            provider = built.provider,
            microphone = mic,
            playback = playback,
            scope = scope,
            config = built.session,
            callbacks = VoiceSessionCallbacks(
                onUiState = { state, error -> DebugVoiceLog.log("state=$state err=${error ?: "-"}") },
                onTranscript = { line -> synchronized(turnTranscripts) { turnTranscripts += line } },
                onError = { code, _ -> DebugVoiceLog.log("error=$code") },
                onToolCall = { call -> onToolCall(dispatcher, call) },
                onSessionLog = { line -> DebugVoiceLog.log(line) },
            ),
        )
        core.start()
        val deadline = System.currentTimeMillis() + 30_000
        while (!core.connectedNow && System.currentTimeMillis() < deadline) Thread.sleep(50)
        check(core.connectedNow) { "the live session did not connect within 30 s (see logcat.txt)" }
        Thread.sleep(3_000)
        timeline.start()
        Thread(::uplink, "livedemo-uplink").apply { isDaemon = true }.start()
        Thread(::observe, "livedemo-observe").apply { isDaemon = true }.start()
        try {
            scenes.forEach { LiveDemoRun.SCENES.getValue(it)(this) }
            Thread.sleep(2_000)
        } finally {
            val end = timeline.now()
            running = false
            Thread.sleep(300)
            timeline.render(end)
            writeState()
            println(String.format(Locale.US, "[live] done %.1f s", end))
        }
    }

    /** record_demo.turn: wake, say the clip, wait for the reply to play out, then 0.6 s. */
    fun turn(key: String, timeout: Double = 35.0) {
        DebugVoiceLog.log("debug_tool tool=voice arg=wake")
        timeline.wakes += timeline.epochNow()
        Thread.sleep(800)
        val t = timeline.queueClip(key)
        println(String.format(Locale.US, "[live] %7.2f say %s", t, key))
        Thread.sleep(timeline.clipPcm(key).size * 1000L / 2 / LiveDemoTimeline.RATE)
        timeline.waitReply(timeout = timeout)
        Thread.sleep(600)
        snapshot(key)
    }

    fun snapshot(key: String) {
        val cabin = vehicle.cabinState.value
        val climate = vehicle.climateState.value
        val tools = synchronized(turnTools) { JSONArray(turnTools.toString()) }
        val lines = synchronized(turnTranscripts) { turnTranscripts.toList() }
        turns.put(
            JSONObject().put("key", key).put("t", timeline.now())
                .put("tools", tools)
                .put("driver_heard", JSONArray(lines.filter { it.startsWith("你: ") }.map { it.removePrefix("你: ") }))
                .put("reply", JSONArray(lines.filter { it.startsWith("小诺: ") }.map { it.removePrefix("小诺: ") }))
                .put("car", carState(cabin.seatHeights[SeatId.DRIVER], cabin.windows, climate)),
        )
        synchronized(turnTools) { while (turnTools.length() > 0) turnTools.remove(0) }
        synchronized(turnTranscripts) { turnTranscripts.clear() }
    }

    private fun carState(seat: Int?, windows: Map<WindowId, Int>, climate: com.novadrive.vehicle.ClimateState) = JSONObject()
        .put("seat_height", seat)
        .put("windows", JSONObject(windows.mapKeys { it.key.name }))
        .put("fan", climate.fanLevel)
        .put("ac_on", climate.powerOn)
        .put("temp_c", climate.targetTemperatureCelsius)
        .put("music", handoff.lastRequest?.let { listOfNotNull(it.artist, it.title).joinToString(" · ").ifEmpty { it.query ?: "" } })
        .put("music_confirmed", false)
        .put("style", SpeakingStyleState.current.wireName)
        .put("nav_phase", nav.state().value.name)
        .put("destination", world.destinationName)
        .put("progress", world.progress)
        .put("remaining_m", world.remainingMeters)
        .put("speed_kmh", if (world.navigating) world.speedKmh else 0.0)

    /** The car's state twice a second for the renderer, and the player's activity for the provider. */
    private fun observe() {
        var wasPlaying = false
        var lastSample = 0.0
        while (running) {
            val playing = playback.playing
            if (playing != wasPlaying) {
                wasPlaying = playing
                runCatching { providerRef?.onPlaybackActiveChanged(playing) }
            }
            val t = timeline.now()
            if (t - lastSample >= 0.5) {
                lastSample = t
                val cabin = vehicle.cabinState.value
                synchronized(track) {
                    track.put(JSONObject().put("t", t).put("car", carState(cabin.seatHeights[SeatId.DRIVER], cabin.windows, vehicle.climateState.value)))
                }
            }
            Thread.sleep(50)
        }
    }

    @Volatile private var providerRef: com.novadrive.ingress.realtime.RealtimeVoiceProvider? = null

    /** 20 ms of audio every 20 ms of wall time, as two 10 ms capture frames through the app's uplink gate. */
    private fun uplink() {
        val half = LiveDemoTimeline.FRAME / 2
        var start = System.nanoTime()
        var n = 0L
        while (running) {
            val frame = timeline.nextFrame()
            for (k in 0..1) {
                val part = frame.copyOfRange(k * half, (k + 1) * half)
                val before = gate.isOpen
                val decision = gate.offer(part)
                val after = gate.isOpen
                decision.send.forEach { mic.emit(gain.process(it, 10)) }
                decision.finished?.let { DebugVoiceLog.log("UPLINK_GATE_SEGMENT durationMs=${it.durationMs} suspicious=${it.isSuspicious()}") }
                if (before != after) core.onLocalSpeechActivity(after)
            }
            n++
            val delayNs = start + n * 20_000_000L - System.nanoTime()
            if (delayNs > 0) Thread.sleep(delayNs / 1_000_000, (delayNs % 1_000_000).toInt())
            else if (delayNs < -200_000_000L) { start = System.nanoTime(); n = 0 }
        }
    }

    private fun writeState() {
        val json = JSONObject().put("provider", choice).put("turns", turns).put("track", synchronized(track) { track })
        File(out, "state.json").writeText(json.toString(1), Charsets.UTF_8)
    }

    /**
     * The provider clients read the screen context from the app-wide navigation owner
     * (VoiceContextHints -> EmbeddedNavigation.currentOrNull()), which has no test setter. The harness
     * points it at its own controller by reflection, so the model hears the same context as in the app.
     */
    private fun setSharedNavigation(controller: EmbeddedNavigationController?) {
        val field = EmbeddedNavigation::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(EmbeddedNavigation, controller)
    }

    override fun close() {
        running = false
        runCatching { core.stop() }
        runCatching { scope.cancel() }
        mainThread.shutdownNow()
        world.close()
        runCatching { setSharedNavigation(null) }
        NavigationState.reset()
        DebugVoiceLog.sink = null
        synchronized(log) { log.close() }
    }

    /** The music app hand-off: always sent, never confirmed playing (no music app, as on the emulator). */
    private class SimulatedHandoff : MusicHandoffTool {
        @Volatile var lastRequest: MusicRequest? = null
        override fun snapshot(): NowPlaying? = null
        override fun handOff(request: MusicRequest): HandoffResult {
            lastRequest = request
            handedOff = true
            return HandoffResult.Sent("sim.music")
        }
        override suspend fun await(request: MusicRequest, previous: NowPlaying?): PlaybackCheck = PlaybackCheck.Unverified
        override fun pauseActive(): PauseResult = PauseResult.NoAccess
        override suspend fun awaitStopped(): Boolean = true
        @Volatile override var handedOff: Boolean = false
            private set
        override val lastWaitedMs: Long get() = 0
    }
}
