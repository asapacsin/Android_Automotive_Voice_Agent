package com.novadrive.app.wake

import com.novadrive.app.voice.StartResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WakeWordControllerTest {
    @Test
    fun startResultAlreadyActiveAndStartedAreIgnored() {
        assertNull(logForStartResult(StartResult.Started))
        assertNull(logForStartResult(StartResult.AlreadyActive))
    }

    @Test
    fun startResultFailuresUseFixedWakeLogStrings() {
        assertEquals("wake_permission_failure", logForStartResult(StartResult.MicPermissionMissing))
        assertEquals("wake_not_attached", logForStartResult(StartResult.NotAttached))
        assertEquals("wake_config_invalid", logForStartResult(StartResult.ConfigInvalid("hidden")))
    }
}
