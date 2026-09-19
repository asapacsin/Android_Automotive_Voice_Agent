package com.novadrive.app

import com.novadrive.app.nav.PlaceSlot
import com.novadrive.app.nav.SavedPlace
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * 「回家」 must not become a POI search.
 *
 * The defect this covers is not a crash. A search for 家 returns shops and other people's
 * addresses, so the driver would be routed somewhere confidently wrong — the exact failure class
 * [ToolCallGuards] exists for.
 */
class SavedPlaceGuardTest {
    private val home = SavedPlace(PlaceSlot.HOME, "家", "珠海市香洲区某路 1 号", 22.24, 113.53)

    @Test
    fun `an unset home is refused with a code the driver can be told about`() {
        assertEquals(
            ToolCallGuards.HOME_NOT_SET,
            ToolCallGuards.savedPlaceMissing("回家") { null },
        )
    }

    @Test
    fun `an unset work slot is refused separately`() {
        assertEquals(
            ToolCallGuards.WORK_NOT_SET,
            ToolCallGuards.savedPlaceMissing("去公司") { null },
        )
    }

    @Test
    fun `a set home passes through to the resolver`() {
        assertNull(ToolCallGuards.savedPlaceMissing("返屋企") { slot ->
            home.takeIf { slot == PlaceSlot.HOME }
        })
    }

    @Test
    fun `an ordinary destination is never treated as a saved place`() {
        assertNull(ToolCallGuards.savedPlaceMissing("珠海站") { null })
        assertNull(ToolCallGuards.savedPlaceMissing("家乐福") { null })
    }

    @Test
    fun `every refusal code carries wording for the driver`() {
        // A code with no advice reaches the model as a bare string and gets improvised around.
        listOf(ToolCallGuards.HOME_NOT_SET, ToolCallGuards.WORK_NOT_SET).forEach { code ->
            val advice = ToolFailureAdvice.forCode(code)
            assert(!advice.isNullOrBlank()) { "$code has no advice, so the driver hears an invention" }
        }
    }
}
