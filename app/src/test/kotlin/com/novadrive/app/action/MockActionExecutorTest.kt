package com.novadrive.app.action

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MockActionExecutorTest {
    @Test
    fun successMessagesForKnownPairs() = runBlocking {
        val executor = MockActionExecutor()
        assertEquals("空调已打开", executor.execute(ActionRequest("air_conditioner", "turn_on")).message)
        assertEquals("空调已关闭", executor.execute(ActionRequest("air_conditioner", "turn_off")).message)
        assertEquals("蓝牙已打开", executor.execute(ActionRequest("bluetooth", "turn_on")).message)
        assertEquals("蓝牙已关闭", executor.execute(ActionRequest("bluetooth", "turn_off")).message)
        assertEquals("灯光已打开", executor.execute(ActionRequest("light", "turn_on")).message)
        assertEquals("灯光已关闭", executor.execute(ActionRequest("light", "turn_off")).message)
        assertEquals("音乐已暂停", executor.execute(ActionRequest("music", "pause")).message)
        assertEquals("音乐已继续", executor.execute(ActionRequest("music", "resume")).message)
        val ok = executor.execute(ActionRequest("air_conditioner", "turn_on"))
        assertTrue(ok.success)
        assertNull(ok.errorCode)
    }

    @Test
    fun unknownTargetIsUnsupportedAction() = runBlocking {
        val result = MockActionExecutor().execute(ActionRequest("fridge", "turn_on"))
        assertFalse(result.success)
        assertEquals("UNSUPPORTED_ACTION", result.errorCode)
    }

    @Test
    fun unknownActionIsUnsupportedAction() = runBlocking {
        val result = MockActionExecutor().execute(ActionRequest("air_conditioner", "set_temperature"))
        assertFalse(result.success)
        assertEquals("UNSUPPORTED_ACTION", result.errorCode)
    }

    @Test
    fun injectedDeviceOfflineFailure() = runBlocking {
        val executor = MockActionExecutor()
        executor.injectDeviceOffline("air_conditioner")
        val result = executor.execute(ActionRequest("air_conditioner", "turn_on"))
        assertFalse(result.success)
        assertEquals("DEVICE_OFFLINE", result.errorCode)
        assertEquals("空调连接失败", result.message)
    }
}
