package com.novadrive.app

import com.novadrive.app.nav.DestinationCandidate
import com.novadrive.app.nav.EmbeddedNavigationController
import com.novadrive.app.nav.NavigationChoice
import com.novadrive.app.nav.NavigationLocalPickGuard
import com.novadrive.app.nav.NavigationPhase
import com.novadrive.app.vehicle.ClimateToolHandler
import kotlinx.coroutines.runBlocking
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.simulator.SimulatedVehicleControl
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AndroidToolDispatcherTest {
    @Test
    fun navigateValidatesThenMapsSuccessfulAndFailedResults() {
        val executor = FakeExecutor()
        val dispatcher = AndroidToolDispatcher(executor, ClimateToolHandler(SimulatedVehicleControl()), noCamera())
        val success = dispatcher.dispatch(call("navigate_to", mapOf("destination" to "Macau Tower")))
        assertEquals("Macau Tower", executor.destination)
        // The list is read out once it has loaded, so the accepted result is delivered asynchronously.
        val options = runBlocking { success.deferredOutput!!() }
        assertTrue(JSONObject(options).getBoolean("ok"))
        assertEquals("destination_list", JSONObject(options).getString("screen"))
        assertTrue(JSONObject(options).getString("options_on_screen").startsWith("1. 珠海站"))

        executor.next = AndroidActionResult.Rejected("NAVIGATION_APP_UNAVAILABLE")
        val failure = dispatcher.dispatch(call("navigate_to", mapOf("destination" to "Zhuhai")))
        assertFalse(JSONObject(failure.output!!).getBoolean("ok"))
        assertEquals("NAVIGATION_APP_UNAVAILABLE", JSONObject(failure.output!!).getString("error"))
    }

    @Test
    fun malformedUnknownAndBlankCallsExecuteNothing() {
        val executor = FakeExecutor()
        val dispatcher = AndroidToolDispatcher(executor, ClimateToolHandler(SimulatedVehicleControl()), noCamera())
        dispatcher.dispatch(call("navigate_to", mapOf("_validation_error" to "MALFORMED_JSON")))
        dispatcher.dispatch(call("unknown", emptyMap()))
        dispatcher.dispatch(call("navigate_to", mapOf("destination" to " ")))
        assertEquals(0, executor.executions)
    }

    @Test
    fun openAppAcceptsOnlyTheFixedAllowlist() {
        val executor = FakeExecutor()
        val dispatcher = AndroidToolDispatcher(executor, ClimateToolHandler(SimulatedVehicleControl()), noCamera())
        listOf("maps", "settings").forEach { dispatcher.dispatch(call("open_app", mapOf("app" to it))) }
        assertEquals(listOf(AllowedApp.MAPS, AllowedApp.SETTINGS), executor.apps)
        val rejected = dispatcher.dispatch(call("open_app", mapOf("app" to "com.example.raw")))
        assertEquals("APP_NOT_ALLOWED", JSONObject(rejected.output!!).getString("error"))
        assertEquals(2, executor.executions)
    }

    @Test
    fun controlMusicPlayAndStopAreAccepted() {
        val executor = FakeExecutor()
        val dispatcher = AndroidToolDispatcher(executor, ClimateToolHandler(SimulatedVehicleControl()), noCamera())
        val play = dispatcher.dispatch(call("control_music", mapOf("action" to "play")))
        assertTrue(JSONObject(play.output!!).getBoolean("ok"))
        assertTrue(play.output!!.contains("music_playing"))
        val stop = dispatcher.dispatch(call("control_music", mapOf("action" to "stop")))
        assertTrue(JSONObject(stop.output!!).getBoolean("ok"))
        assertTrue(stop.output!!.contains("music_stopped"))
        assertEquals(2, executor.executions)
    }

    @Test
    fun exitNavigationModeReturnsAcceptedWithNavigationModeExited() {
        val executor = FakeExecutor()
        val dispatcher = AndroidToolDispatcher(executor, ClimateToolHandler(SimulatedVehicleControl()), noCamera())
        val result = dispatcher.dispatch(call("exit_navigation_mode", emptyMap()))
        assertTrue(JSONObject(result.output!!).getBoolean("ok"))
        assertTrue(result.output!!.contains("navigation_mode_exited"))
        assertEquals(1, executor.executions)
    }

    @Test
    fun openAppMusicAndUnknownControlActionAreBlocked() {
        val executor = FakeExecutor()
        val dispatcher = AndroidToolDispatcher(executor, ClimateToolHandler(SimulatedVehicleControl()), noCamera())
        val music = dispatcher.dispatch(call("open_app", mapOf("app" to "music")))
        assertEquals("APP_NOT_ALLOWED", JSONObject(music.output!!).getString("error"))
        val unknown = dispatcher.dispatch(call("control_music", mapOf("action" to "pause")))
        assertEquals("ACTION_NOT_ALLOWED", JSONObject(unknown.output!!).getString("error"))
        assertEquals(0, executor.executions)
    }

    @Test
    fun navigateToRedirectsWhenSimplifiedHuiMatchesTraditionalRow() {
        val executor = FakeExecutor()
        executor.pickerMatch = NavigationChoice.Name("中交汇通")
        val dispatcher = AndroidToolDispatcher(executor, ClimateToolHandler(SimulatedVehicleControl()), noCamera())
        val result = dispatcher.dispatch(call("navigate_to", mapOf("destination" to "中交汇通")))
        assertEquals(listOf(NavigationChoice.Name("中交汇通")), executor.choices)
        assertEquals(null, executor.destination)
        assertTrue(runBlocking { result.deferredOutput!!() }.contains("destination_list"))
    }

    @Test
    fun navigateToIsSuppressedAfterLocalPickSucceeded() {
        NavigationLocalPickGuard.onLocalPickSucceeded("list-a", NavigationLocalPickGuard.nextTurnKey())
        val executor = FakeExecutor()
        val dispatcher = AndroidToolDispatcher(executor, ClimateToolHandler(SimulatedVehicleControl()), noCamera())
        val result = dispatcher.dispatch(call("navigate_to", mapOf("destination" to "中交汇通")))
        assertEquals(0, executor.executions)
        assertEquals("destination_already_selected_locally", JSONObject(result.output!!).getString("status"))
        NavigationLocalPickGuard.onUserTranscript()
    }

    @Test
    fun navigateToRedirectsToPickerWhenNameMatchesOneCandidate() {
        val executor = FakeExecutor()
        executor.pickerMatch = NavigationChoice.Name("拱北口岸")
        val dispatcher = AndroidToolDispatcher(executor, ClimateToolHandler(SimulatedVehicleControl()), noCamera())
        val result = dispatcher.dispatch(call("navigate_to", mapOf("destination" to "拱北口岸")))
        assertEquals(listOf(NavigationChoice.Name("拱北口岸")), executor.choices)
        assertEquals(null, executor.destination, "must not start a new search")
        val options = runBlocking { result.deferredOutput!!() }
        assertEquals("destination_list", JSONObject(options).getString("screen"))
    }

    @Test
    fun chooseNavigationOptionParsesEachFormAndReportsRejections() {
        val executor = FakeExecutor()
        val dispatcher = AndroidToolDispatcher(executor, ClimateToolHandler(SimulatedVehicleControl()), noCamera())
        dispatcher.dispatch(call("choose_navigation_option", mapOf("index" to "2")))
        dispatcher.dispatch(call("choose_navigation_option", mapOf("index" to "3.0")))
        dispatcher.dispatch(call("choose_navigation_option", mapOf("preference" to "fastest")))
        dispatcher.dispatch(call("choose_navigation_option", mapOf("name" to "拱北口岸")))
        assertEquals(
            listOf(
                NavigationChoice.Index(2),
                NavigationChoice.Index(3),
                NavigationChoice.Preference(NavigationChoice.Kind.FASTEST),
                NavigationChoice.Name("拱北口岸"),
            ),
            executor.choices,
        )
        val bad = dispatcher.dispatch(call("choose_navigation_option", mapOf("preference" to "scenic")))
        assertEquals("INVALID_CHOICE", JSONObject(bad.output!!).getString("error"))
        executor.next = AndroidActionResult.Rejected("OUT_OF_RANGE")
        val out = dispatcher.dispatch(call("choose_navigation_option", mapOf("index" to "9")))
        assertEquals("OUT_OF_RANGE", JSONObject(out.output!!).getString("error"))
        assertEquals(null, out.deferredOutput, "a refused pick must not wait for a list")
    }

    @Test
    fun setSpeechOutputSwitchesSilentModeAndRejectsOtherModes() {
        val executor = FakeExecutor()
        val dispatcher = AndroidToolDispatcher(executor, ClimateToolHandler(SimulatedVehicleControl()), noCamera())
        val silent = dispatcher.dispatch(call("set_speech_output", mapOf("mode" to "silent")))
        assertTrue(JSONObject(silent.output!!).getBoolean("ok"))
        assertEquals(listOf(true), executor.silentRequests)
        dispatcher.dispatch(call("set_speech_output", mapOf("mode" to "spoken")))
        assertEquals(listOf(true, false), executor.silentRequests)
        val bad = dispatcher.dispatch(call("set_speech_output", mapOf("mode" to "loud")))
        assertEquals("MODE_NOT_ALLOWED", JSONObject(bad.output!!).getString("error"))
    }

    @Test
    fun theCoreExecutorForwardsSilentMode() {
        val seen = mutableListOf<Boolean>()
        val engine = com.novadrive.app.nav.FakeNaviEngine()
        val core = CoreActionExecutor(
            navigationFlow = EmbeddedNavigationController(resolver = engine, engine = engine),
            music = { error("unused") },
            speechSilent = { seen += it },
        )
        val silent = core.setSpeechSilent(true)
        assertTrue(silent is AndroidActionResult.Accepted)
        assertEquals("stopped_talking_still_listening", (silent as AndroidActionResult.Accepted).status)
        assertEquals(listOf(true), seen)
    }

    private fun call(name: String, args: Map<String, String>) = DomainVoiceEvent.ToolCall("call_1", name, args)

    private class FakeExecutor : AndroidActionExecutor {
        var executions = 0
        var destination: String? = null
        val apps = mutableListOf<AllowedApp>()
        var next: AndroidActionResult = AndroidActionResult.Accepted()
        override fun navigate(destination: String): AndroidActionResult { executions++; this.destination = destination; return next }
        override fun openApp(app: AllowedApp): AndroidActionResult { executions++; apps += app; return next }
        override fun playMusic(): AndroidActionResult { executions++; return AndroidActionResult.Accepted("music_playing") }
        override fun stopMusic(): AndroidActionResult { executions++; return AndroidActionResult.Accepted("music_stopped") }
        override fun exitNavigationMode(): AndroidActionResult { executions++; return AndroidActionResult.Accepted("navigation_mode_exited") }
        val silentRequests = mutableListOf<Boolean>()
        override fun setSpeechSilent(silent: Boolean): AndroidActionResult { silentRequests += silent; return AndroidActionResult.Accepted() }
        val choices = mutableListOf<NavigationChoice>()
        var pickerMatch: NavigationChoice? = null
        override fun matchPickerName(utterance: String): NavigationChoice? = pickerMatch
        override fun chooseNavigationOption(choice: NavigationChoice): AndroidActionResult { choices += choice; return next }
        override suspend fun awaitNavigationOptions() = EmbeddedNavigationController.OptionsSnapshot(
            NavigationPhase.AWAITING_DESTINATION_SELECTION,
            listOf(DestinationCandidate("a", "珠海站", "", "", 22.2, 113.5, distanceMeters = 12_900)),
            emptyList(),
            null,
        )
    }
}
