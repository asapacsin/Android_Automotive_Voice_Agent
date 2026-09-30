package com.novadrive.app.tools

import com.novadrive.app.AllowedApp
import com.novadrive.app.AndroidActionExecutor
import com.novadrive.app.AndroidActionResult
import com.novadrive.app.AndroidToolDispatcher
import com.novadrive.app.noCamera
import com.novadrive.app.vehicle.ClimateToolHandler
import com.novadrive.ingress.realtime.DomainVoiceEvent
import com.novadrive.ingress.realtime.ToolDispatchResult
import com.novadrive.simulator.SimulatedVehicleControl
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** SPEC-016 B / ADR-015: every product tool reaches the server of the domain that declares it. */
class ToolServerRoutingTest {
    private val env = ToolCallEnv(
        driverContext = { null },
        failedFormat = { call, code, _ -> ToolDispatchResult(null, null, blockedReason = code, output = call.name) },
        resultFormat = { call, _ -> ToolDispatchResult(null, null, output = call.name) },
    )

    private class Recording(override val domain: ToolDomain) : ToolServer {
        val calls = mutableListOf<String>()
        override fun call(call: DomainVoiceEvent.ToolCall, env: ToolCallEnv): ToolDispatchResult {
            calls += call.name
            return ToolDispatchResult(null, null)
        }
    }

    private val productDomains =
        ToolRegistry.PRODUCT.tools().map { ToolRegistry.PRODUCT.domainOf(it.name)!! }.distinctBy { it.id }

    private fun call(name: String) = DomainVoiceEvent.ToolCall("call_1", name, emptyMap())

    @Test
    fun everyProductToolRoutesToTheServerOfItsDomain() {
        val fakes = productDomains.map { Recording(it) }
        val index = AndroidToolDispatcher.serverIndex(ToolRegistry.PRODUCT, fakes)
        ToolRegistry.PRODUCT.tools().forEach { spec ->
            val domain = ToolRegistry.PRODUCT.domainOf(spec.name)!!
            index.getValue(domain.id).call(call(spec.name), env)
        }
        fakes.forEach { fake ->
            assertEquals(fake.domain.specs().map { it.name }, fake.calls, "domain ${fake.domain.id}")
        }
        assertEquals(9, productDomains.size)
    }

    @Test
    fun aDomainWithoutAServerFailsConstruction() {
        val missing = productDomains.drop(1).map { Recording(it) }
        assertThrows<IllegalArgumentException> { AndroidToolDispatcher.serverIndex(ToolRegistry.PRODUCT, missing) }
    }

    @Test
    fun twoServersForOneDomainFailConstruction() {
        val fakes = productDomains.map { Recording(it) } + Recording(BodyDomain)
        assertThrows<IllegalArgumentException> { AndroidToolDispatcher.serverIndex(ToolRegistry.PRODUCT, fakes) }
    }

    @Test
    fun aServerForADomainOutsideTheRegistryFailsConstruction() {
        val stranger = object : ToolDomain {
            override val id = "body"
            override fun specs() = BodyDomain.specs()
            override fun validate(name: String, args: JSONObject): String? = null
        }
        val fakes = productDomains.filter { it.id != "body" }.map { Recording(it) } + Recording(stranger)
        assertThrows<IllegalArgumentException> { AndroidToolDispatcher.serverIndex(ToolRegistry.PRODUCT, fakes) }
        val extra = productDomains.map { Recording(it) } + Recording(object : ToolDomain {
            override val id = "sunroof"
            override fun specs() = emptyList<com.novadrive.app.voice.RealtimeToolCatalog.ToolSpec>()
            override fun validate(name: String, args: JSONObject): String? = null
        })
        assertThrows<IllegalArgumentException> { AndroidToolDispatcher.serverIndex(ToolRegistry.PRODUCT, extra) }
    }

    @Test
    fun theProductDispatcherHasAServerForEveryDomain() {
        // Construction runs the fail-fast index over the real servers.
        AndroidToolDispatcher(CountingExecutor(), ClimateToolHandler(SimulatedVehicleControl()), noCamera()) { null }
    }

    @Test
    fun unknownToolIsRefusedWithoutExecuting() {
        val executor = CountingExecutor()
        val dispatcher =
            AndroidToolDispatcher(executor, ClimateToolHandler(SimulatedVehicleControl()), noCamera()) { null }
        val result = dispatcher.dispatch(call("no_such_tool"))
        assertEquals("UNKNOWN_TOOL", JSONObject(result.output!!).getString("error"))
        assertEquals(0, executor.executions)
    }

    @Test
    fun aServerRefusesAToolItsDomainDoesNotDeclare() {
        val result = SpeechServer(CountingExecutor()).call(call("open_app"), env)
        assertEquals("UNKNOWN_TOOL", result.blockedReason)
    }

    private class CountingExecutor : AndroidActionExecutor {
        var executions = 0
        private fun run(): AndroidActionResult { executions++; return AndroidActionResult.Accepted() }
        override fun navigate(destination: String) = run()
        override fun openApp(app: AllowedApp) = run()
        override fun playMusic() = run()
        override fun stopMusic() = run()
        override fun exitNavigationMode() = run()
        override fun setSpeechSilent(silent: Boolean) = run()
        override fun endConversation() = run()
    }

    init {
        assertTrue(productDomains.isNotEmpty())
    }
}
