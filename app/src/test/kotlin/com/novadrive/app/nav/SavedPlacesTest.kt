package com.novadrive.app.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.Test

class SavedPlacesTest {

    @TestFactory
    fun `phrasings that mean home`(): List<DynamicTest> = listOf(
        "回家", "返屋企", "屋企", "我家", "家", "导航回家", "我要回家", "返屋企啦", "带我回家",
        "home", "go home",
    ).map { spoken ->
        DynamicTest.dynamicTest(spoken) { assertEquals(PlaceSlot.HOME, SavedPlaces.slotFor(spoken)) }
    }

    @TestFactory
    fun `phrasings that mean work`(): List<DynamicTest> = listOf(
        "公司", "去公司", "回公司", "上班", "返工", "办公室", "单位", "导航去公司", "office",
    ).map { spoken ->
        DynamicTest.dynamicTest(spoken) { assertEquals(PlaceSlot.WORK, SavedPlaces.slotFor(spoken)) }
    }

    @TestFactory
    fun `places that merely contain the word are not the slot`(): List<DynamicTest> = listOf(
        // Each of these would route the driver somewhere wrong if a substring match were used.
        "家乐福", "宜家", "我家附近的星巴克", "公司附近的星巴克", "海底捞", "珠海站", "农业银行",
    ).map { spoken ->
        DynamicTest.dynamicTest(spoken) { assertNull(SavedPlaces.slotFor(spoken)) }
    }

    @Test
    fun `a saved place resolves to exactly one candidate with its own coordinates`() {
        val place = SavedPlace(PlaceSlot.HOME, "家", "珠海市香洲区某路 1 号", 22.24, 113.53)
        val candidate = place.toCandidate()
        assertEquals("saved:home", candidate.id)
        assertEquals(22.24, candidate.latitude)
        assertEquals(113.53, candidate.longitude)
    }

    @Test
    fun `a coordinate outside the world is refused rather than stored`() {
        val failure = runCatching { SavedPlace(PlaceSlot.WORK, "公司", "", 91.0, 0.0) }
        assert(failure.isFailure) { "a latitude of 91 is not a place" }
    }
}
