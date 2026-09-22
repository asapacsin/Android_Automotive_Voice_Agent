package com.novadrive.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards against a working feature silently disappearing during an unrelated change.
 *
 * The wake word stopped working once before after another update, and nothing failed. These
 * checks are deliberately structural: each asserts one link that the feature cannot work without.
 * If a change removes a link on purpose, this test is where that decision gets made explicit.
 */
class FeaturePresenceRegressionTest {
    private val root = File(System.getProperty("nova.repo.root") ?: error("nova.repo.root not set"))
    private fun text(path: String): String = File(root, path).also {
        assertTrue(it.isFile, "missing file: $path")
    }.readText()

    private fun assertContains(path: String, needle: String, why: String) {
        assertTrue(text(path).contains(needle), "$path must contain `$needle` — $why")
    }

    // ---- wake word: 你好小诺 -> session ----

    @Test
    fun wakeWordIsBoundAtAppStart() {
        // It used to be bound inside DebugVoiceLog.init, and this test pinned it there. A logging
        // initialiser is the wrong owner: a guard added to it for a logging reason would have taken
        // the wake word with it, silently.
        assertContains(
            "app/src/main/kotlin/com/novadrive/app/MainActivity.kt",
            "WakeWordController.bind(this)",
            "without this nothing ever starts the detector",
        )
    }

    @Test
    fun wakeDetectionStartsTheVoiceSession() {
        assertContains(
            "app/src/main/kotlin/com/novadrive/app/wake/WakeWordController.kt",
            // Not the full call: the detection callback must be wired, but its arguments are
            // the controller's business and pinning them made this fail on an unrelated change.
            "created.onWake = { onDetected(",
            "the detector callback must be wired",
        )
        assertContains(
            "app/src/main/kotlin/com/novadrive/app/wake/WakeWordController.kt",
            "VoiceSessionGateway.start(\"wake_word\")",
            "a detection must open (or resume) the same session as every other entry point",
        )
    }

    // ---- listening lifecycle: ACTIVE / SILENT_WAIT / SLEEP / DEEP_IDLE ----

    @Test
    fun listeningLifecycleIsTheSwitchForCloudAudio() {
        val controller = "app/src/main/kotlin/com/novadrive/app/voice/VoiceSessionController.kt"
        assertContains(controller, "active.setCaptureSuspended(!enabled)", "standby must really stop capture and upload")
        assertContains(controller, "commandRouter.onUserUtterance(text)", "「闭嘴」/「休眠」 are handled locally")
        assertContains(controller, "lifecycle.onBusyChanged(busy)", "the inactivity timer must see user turns")
        assertContains(
            "ingress/src/main/kotlin/com/novadrive/ingress/realtime/VoiceSessionController.kt",
            "if (captureSuspended.get()) return",
            "nothing may re-arm capture during sleep",
        )
        assertContains(
            "app/src/main/kotlin/com/novadrive/app/voice/VoiceSessionGateway.kt",
            "session.activate(reason)",
            "wake word and UI resume a sleeping or silent session",
        )
        assertContains(
            "app/src/main/kotlin/com/novadrive/app/ui/AssistantOverlayView.kt",
            "fun bindListening(state: ListeningState)",
            "the driver must see whether audio goes to the cloud",
        )
    }

    @Test
    fun shutUpCutsTheReplyWithoutEndingTheConversation() {
        val controller = "app/src/main/kotlin/com/novadrive/app/voice/VoiceSessionController.kt"
        assertContains(controller, "AndroidPlaybackPort(player, audioFocus) { lifecycle.speaks }", "replies are spoken only in ACTIVE")
        assertContains(controller, "if (reason == \"wake_word\" && playbackSpeaking)", "the wake word must be able to cut off a reply")
        assertContains(
            "app/src/main/kotlin/com/novadrive/app/voice/VoiceCommandRouter.kt",
            "ListeningIntent.Decision.SHUT_UP ->",
            "「闭嘴」 is its own intent, not sleep",
        )
    }

    @Test
    fun wakeWordEngineArtifactsArePackaged() {
        assertContains("app/build.gradle.kts", "files(\"libs/Msc.jar\")", "iFlytek MSC classes")
        for (path in listOf(
            "app/libs/Msc.jar",
            "app/src/main/assets/ivw/wakeword.jet",
            "app/src/main/jniLibs/arm64-v8a/libmsc.so",
            "app/src/main/jniLibs/arm64-v8a/libw_ivw.so",
        )) {
            assertTrue(File(root, path).isFile, "wake-word artifact missing: $path")
        }
        assertContains(
            "app/src/main/kotlin/com/novadrive/app/wake/IflytekWakeWordDetector.kt",
            "\"ivw/wakeword.jet\"",
            "the detector loads this exact asset",
        )
        assertContains(
            "app/src/main/kotlin/com/novadrive/app/wake/IflytekWakeWordDetector.kt",
            "IVW_NET_MODE_OFFLINE = \"0\"",
            "wake detection must stay offline (modes 1-2 upload audio)",
        )
    }

    // ---- spoken navigation guidance, kept out of the assistant's ears ----

    @Test
    fun navigationGuidanceIsSpokenAndGatesTheMicrophone() {
        val host = "app/src/main/kotlin/com/novadrive/app/nav/amap/AmapNaviViewHost.kt"
        assertContains(host, "setUseInnerVoice(true", "the SDK is silent unless its own voice is enabled")
        assertContains(host, "enableGuidanceVoice()", "must be called when the engine is created")
        assertContains(host, "addTTSPlayListener(GuidancePlayListener)", "the speaking signal drives the mic gate (P3)")
        assertContains(
            "app/src/main/kotlin/com/novadrive/app/voice/VoiceSessionController.kt",
            "NavigationGuidanceVoice.addListener(guidanceListener)",
            "guidance must not be sent to Baidu as the driver's speech",
        )
        assertContains(
            "app/src/main/kotlin/com/novadrive/app/voice/PcmAudioCapture.kt",
            "droppedGuidance.incrementAndGet()",
            "gated frames must actually be dropped",
        )
        assertContains(
            "app/src/main/kotlin/com/novadrive/app/voice/PcmAudioCapture.kt",
            "uplinkGate.onCaptureInterrupted()",
            "residual guidance or reply audio must not build a speech onset",
        )
    }

    // ---- the map finds the driver at startup ----

    @Test
    fun theMapRecentresOnTheCurrentPositionAtStartup() {
        val host = "app/src/main/kotlin/com/novadrive/app/nav/amap/AmapNaviViewHost.kt"
        // The whole defect was that nothing ever moved the camera: MyLocationStyle alone does
        // not recentre AMapNaviView without a route.
        assertContains(host, "map.moveCamera(CameraUpdateFactory.newLatLngZoom", "nothing else moves the camera")
        assertContains(host, "ensureTraceListener()", "no route means no listener means no fixes")
        assertContains(
            "app/src/main/kotlin/com/novadrive/app/nav/amap/NavigationTraceListener.kt",
            "location?.let(onLocation)",
            "the SDK's position stream is the source of the first fix",
        )
        assertContains(host, "addOnMapTouchListener", "a manual pan must end automatic recentring")
        assertContains(
            host,
            "CoordinateConverter.CoordType.GPS",
            "platform fixes are WGS-84 and the map is GCJ-02",
        )
    }

    @Test
    fun drivingNavigationUsesNativeAmapPresentationNotIdleCamera() {
        val presentation = "app/src/main/kotlin/com/novadrive/app/nav/amap/AmapDrivingPresentation.kt"
        assertContains(presentation, "setAutoLockCar(driving)", "lock-car is an Amap option, not a homemade tilt")
        assertContains(presentation, "AMapNaviView.CAR_UP_MODE", "heading-up is navi mode, not MapView bearing")
        assertContains(presentation, "setTrafficLine(true)", "traffic colour comes from the navi route")
        assertContains(presentation, "recoverLockMode()", "overview must be able to return to tracking")
        assertContains(presentation, "setNaviArrowVisible(driving)", "3D turn arrows are a navi overlay")
        assertContains(presentation, "setLaneInfoShow(driving)", "lane guidance is native HUD")
        assertContains(presentation, "setTurnArrowIs3D(driving)", "turn arrows must be 3D when the SDK draws them")
        assertContains(presentation, "setLayoutVisible(driving)", "default Amap navi chrome is shown while driving")
        assertContains(presentation, "SHOW_MODE_LOCK_CAR", "the camera mode is lock-car")
        assertContains(presentation, "setTrafficStatusUpdateEnabled(true)", "traffic must update during the drive")
        assertContains(presentation, "setWidgetOverSpeedPulseEffective(driving)", "overspeed pulse is native HUD, not a homemade overlay")
        assertContains(presentation, "setTrafficLightsVisible(true)", "light icons render when Amap supplies them")
        assertContains(presentation, "setShowTrafficLightView(true)", "countdown bubble is the navi view, not our layout")
        assertContains(presentation, "TRAFFIC_COUNTDOWN_STATUS", "countdown state is declared, not silently assumed")
        assertContains(presentation, "setPointToCenter(LOCK_CENTER_X, LOCK_CENTER_Y)", "vehicle sits in the lower-middle")
        val host = "app/src/main/kotlin/com/novadrive/app/nav/amap/AmapNaviViewHost.kt"
        assertContains(host, "AmapDrivingPresentation.applyDriving", "startNavi must enter driving presentation")
        assertContains(host, "EmulatorNaviSpeed.clamp", "emulator speed is urban default / debug override, not a hardcoded 120")
        assertContains(host, "DrivingSpeedHud.snapshot", "current speed and posted limit must be shown from Amap data")
        assertContains(host, "attachSpeedHud", "speed chip must sit above the assistant overlay, not under it")
        val screen = "app/src/main/kotlin/com/novadrive/app/ui/AssistantNavigationScreen.kt"
        assertContains(screen, "mapHost.attachSpeedHud(this)", "the speed chip is attached on the screen, above overlay")
        assertContains(host, "AmapDrivingPresentation.applyRoutePreview", "route pick is overview, not driving camera")
        assertContains(host, "AmapDrivingPresentation.applyIdle", "ending navi restores browse")
        assertContains(host, "lock_car", "📍 during a drive recovers lock-car, it does not 2D-recenter")
        assertContains(host, "disableBrowseLocationLayer()", "the idle blue-dot layer must not fight the navi car")
        val controller = "app/src/main/kotlin/com/novadrive/app/nav/EmbeddedNavigationController.kt"
        assertContains(controller, "fun showOverview()", "voice/UI overview must not touch AMapNaviView")
        assertContains(controller, "fun resumeTracking()", "voice/UI lock-car return goes through the engine")
        val resolver = "app/src/main/kotlin/com/novadrive/app/voice/UtteranceIntentResolver.kt"
        assertTrue(!text(resolver).contains("AMapNaviView")) {
            "NLU must not name AMapNaviView"
        }
    }

    // ---- traffic-light countdown baseline: blocked externally, never faked ----

    @Test
    fun missingCountdownSecondsAreNotAFailure() {
        val presentation = "app/src/main/kotlin/com/novadrive/app/nav/amap/AmapDrivingPresentation.kt"
        assertContains(
            presentation,
            "BLOCKED_EXTERNAL_AMAP_ENTITLEMENT",
            "countdown state is declared until Amap grants the trial",
        )
        assertTrue(!text(presentation).contains("navi.setIsOpenTrafficLight")) {
            "quarantined 2026-09-21: setIsOpenTrafficLight(\"1\") never enabled anything"
        }
        assertTrue(!text(presentation).contains("navi.setTrafficSignalEnable")) {
            "dead public stub in 11.2.100: nothing may rely on it"
        }
        assertContains(
            presentation,
            "setShowTrafficLightView(true)",
            "the native bubble stays on so seconds appear unaided once entitled",
        )
    }

    // ---- open mic: noise must not become a turn, and never an action ----

    @Test
    fun strayNoiseIsGatedBeforeTheModelAndSilencedAfterIt() {
        val capture = "app/src/main/kotlin/com/novadrive/app/voice/PcmAudioCapture.kt"
        assertContains(capture, "gateAndSend(bytes, onFrame)", "live frames must pass the uplink gate")
        assertContains(capture, "gateForInjection", "harness audio goes through the same gate or it proves nothing")
        val client = "app/src/main/kotlin/com/novadrive/app/voice/BaiduFlexClient.kt"
        assertContains(client, "onResponseCreated()", "a suspicious turn's reply audio must be held")
        assertContains(client, "finishResponse(hadToolCall =", "the verdict needs the tool-call fact")
        assertContains(client, "TURN_DROP", "every suppression must say why")
        assertContains(client, "onExecutionResult(output)", "execution evidence must reach the turn")
        // The safety invariant: only what the driver hears and reads may be held. A tool call, an
        // error or the driver's own transcript must always pass straight through.
        assertContains(client, "event is DomainVoiceEvent.AudioDelta ||", "reply audio may be held")
        assertContains(client, "event is DomainVoiceEvent.AssistantTranscript", "and its subtitle, so the two cannot diverge")
        assertContains(client, "if (!holdable) return false", "everything else passes through untouched")
        // T07 / T09 / D-7 now share one owner: the per-turn state machine.
        val turn = "app/src/main/kotlin/com/novadrive/app/voice/DriverTurn.kt"
        assertContains(turn, "NO_TOOL_REQUEST", "a request with no tool must hold until it is known to be honest")
        assertContains(turn, "false_claim_unsupported", "a claimed action with no tool is dropped, not corrected afterwards")
        assertContains(turn, "fabricated_realtime_info", "an invented forecast is never spoken")
        assertContains(turn, "unproven_action_claim", "a claim without execution proof is never spoken")
    }

    @Test
    fun capabilityHelpGrammarAndHoldStayWired() {
        val resolver = "app/src/main/kotlin/com/novadrive/app/voice/UtteranceIntentResolver.kt"
        assertContains(resolver, "matchesCapabilityHelpGrammar", "help is grammar, not a synonym list")
        assertContains(resolver, "CapabilityIds.SPEECH_CAPABILITY_HELP", "help maps to speech.capability_help")
        val turn = "app/src/main/kotlin/com/novadrive/app/voice/DriverTurn.kt"
        assertContains(turn, "Kind.CAPABILITY_HELP", "help is its own turn kind")
        assertContains(turn, "HoldReason.CAPABILITY_HELP", "help holds until catalog copy is spoken")
        val catalog = "contracts/src/main/kotlin/com/novadrive/contracts/Capability.kt"
        assertContains(catalog, "spokenHelpSummary", "help copy comes from the catalog")
    }

    @Test
    fun fullDuplexBargeInDoesNotMuteMicDuringModelPlayback() {
        val controller = text("app/src/main/kotlin/com/novadrive/app/voice/VoiceSessionController.kt")
        assertTrue(!controller.contains("PLAYBACK_UNGATE_DELAY_MS")) {
            "model playback must not delay mic reopen — full-duplex barge-in uses server VAD + AEC"
        }
        assertTrue(!controller.contains("microphone.gated = true")) {
            "model playback must not drop uplink frames"
        }
        assertContains(
            "app/src/main/kotlin/com/novadrive/app/voice/BaiduFlexProtocol.kt",
            "interrupt_response",
            "Flex session must allow server-side interruption",
        )
        assertContains(
            "ingress/src/main/kotlin/com/novadrive/ingress/realtime/VoiceSessionController.kt",
            "private suspend fun bargeIn()",
            "speech during reply must flush playback",
        )
    }

    // ---- permissions every main feature depends on ----

    @Test
    fun requiredPermissionsAreDeclaredAndRequested() {
        val manifest = "app/src/main/AndroidManifest.xml"
        for (permission in listOf("RECORD_AUDIO", "CAMERA", "ACCESS_FINE_LOCATION", "INTERNET")) {
            assertContains(manifest, "android.permission.$permission", "feature depends on it")
        }
        val activity = "app/src/main/kotlin/com/novadrive/app/MainActivity.kt"
        assertContains(activity, "Manifest.permission.CAMERA", "camera permission must be requested")
        assertContains(activity, "ACTION_APPLICATION_DETAILS_SETTINGS", "settings fallback after a permanent refusal")
        assertContains(activity, "Manifest.permission.ACCESS_FINE_LOCATION", "embedded navigation needs GNSS")
    }

    // ---- the debug harness must never ship in a release build ----

    @Test
    fun debugToolsStayInTheDebugSourceSet() {
        assertTrue(
            !File(root, "app/src/main/kotlin/com/novadrive/app/DebugToolReceiver.kt").exists(),
            "DebugToolReceiver must not move into the main source set",
        )
        assertTrue(
            !text("app/src/main/AndroidManifest.xml").contains("DebugToolReceiver"),
            "the ADB receiver must only be declared in the debug manifest",
        )
        assertContains(
            "app/src/main/kotlin/com/novadrive/app/voice/VoiceSessionController.kt",
            "if (!com.novadrive.app.DebugVoiceLog.isEnabled) return",
            "synthetic speech injection must be inert on non-debuggable builds",
        )
    }

    // ---- UI features that were removed-by-accident candidates ----

    @Test
    fun navigationPickerAndCameraAreStillOnTheMainScreen() {
        val screen = "app/src/main/kotlin/com/novadrive/app/ui/AssistantNavigationScreen.kt"
        assertContains(screen, "NavigationChoiceOverlay(context)", "destination / route picker")
        assertContains(screen, "choiceOverlay.bind(", "picker must be bound to the controller")
        assertContains(screen, "CameraPreviewView(context)", "camera window")
        assertContains(screen, "CameraVisionGateway.attach(", "camera must be reachable by the vision tool")
        assertContains(screen, "NavigationHostGateway.attach(", "embedded map host must be reachable")
        // Was bindClimate(VehicleControlProvider.port) until 2026-09-19. The bar now reaches the
        // executors the same way a spoken command does (I-6, D-3 resolved), so the link to assert
        // is the one that supplies it, and the Activity is what supplies it.
        assertContains(screen, "bottomBar.bind(controls)", "climate and media controls must be bound")
        assertContains(
            "app/src/main/kotlin/com/novadrive/app/MainActivity.kt",
            "screen.bindControls(screenControls)",
            "without this the bottom bar is inert",
        )
        assertContains(screen, "camera.onHostPause()", "the camera must be released when the screen is backgrounded")
        assertContains(screen, "camera.onHostResume()", "and reopened when the screen returns")
    }
}
