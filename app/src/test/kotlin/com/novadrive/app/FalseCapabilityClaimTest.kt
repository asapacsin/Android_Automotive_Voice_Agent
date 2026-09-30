package com.novadrive.app

import com.novadrive.app.vehicle.ClimateToolHandler
import com.novadrive.app.voice.ActionClaimGuard
import com.novadrive.app.voice.ClimateToolActions
import com.novadrive.app.voice.DriverContext
import com.novadrive.app.voice.DriverTurn
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.ResponseOutcome
import com.novadrive.simulator.SimulatedVehicleControl
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Two ways the product could tell the driver that something happened when it did not, both of
 * which pass every other guard because a tool really did return `ok=true`.
 *
 * `control_music` is one bundled track with play/stop. A request that names or describes a song is
 * served by `play_music` (SPEC-017); answering it by starting the bundled track makes `ok=true`
 * mean "you got what you asked for" ([I-2](../../../../../../docs/INVARIANTS.md)).
 *
 * And a relative adjustment is not idempotent: the same `adjust_temperature{-2}` dispatched twice
 * is −4 °C, a physical change the driver never asked for.
 */
class FalseCapabilityClaimTest {

    private fun call(name: String, arguments: Map<String, String>) =
        DomainVoiceEvent.ToolCall("call_1", name, arguments)

    /** Accepts everything it is asked to do, so a refusal in a test can only come from the guard. */
    private class WillingExecutor : AndroidActionExecutor {
        override fun navigate(destination: String) = AndroidActionResult.Accepted("navigating")

        override fun openApp(app: AllowedApp) = AndroidActionResult.Accepted("opened")

        override fun playMusic() = AndroidActionResult.Accepted("playing")

        override fun stopMusic() = AndroidActionResult.Accepted("stopped")

        override fun exitNavigationMode() = AndroidActionResult.Accepted("exited")
    }

    private fun dispatcher(context: DriverContext) = AndroidToolDispatcher(
        WillingExecutor(),
        ClimateToolHandler(SimulatedVehicleControl()),
        noCamera(),
    ) { context }

    // ---- recognising a named / described song (SPEC-017: play_music) -------

    @Test
    fun aRequestNamingASongIsRecognisedAsPlayByDescription() {
        listOf(
            "放一下周杰伦那首我忘了名字的歌，就是讲晴天的那个。",
            "放周杰伦的歌。",
            "我想听点轻音乐的歌曲。",
        ).forEach {
            assertTrue(ActionClaimGuard.isSpecificMediaRequest(it), "should be specific: $it")
            assertFalse(ActionClaimGuard.isUnsupportedRequest(it), "play_music serves it: $it")
            assertTrue(ActionClaimGuard.isControlRequest(it), it)
            assertEquals(DriverTurn.Kind.ACTION, DriverTurn.classify(it), it)
        }
    }

    // ---- a playing claim with no tool behind it ----------------------------

    @Test
    fun aPlayingClaimWithNoToolCallIsNotReleased() {
        assertTrue(ActionClaimGuard.carActionClaim("正在放梶浦由记的《xxx》") != null)
        val guard = ActionClaimGuard()
        guard.onUserTranscript("放梶浦由记的歌")
        val followUp = guard.onResponseDone(ResponseOutcome(spoke = true), "正在放梶浦由记的《xxx》")
        assertTrue(followUp != null, "a claim of what is playing needs play_music's now_playing")
    }

    @Test
    fun anHonestMusicFailureIsReleasedAsARefusal() {
        listOf("《X》播放不了", "这首没放成").forEach { reply ->
            assertEquals(null, ActionClaimGuard.carActionClaim(reply), reply)
            val guard = ActionClaimGuard()
            guard.onUserTranscript("放梶浦由记的歌")
            guard.onResponseDone(ResponseOutcome(spoke = false, toolCallIds = listOf("c1")), "")
            assertEquals(null, guard.onResponseDone(ResponseOutcome(spoke = true), reply), reply)
        }
        listOf("空调开好了，风量调不了", "没能找到别的，已经为你打开空调", "这首放不了，已经在放另一首歌了").forEach { reply ->
            assertTrue(ActionClaimGuard.carActionClaim(reply) != null, reply)
            val mixed = ActionClaimGuard()
            mixed.onUserTranscript("打开空调")
            assertTrue(mixed.onResponseDone(ResponseOutcome(spoke = true), reply) != null, reply)
        }
        assertTrue(ActionClaimGuard.carActionClaim("正在放《X》") != null)
        val guard = ActionClaimGuard()
        guard.onUserTranscript("放梶浦由记的歌")
        assertTrue(guard.onResponseDone(ResponseOutcome(spoke = true), "正在放《X》") != null)
    }

    @Test
    fun mediaWordsInOrdinaryChatAreReleased() {
        listOf("现在放松一下，听听音乐吧", "《哪吒2》还在放映").forEach {
            assertEquals(null, ActionClaimGuard.carActionClaim(it), it)
            val guard = ActionClaimGuard()
            guard.onUserTranscript("随便聊聊")
            assertEquals(null, guard.onResponseDone(ResponseOutcome(spoke = true), it), it)
        }
    }

    @Test
    fun aPlayingPromiseOrMixedRefusalWithNoCallIsNotReleased() {
        listOf("好的，这就给你放周杰伦的《晴天》", "帮你放一首歌", "这首放不了，已经为你播放了另一首歌").forEach {
            assertTrue(ActionClaimGuard.carActionClaim(it) != null, it)
            val guard = ActionClaimGuard()
            guard.onUserTranscript("放周杰伦的晴天")
            assertTrue(guard.onResponseDone(ResponseOutcome(spoke = true), it) != null, it)
        }
    }

    // R2 (an unconfirmed play_music result) is owned by DriverTurn: DriverTurnMusicTest.

    @Test
    fun chatAboutASongWithoutAPlayingWordIsReleased() {
        assertEquals(null, ActionClaimGuard.carActionClaim("这首歌的歌词挺好"))
        val guard = ActionClaimGuard()
        guard.onUserTranscript("你觉得这首歌怎么样")
        assertEquals(null, guard.onResponseDone(ResponseOutcome(spoke = true), "这首歌的歌词挺好"))
    }

    @Test
    fun aGenericMusicRequestIsStillSupported() {
        listOf("播放音乐。", "放首歌。", "来点音乐。", "我想听点音乐。").forEach {
            assertFalse(ActionClaimGuard.isSpecificMediaRequest(it), "should stay supported: $it")
            assertFalse(ActionClaimGuard.isUnsupportedRequest(it), "should stay supported: $it")
        }
    }

    @Test
    fun stoppingMusicAndUnrelatedSentencesAreNotMediaRequests() {
        // 「放大地图」 contains 放 but no music noun; 「关闭音乐」 is not a play request at all.
        listOf("关闭音乐。", "放大地图。", "导航去珠海站。").forEach {
            assertFalse(ActionClaimGuard.isSpecificMediaRequest(it), it)
        }
    }

    @Test
    fun skippingTracksIsRecognisedAsUnsupported() {
        // media.next_track is `unsupported` in the registry and had no recogniser at all.
        listOf("下一首。", "换一首。", "切歌。").forEach {
            assertTrue(ActionClaimGuard.isUnsupportedRequest(it), it)
        }
    }

    // ---- and refusing to execute it ----------------------------------------

    @Test
    fun aNamedSongNeverStartsTheBundledTrack() {
        val context = DriverContext()
        context.onDriverUtterance("放一下周杰伦那首讲晴天的歌。", epoch = 1)
        val result = dispatcher(context).dispatch(call("control_music", mapOf("action" to "play")))
        val output = JSONObject(result.output!!)

        assertFalse(output.getBoolean("ok"), "a song this product cannot play must not report success")
        assertEquals("MEDIA_LIBRARY_UNSUPPORTED", output.getString("error"))
        assertTrue(output.getString("next").contains("play_music"), "the model is sent to play_music")
    }

    @Test
    fun aNamedArtistStillNeverStartsTheBundledTrack() {
        val context = DriverContext()
        context.onDriverUtterance("放梶浦由记的歌", epoch = 1)
        val output = JSONObject(dispatcher(context).dispatch(call("control_music", mapOf("action" to "play"))).output!!)
        assertFalse(output.getBoolean("ok"))
        assertEquals("MEDIA_LIBRARY_UNSUPPORTED", output.getString("error"))
    }

    @Test
    fun aGenericPlayRequestStillPlays() {
        val context = DriverContext()
        context.onDriverUtterance("播放音乐。", epoch = 1)
        val result = dispatcher(context).dispatch(call("control_music", mapOf("action" to "play")))
        assertTrue(JSONObject(result.output!!).getBoolean("ok"))
    }

    // ---- and not doing the same thing twice ---------------------------------

    @Test
    fun theSameRelativeAdjustmentIsNotAppliedTwiceInOneTurn() {
        val vehicle = SimulatedVehicleControl()
        val context = DriverContext()
        context.onDriverUtterance("空调调凉一点。", epoch = 1)
        val dispatcher = AndroidToolDispatcher(WillingExecutor(), ClimateToolHandler(vehicle), noCamera()) { context }
        val arguments = mapOf("action" to ClimateToolActions.ADJUST_TEMPERATURE, "value" to "-2")

        val first = dispatcher.dispatch(call("control_climate", arguments))
        val afterFirst = vehicle.climateState.value.targetTemperatureCelsius
        assertTrue(JSONObject(first.output!!).getBoolean("ok"))

        val repeat = dispatcher.dispatch(call("control_climate", arguments))
        assertFalse(JSONObject(repeat.output!!).getBoolean("ok"))
        assertEquals("DUPLICATE_IN_TURN", JSONObject(repeat.output!!).getString("error"))
        assertEquals(
            afterFirst,
            vehicle.climateState.value.targetTemperatureCelsius,
            "the repeat must not move the temperature again",
        )
    }

    @Test
    fun askingAgainInANewTurnIsNotADuplicate() {
        val vehicle = SimulatedVehicleControl()
        val context = DriverContext()
        val dispatcher = AndroidToolDispatcher(WillingExecutor(), ClimateToolHandler(vehicle), noCamera()) { context }
        val arguments = mapOf("action" to ClimateToolActions.ADJUST_TEMPERATURE, "value" to "-1")

        context.onDriverUtterance("再凉一点。", epoch = 1)
        dispatcher.dispatch(call("control_climate", arguments))
        val afterFirst = vehicle.climateState.value.targetTemperatureCelsius

        context.onDriverUtterance("再凉一点。", epoch = 2)
        dispatcher.dispatch(call("control_climate", arguments))

        assertEquals(
            afterFirst - 1.0,
            vehicle.climateState.value.targetTemperatureCelsius,
            "a second utterance is a second intent and must take effect",
        )
    }

    // ---- and not guessing when the driver did not say what to change ---------

    @Test
    fun anAmbiguousRelativeAdjustmentIsRefusedRatherThanGuessed() {
        val vehicle = SimulatedVehicleControl()
        val context = DriverContext()
        val before = vehicle.climateState.value.targetTemperatureCelsius
        // Both dimensions adjusted, so 「再低一点」 could mean either.
        context.onClimateResult(ClimateToolActions.ADJUST_TEMPERATURE, -1.0, okClimate(), epoch = 1)
        context.onClimateResult(ClimateToolActions.ADJUST_FAN, 1.0, okClimate(), epoch = 1)
        context.onDriverUtterance("再低一点。", epoch = 2)

        val dispatcher = AndroidToolDispatcher(WillingExecutor(), ClimateToolHandler(vehicle), noCamera()) { context }
        val result = dispatcher.dispatch(
            call("control_climate", mapOf("action" to ClimateToolActions.ADJUST_TEMPERATURE, "value" to "-1")),
        )
        val output = JSONObject(result.output!!)

        assertFalse(output.getBoolean("ok"), "guessing right is still guessing")
        assertEquals("AMBIGUOUS_REFERENT", output.getString("error"))
        assertEquals(
            before,
            vehicle.climateState.value.targetTemperatureCelsius,
            "nothing may change while the question is unanswered",
        )
        // And the question is recorded, so the driver's one-word answer resolves next turn.
        assertEquals(2, context.pendingClarification(3)?.options?.size)
    }

    @Test
    fun anUnambiguousRelativeAdjustmentStillRuns() {
        val vehicle = SimulatedVehicleControl()
        val context = DriverContext()
        context.onClimateResult(ClimateToolActions.ADJUST_TEMPERATURE, -1.0, okClimate(), epoch = 1)
        context.onDriverUtterance("再低一点。", epoch = 2)

        val dispatcher = AndroidToolDispatcher(WillingExecutor(), ClimateToolHandler(vehicle), noCamera()) { context }
        val result = dispatcher.dispatch(
            call("control_climate", mapOf("action" to ClimateToolActions.ADJUST_TEMPERATURE, "value" to "-1")),
        )
        assertTrue(JSONObject(result.output!!).getBoolean("ok"), "one referent is not ambiguous")
    }

    @Test
    fun anExplicitCommandIsNeverBlockedByTheAmbiguityGuard() {
        val vehicle = SimulatedVehicleControl()
        val context = DriverContext()
        context.onDriverUtterance("空调调到二十二度。", epoch = 1)
        val dispatcher = AndroidToolDispatcher(WillingExecutor(), ClimateToolHandler(vehicle), noCamera()) { context }
        val result = dispatcher.dispatch(
            call("control_climate", mapOf("action" to ClimateToolActions.SET_TEMPERATURE, "value" to "22")),
        )
        assertTrue(JSONObject(result.output!!).getBoolean("ok"))
    }

    @Test
    fun aTemperatureChangeWithTheClimateOffSaysTheDriverWillNotFeelIt() {
        val vehicle = SimulatedVehicleControl()
        val context = DriverContext()
        context.onDriverUtterance("有点热。", epoch = 1)
        val dispatcher = AndroidToolDispatcher(WillingExecutor(), ClimateToolHandler(vehicle), noCamera()) { context }

        val result = dispatcher.dispatch(
            call("control_climate", mapOf("action" to ClimateToolActions.ADJUST_TEMPERATURE, "value" to "-2")),
        )
        val output = JSONObject(result.output!!)

        // The backend stores the new target happily with the system off, so this really did succeed.
        assertTrue(output.getBoolean("ok"))
        assertFalse(output.getBoolean("power_on"))
        assertTrue(
            output.getString("next").contains("power_on"),
            "a true result that leaves a false impression must carry the correction",
        )
    }

    @Test
    fun aTemperatureChangeWithTheClimateOnNeedsNoSuchWarning() {
        val vehicle = SimulatedVehicleControl()
        val context = DriverContext()
        context.onDriverUtterance("有点热。", epoch = 1)
        val dispatcher = AndroidToolDispatcher(WillingExecutor(), ClimateToolHandler(vehicle), noCamera()) { context }
        dispatcher.dispatch(call("control_climate", mapOf("action" to ClimateToolActions.POWER_ON)))

        context.onDriverUtterance("再凉一点。", epoch = 2)
        val result = dispatcher.dispatch(
            call("control_climate", mapOf("action" to ClimateToolActions.ADJUST_TEMPERATURE, "value" to "-1")),
        )
        val output = JSONObject(result.output!!)
        assertTrue(output.getBoolean("ok"))
        assertFalse(output.has("next"), "nothing to warn about when the climate is on")
    }

    private fun okClimate(): String =
        """{"ok":true,"tool":"control_climate","power_on":true,"temperature_c":24.0,""" +
            """"fan_level":3,"limit_reached":false}"""

    // ---- the context record only trusts proven execution --------------------

    @Test
    fun aFailedClimateResultLeavesNoReferentBehind() {
        val context = DriverContext()
        context.onClimateResult(
            action = ClimateToolActions.ADJUST_TEMPERATURE,
            value = -2.0,
            output = """{"ok":false,"tool":"control_climate","error":"VEHICLE_UNAVAILABLE"}""",
            epoch = 1,
        )
        assertTrue(context.validReferents().isEmpty(), "an action that failed is not a referent")
    }

    @Test
    fun aLateResultForACancelledTurnIsIgnored() {
        val context = DriverContext()
        context.cancel(1)
        context.onClimateResult(
            action = ClimateToolActions.ADJUST_TEMPERATURE,
            value = -2.0,
            output = """{"ok":true,"tool":"control_climate","power_on":true,"temperature_c":22.0,""" +
                """"fan_level":3,"limit_reached":false}""",
            epoch = 1,
        )
        assertTrue(context.validReferents().isEmpty(), "a cancelled turn may not write context")
    }

    // ---- SPEC-015: windows and seat are real tools now; a claim still needs the call ----

    @Test
    fun aWindowClaimWithNoToolCallIsNotReleased() {
        val turn = DriverTurn(epoch = 1)
        turn.onUserTranscript("把车窗打开一半") { DriverTurn.classify(it) }
        assertEquals(DriverTurn.Kind.ACTION, turn.kind)
        turn.onResponseStarted(goodAudio, false)
        val reply = "已经把车窗打开一半了"
        turn.onAssistantText(reply)
        val verdict = turn.onResponseDone(reply, hadToolCallInResponse = false)
        assertFalse(verdict is DriverTurn.Verdict.Release, "$verdict")
    }

    private fun assertNotReleased(request: String, reply: String) {
        val turn = DriverTurn(epoch = 1)
        turn.onUserTranscript(request) { DriverTurn.classify(it) }
        assertEquals(DriverTurn.Kind.ACTION, turn.kind, request)
        turn.onResponseStarted(goodAudio, false)
        turn.onAssistantText(reply)
        val verdict = turn.onResponseDone(reply, hadToolCallInResponse = false)
        assertFalse(verdict is DriverTurn.Verdict.Release, "$request / $reply: $verdict")
    }

    @Test
    fun announceStyleWindowClaimsWithNoToolCallAreNotReleased() {
        assertNotReleased("把车窗关上", "车窗关好了")
        assertNotReleased("关窗", "车窗都关好了")
        assertNotReleased("把车窗打开一半", "车窗都开到了50%")
        assertNotReleased("开窗", "好的，车窗关上了")
    }

    @Test
    fun everyActionAnnouncementIsRecognisedAsAClaim() {
        val all = com.novadrive.vehicle.WindowId.entries.toSet()
        val front = setOf(com.novadrive.vehicle.WindowId.FRONT_LEFT, com.novadrive.vehicle.WindowId.FRONT_RIGHT)
        fun state(fl: Int, fr: Int = fl, rl: Int = fl, rr: Int = fl) = com.novadrive.vehicle.CabinState.DEFAULT.copy(
            windows = mapOf(
                com.novadrive.vehicle.WindowId.FRONT_LEFT to fl, com.novadrive.vehicle.WindowId.FRONT_RIGHT to fr,
                com.novadrive.vehicle.WindowId.REAR_LEFT to rl, com.novadrive.vehicle.WindowId.REAR_RIGHT to rr,
            ),
        )
        val A = com.novadrive.app.vehicle.ActionAnnouncement
        val driver = com.novadrive.vehicle.SeatId.DRIVER
        // Every action template (get_state sentences report state; they are not action claims).
        val sentences = listOf(
            A.window("set", all, state(50), false),
            A.window("open", all, state(100), false),
            A.window("close", all, state(0), false),
            A.window("set", all, state(30), false),
            A.window("open", setOf(com.novadrive.vehicle.WindowId.FRONT_LEFT), state(100, 0, 0, 0), false),
            A.window("close", setOf(com.novadrive.vehicle.WindowId.FRONT_LEFT), state(0), false),
            A.window("adjust", setOf(com.novadrive.vehicle.WindowId.FRONT_LEFT), state(40, 0, 0, 0), false, 20),
            A.window("adjust", front, state(40, 20, 0, 0), true, 20),
            A.window("adjust", all, state(100), true, 20),
            A.window("adjust", all, state(0), true, -20),
            A.seat("adjust_height", driver, 4, false, -1),
            A.seat("adjust_height", driver, 6, false, 1),
            A.seat("adjust_height", driver, 0, true, -1),
            A.seat("adjust_height", driver, 10, true, 1),
            A.seat("set_height", driver, 3, false),
        )
        sentences.forEach {
            assertTrue(ActionClaimGuard.claimsDone(it), "claimsDone: $it")
            assertTrue(ActionClaimGuard.carActionClaim(it) != null, "carActionClaim: $it")
        }
    }

    @Test
    fun drivingTalkWithoutABodyNounIsNotAClaim() {
        listOf("开到目的地大概还要二十分钟", "这条路开到头就是了").forEach { reply ->
            assertEquals(null, ActionClaimGuard.carActionClaim(reply), reply)
            val turn = DriverTurn(epoch = 1)
            turn.onUserTranscript("我们聊聊天吧") { DriverTurn.classify(it) }
            assertEquals(DriverTurn.Kind.CONVERSATION, turn.kind)
            turn.onResponseStarted(goodAudio, false)
            turn.onAssistantText(reply)
            val verdict = turn.onResponseDone(reply, hadToolCallInResponse = false)
            assertTrue(verdict is DriverTurn.Verdict.Release, "$reply: $verdict")
        }
    }

    @Test
    fun openingTheSunroofDoesNotResolveAsAWindow() {
        assertTrue(ActionClaimGuard.isUnsupportedRequest("开天窗"))
        assertEquals(DriverTurn.Kind.NO_TOOL_ACTION, DriverTurn.classify("开天窗"))
        assertEquals(DriverTurn.Kind.ACTION, DriverTurn.classify("关窗"))
    }

    @Test
    fun windowsAndSeatAreActionsButTheSunroofIsStillRefused() {
        listOf("把车窗打开一半", "座位有点高", "座椅调低一点").forEach {
            assertFalse(ActionClaimGuard.isUnsupportedRequest(it), it)
            assertEquals(DriverTurn.Kind.ACTION, DriverTurn.classify(it), it)
        }
        listOf("打开天窗", "打开车门", "打开后备箱").forEach {
            assertTrue(ActionClaimGuard.isUnsupportedRequest(it), it)
            assertEquals(DriverTurn.Kind.NO_TOOL_ACTION, DriverTurn.classify(it), it)
        }
    }

    // ---- SPEC-011 A3/A4: a live answer needs a live result of the same kind, this turn ----

    private val goodAudio = com.novadrive.app.voice.SpeechUplinkGate.Segment(durationMs = 1_500, voicedFrames = 14, peak = 9_000)

    private fun realtimeTurn(request: String): DriverTurn {
        val turn = DriverTurn(epoch = 1)
        turn.onUserTranscript(request) { DriverTurn.classify(it) }
        assertEquals(DriverTurn.Kind.REALTIME_INFO, turn.kind, request)
        return turn
    }

    /** The lookup response: the model calls the tool and says nothing. */
    private fun DriverTurn.callsTheTool() {
        onResponseStarted(goodAudio, false)
        onToolCall()
        assertTrue(onResponseDone("", hadToolCallInResponse = true) is DriverTurn.Verdict.Release)
    }

    /** The answer response, after the tool result was delivered. */
    private fun DriverTurn.answers(reply: String): DriverTurn.Verdict {
        onResponseStarted(goodAudio, false)
        onAssistantText(reply)
        return onResponseDone(reply, hadToolCallInResponse = false)
    }

    private val forecast = "今天北京天气晴转多云，气温20到28度。"

    @Test
    fun aWeatherAnswerWithNoLookupIsStillCorrected() {
        val verdict = realtimeTurn("今天天气怎么样").answers(forecast)
        assertTrue(verdict is DriverTurn.Verdict.Drop, "$verdict")
        val correction = (verdict as DriverTurn.Verdict.Drop).correction.orEmpty()
        assertTrue(correction.contains("query_live_info") && correction.contains("kind=weather"), correction)
    }

    @Test
    fun aWeatherAnswerFromASuccessfulWeatherLookupIsReleased() {
        val turn = realtimeTurn("今天天气怎么样")
        turn.callsTheTool()
        turn.onExecutionResult(ok = true, failure = null, liveInfoKind = "weather")
        val verdict = turn.answers("北京现在多云，24度，南风3级。")
        assertTrue(verdict is DriverTurn.Verdict.Release, "$verdict")
    }

    @Test
    fun aFailedLookupDoesNotLicenceAForecast() {
        // The old rule released anything once a tool had been called; a failed lookup followed by an
        // invented forecast would have reached the driver.
        val turn = realtimeTurn("今天天气怎么样")
        turn.callsTheTool()
        turn.onExecutionResult(ok = false, failure = "LIVE_INFO_UNAVAILABLE")
        val verdict = turn.answers(forecast)
        assertTrue(verdict is DriverTurn.Verdict.Drop, "$verdict")
        val correction = (verdict as DriverTurn.Verdict.Drop).correction.orEmpty()
        assertTrue(correction.contains("没有成功") && correction.contains("不要调用任何工具"), correction)
    }

    @Test
    fun anHonestNoDataAfterAFailedLookupIsHeard() {
        val turn = realtimeTurn("今天天气怎么样")
        turn.callsTheTool()
        turn.onExecutionResult(ok = false, failure = "LIVE_INFO_QUOTA")
        assertTrue(turn.answers("今天的查询次数用完了，现在查不到天气。") is DriverTurn.Verdict.Release)
    }

    @Test
    fun aResultOfAnotherKindIsNotASourceForTheWeather() {
        val turn = realtimeTurn("明天会下雨吗")
        turn.callsTheTool()
        turn.onExecutionResult(ok = true, failure = null, liveInfoKind = "route_traffic")
        assertTrue(turn.answers("明天小雨，17到24度。") is DriverTurn.Verdict.Drop)
    }

    @Test
    fun trafficIsAnswerableFromARouteTrafficResult() {
        assertEquals("route_traffic", ActionClaimGuard.liveInfoKindFor("前面堵不堵"))
        val turn = realtimeTurn("前面堵不堵")
        turn.callsTheTool()
        turn.onExecutionResult(ok = true, failure = null, liveInfoKind = "route_traffic")
        assertTrue(turn.answers("前方1.3公里有两段拥堵。") is DriverTurn.Verdict.Release)
    }

    /** A4 / TRUTH-LIVEINFO-NEWS-001: no lookup can make news, prices or air quality true. */
    @Test
    fun newsAndPricesStayRefusedEvenWithALiveResultInTheTurn() {
        listOf("今天有什么新闻", "现在油价多少", "股票涨了吗", "美元汇率多少", "今天天气和新闻怎么样", "空气质量怎么样").forEach { request ->
            assertEquals(null, ActionClaimGuard.liveInfoKindFor(request), request)
            val turn = realtimeTurn(request)
            turn.callsTheTool()
            turn.onExecutionResult(ok = true, failure = null, liveInfoKind = "weather")
            val verdict = turn.answers("今天的头条是股市大涨，油价每升8块。")
            assertTrue(verdict is DriverTurn.Verdict.Drop, "$request -> $verdict")
            assertTrue((verdict as DriverTurn.Verdict.Drop).correction.orEmpty().contains("没有这类实时信息的数据来源"))
        }
        assertTrue(realtimeTurn("今天有什么新闻").answers("抱歉，我没有新闻的数据来源。") is DriverTurn.Verdict.Release)
    }

    @Test
    fun theDispatcherRefusesAKindWithNoSource() {
        val output = JSONObject(dispatcher(DriverContext()).dispatch(call("query_live_info", mapOf("kind" to "news"))).output!!)
        assertFalse(output.getBoolean("ok"))
        assertEquals("INVALID_KIND", output.getString("error"))
    }
}
