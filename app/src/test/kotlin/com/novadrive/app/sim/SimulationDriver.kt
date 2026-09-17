package com.novadrive.app.sim

import com.novadrive.app.AndroidToolDispatcher
import com.novadrive.app.BaiduApiConfig
import com.novadrive.app.BaiduAppSettings
import com.novadrive.app.BaiduAuthMode
import com.novadrive.app.BaiduCredentials
import com.novadrive.app.BaiduRuntimeProvider
import com.novadrive.app.CoreActionExecutor
import com.novadrive.app.NavigationState
import com.novadrive.app.nav.EmbeddedNavigationController
import com.novadrive.app.vehicle.ClimateToolHandler
import com.novadrive.app.vision.CameraQuestionHandler
import com.novadrive.app.voice.BaiduFlexClient
import com.novadrive.app.voice.BaiduFlexProtocol
import com.novadrive.app.voice.BaiduFlexProvider
import com.novadrive.app.voice.VoiceContextHints
import com.novadrive.evaluation.EventType
import com.novadrive.evaluation.Fault
import com.novadrive.evaluation.ModelBehavior
import com.novadrive.evaluation.Scenario
import com.novadrive.evaluation.ScenarioDriver
import com.novadrive.evaluation.ScenarioExecutor
import com.novadrive.evaluation.Step
import com.novadrive.evaluation.Telemetry
import com.novadrive.evaluation.TelemetryRecorder
import com.novadrive.evaluation.TestMode
import com.novadrive.ingress.realtime.InMemoryMicrophonePort
import com.novadrive.ingress.realtime.PlaybackPort
import com.novadrive.ingress.realtime.RealtimeSessionConfig
import com.novadrive.ingress.realtime.VoiceCatalog
import com.novadrive.ingress.realtime.VoiceProviderId
import com.novadrive.ingress.realtime.VoiceSessionCallbacks
import com.novadrive.ingress.realtime.VoiceUiState
import com.novadrive.simulator.ClimateOperation
import com.novadrive.simulator.InjectedClimateFailure
import com.novadrive.simulator.SimulatedVehicleControl
import com.novadrive.vehicle.ClimateState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.novadrive.ingress.realtime.VoiceSessionController as CoreSession

/**
 * Level A driver: the shipped voice stack against the scripted server, in a simulated world.
 *
 * Real code under test: BaiduFlexClient + protocol + guards (false-claim, reply timing, empty
 * response, conversation reset), BaiduFlexProvider, the core session (turn state, tool work,
 * reconnect), AndroidToolDispatcher + CoreActionExecutor, the navigation state machine and choice
 * resolver, ClimateToolHandler + SimulatedVehicleControl, CameraQuestionHandler.
 * Replaced: the model (scripted), Amap (SimulatedNavigationWorld), audio devices, the vision model,
 * the music player. The listening lifecycle's Android glue is not part of this path.
 */
class SimulationDriver(
    private val recorder: TelemetryRecorder,
    /** Final quiescence window once every settle condition holds. */
    private val quietMs: Long = 40,
) : ScenarioDriver {
    override val mode = TestMode.SIM_LOGIC

    private lateinit var server: ScriptedRealtimeServer
    private lateinit var core: CoreSession
    private lateinit var client: BaiduFlexClient
    private lateinit var scope: CoroutineScope
    private var mainThread: java.util.concurrent.ExecutorService? = null
    private val world = SimulatedNavigationWorld()
    private val vehicle = SimulatedVehicleControl()
    private val music = SimulatedMusic()
    private val camera = SimulatedCamera()
    private val vision = FixtureVision()
    private var navigation: EmbeddedNavigationController? = null
    private var turn: Step.Say? = null
    private val clientCompletions = java.util.concurrent.atomic.AtomicInteger()
    private val completionCounter = com.novadrive.evaluation.TelemetrySink { e ->
        if (e.type == EventType.RESPONSE_COMPLETED) clientCompletions.incrementAndGet()
    }
    private var bargeInDelayMs = 0L

    private val probe = WorldProbe(
        climate = { vehicle.climateState.value },
        navigation = { navigation },
        world = world,
        musicPlaying = { music.isPlaying },
        cameraOpen = { camera.open },
        visionRequests = { vision.requests.get() },
        sessionAlive = { core.sessionActiveNow && core.machine.state != VoiceUiState.ERROR },
        sessionConnected = { core.connectedNow },
        sessionConnects = { server.connections.get() },
        visionCompleted = { vision.completed.get() },
    )

    override fun unsupported(step: Step): String? = when {
        step is Step.Inject && step.fault is Fault.BackgroundForeground -> "app lifecycle needs a phone"
        step is Step.Inject && step.fault is Fault.ToolResultDelay && (step.fault as Fault.ToolResultDelay).tool != "describe_camera_view" ->
            "only vision results can be delayed in SIM_LOGIC"
        else -> null
    }

    override suspend fun prepare(scenario: Scenario, seed: Long) {
        Telemetry.recorder = recorder
        recorder.removeSink(completionCounter)
        clientCompletions.set(0)
        recorder.addSink(completionCounter)
        bargeInDelayMs = 0
        NavigationState.reset()
        val setup = scenario.setup
        world.reset()
        vehicle.reset(ClimateState(setup.hvacPower, setup.temperature, setup.fan))
        music.reset(setup.musicPlaying)
        vision.reset()
        camera.open = setup.cameraOpen
        camera.fixture = setup.visionFixture
        val nav = EmbeddedNavigationController(resolver = world, engine = world)
        navigation = nav

        server = ScriptedRealtimeServer(seed)
        val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        client = BaiduFlexClient(http, 5_000, requireTls = false, contextHint = { VoiceContextHints.describe(nav, camera.open) })
        val provider = BaiduFlexProvider(config(server.endpoint()), client)
        val dispatcher = AndroidToolDispatcher(
            CoreActionExecutor(navigationFlow = nav, music = { music }),
            ClimateToolHandler(vehicle),
            CameraQuestionHandler(surface = { camera }, vision = vision),
        )
        mainThread = Executors.newSingleThreadExecutor { r -> Thread(r, "sim-main").apply { isDaemon = true } }
        scope = CoroutineScope(SupervisorJob() + mainThread!!.asCoroutineDispatcher())
        core = CoreSession(
            provider = provider,
            microphone = InMemoryMicrophonePort(),
            playback = RecordingPlayback(),
            scope = scope,
            config = RealtimeSessionConfig(VoiceProviderId.BAIDU_FLEX, VoiceCatalog.DEFAULT_MODEL),
            callbacks = VoiceSessionCallbacks(onToolCall = { call -> dispatcher.dispatch(call) }),
        )
        core.start()
        val deadline = System.currentTimeMillis() + 5_000
        while (!core.connectedNow && System.currentTimeMillis() < deadline) delay(10)
        check(core.connectedNow) { "simulated session did not connect" }
        recorder.record(EventType.SESSION_START)
    }

    override suspend fun deliver(turn: Step.Say, variant: String) {
        this.turn = turn
        val calls = if (turn.model == ModelBehavior.CUSTOM_CALLS) turn.modelCalls else turn.expect.tools
        server.userSays(ScriptedRealtimeServer.Turn(turn.utterance, calls, turn.model))
    }

    /**
     * Settled when, together and then for [quietMs] without any new event:
     * - the server has nothing running, scheduled or streaming, and no tool result is owed;
     * - the client has recorded as many response completions as the server sent (so the last
     *   burst has been received and processed, not merely sent);
     * - the session has no tool work pending;
     * - the client has no turn, held message or conversation reset of its own in flight.
     * The timeout makes a broken scenario fail instead of hang.
     */
    override suspend fun awaitSettled(timeoutMs: Long): Boolean =
        ScenarioExecutor.awaitQuiet(recorder, quietMs, timeoutMs) {
            server.idle &&
                clientCompletions.get() >= server.responsesCompleted &&
                !core.hasPendingWork() &&
                client.ownWorkInFlight == 0
        }

    override fun intentionalDelayMs(): Long =
        server.latencySleptMs + world.delaySleptMs + vision.delaySleptMs.get() + bargeInDelayMs

    override fun snapshot(): Map<String, String> = probe.snapshot()

    override suspend fun apply(step: Step) {
        when (step) {
            is Step.AdvanceRoute -> world.advance(step.fraction)
            Step.Arrive -> world.arrive()
            is Step.Camera -> {
                camera.open = step.open
                camera.fixture = step.fixture
            }
            is Step.Pause -> delay(step.ms)
            is Step.Inject -> inject(step.fault)
            else -> Unit
        }
    }

    private fun inject(fault: Fault) {
        when (fault) {
            is Fault.VehicleFailNext -> vehicle.faults.failNext = InjectedClimateFailure.valueOf(fault.kind)
            is Fault.VehicleFailAlways ->
                vehicle.faults.failAlways[ClimateOperation.valueOf(fault.operation)] = InjectedClimateFailure.valueOf(fault.kind)
            Fault.VehicleClearFaults -> {
                vehicle.faults.failNext = null
                vehicle.faults.failAlways.clear()
            }
            is Fault.NavigationResolveDelay -> world.delayResolve(fault.query, fault.delayMs)
            Fault.NavigationResolveFailsOnce -> world.failNextResolve = true
            is Fault.NavigationCalcFailsOnce -> world.failNextCalcWith = fault.code
            Fault.NavigationStartFailsOnce -> world.failNextStart = true
            Fault.MusicFailsOnce -> music.failNext = true
            Fault.VisionFailsOnce -> vision.failNext = true
            is Fault.ServerLatency -> server.latencyMs = fault.ms
            is Fault.ServerDisconnectDuringNextTurn -> server.disconnectNextTurn = fault.afterToolCall
            Fault.ServerRejectsNextConnect -> {
                server.rejectNextConnect = true
                server.dropConnection()
            }
            Fault.ServerErrorEventOnNextTurn -> server.errorOnNextTurn = true
            is Fault.ToolResultDelay -> vision.delayMs = fault.ms
            Fault.DuplicateToolResult -> server.duplicateToolEvent = true
            Fault.BackgroundForeground -> error("unsupported")
        }
    }

    override suspend fun bargeIn(step: Step.BargeIn, variant: String) {
        val mark = recorder.lastSeq
        // The reply lasts just long enough to be interrupted at the scenario's moment.
        server.userSays(ScriptedRealtimeServer.Turn(step.primer, emptyList(), ModelBehavior.CORRECT, longReplyMs = step.afterTtsMs + 500))
        val deadline = System.currentTimeMillis() + 5_000
        while (recorder.eventsAfter(mark).none { it.type == EventType.TTS_START } && System.currentTimeMillis() < deadline) delay(2)
        bargeInDelayMs += step.afterTtsMs
        delay(step.afterTtsMs)
        recorder.record(EventType.INPUT_SENT, text = { step.utterance })
        server.userSays(ScriptedRealtimeServer.Turn(step.utterance, step.expect.tools, ModelBehavior.CORRECT, bargeIn = true))
    }

    override fun sessionFailed(): Boolean = ::core.isInitialized && core.machine.state == VoiceUiState.ERROR

    override suspend fun finish(scenario: Scenario) {
        runCatching { core.stop() }
        runCatching { client.close() }
        runCatching { server.shutdown() }
        runCatching { scope.cancel() }
        mainThread?.shutdownNow()
        mainThread = null
        NavigationState.reset()
        recorder.record(EventType.SESSION_END)
        recorder.removeSink(completionCounter)
    }

    val serverConnections: Int get() = server.connections.get()

    /** Playback stand-in: counts audio and reports a stop like the app's playback port. */
    private class RecordingPlayback : PlaybackPort {
        @Volatile private var frames = 0
        override fun start() = Unit
        override fun enqueue(pcm16le: ByteArray) {
            frames++
        }
        override fun flush() {
            frames = 0
            Telemetry.record(EventType.AUDIO_STOPPED)
        }
        override fun stop() = Unit
        override val queuedFrames: Int get() = frames
    }

    private fun config(endpoint: String) = BaiduApiConfig(
        BaiduAppSettings(
            authMode = BaiduAuthMode.BEARER_API_KEY,
            runtimeProvider = BaiduRuntimeProvider.FLEX,
            model = BaiduFlexProtocol.MODEL,
            endpoint = endpoint,
        ),
        BaiduCredentials("", "simulated-key", ""),
    )
}
