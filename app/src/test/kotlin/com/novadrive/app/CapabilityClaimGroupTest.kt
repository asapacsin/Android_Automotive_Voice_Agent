package com.novadrive.app

import com.novadrive.app.voice.DriverContext
import com.novadrive.app.voice.DriverContext.ClaimSource.LOCAL
import com.novadrive.app.voice.DriverContext.ClaimSource.MODEL
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** P67 / SPEC-010 B4: one execution per control per turn, whichever side is first. */
class CapabilityClaimGroupTest {
    private val ctx = DriverContext()
    private val climate = "control_climate"
    private val music = "control_music"

    @Test fun `opposite power action from the model is refused after local`() {
        assertTrue(ctx.claimCapability(1, climate, "power_on", LOCAL))
        assertFalse(ctx.claimCapability(1, climate, "power_off", MODEL))
    }

    @Test fun `opposite power action from local is refused after model`() {
        assertTrue(ctx.claimCapability(1, climate, "power_off", MODEL))
        assertFalse(ctx.claimCapability(1, climate, "power_on", LOCAL))
    }

    @Test fun `temperature is a different control from power`() {
        assertTrue(ctx.claimCapability(1, climate, "power_on", LOCAL))
        assertTrue(ctx.claimCapability(1, climate, "set_temperature", MODEL))
    }

    @Test fun `music play then stop across sources is refused`() {
        assertTrue(ctx.claimCapability(1, music, "play", LOCAL))
        assertFalse(ctx.claimCapability(1, music, "stop", MODEL))
    }

    @Test fun `music stop then play across sources is refused`() {
        assertTrue(ctx.claimCapability(1, music, "stop", LOCAL))
        assertFalse(ctx.claimCapability(1, music, "play", MODEL))
    }

    @Test fun `a different epoch is allowed`() {
        assertTrue(ctx.claimCapability(1, climate, "power_on", LOCAL))
        assertTrue(ctx.claimCapability(2, climate, "power_off", MODEL))
    }

    @Test fun `the same source claiming twice is allowed`() {
        assertTrue(ctx.claimCapability(1, climate, "power_on", MODEL))
        assertTrue(ctx.claimCapability(1, climate, "power_off", MODEL))
    }

    @Test fun `model get_state after local power is refused`() {
        assertTrue(ctx.claimCapability(1, climate, "power_on", LOCAL))
        assertFalse(ctx.claimCapability(1, climate, "get_state", MODEL))
    }

    @Test fun `model get_state first never blocks local power`() {
        assertTrue(ctx.claimCapability(1, climate, "get_state", MODEL))
        assertTrue(ctx.claimCapability(1, climate, "power_on", LOCAL))
    }

    @Test fun `model get_state with no local claim is allowed`() {
        assertTrue(ctx.claimCapability(1, climate, "get_state", MODEL))
    }
}
