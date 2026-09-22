package com.novadrive.contracts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CapabilityCatalogTest {
    @Test
    fun productCatalogTreatsPlaceCallAsSupportedAndVolumeAsUnsupported() {
        assertTrue(ProductCapabilities.isSupported(CapabilityIds.PHONE_PLACE_CALL))
        assertEquals("place_call", ProductCapabilities.record(CapabilityIds.PHONE_PLACE_CALL)?.tool)
        assertFalse(ProductCapabilities.isSupported(CapabilityIds.VOLUME_CONTROL))
        assertEquals(CapabilityStatus.UNREGISTERED, ProductCapabilities.status("windows.open"))
    }

    @Test
    fun overlayChangesAvailabilityWithoutEditingKeywords() {
        val off = ProductCapabilities.overlay(mapOf(CapabilityIds.PHONE_PLACE_CALL to false))
        assertFalse(off.isSupported(CapabilityIds.PHONE_PLACE_CALL))
        assertTrue(ProductCapabilities.isSupported(CapabilityIds.PHONE_PLACE_CALL))
        val on = ProductCapabilities.overlay(mapOf(CapabilityIds.VOLUME_CONTROL to true))
        assertTrue(on.isSupported(CapabilityIds.VOLUME_CONTROL))
    }

    @Test
    fun spokenHelpSummaryReflectsSupportedGroupsOnly() {
        val full = ProductCapabilities.spokenHelpSummary()
        assertTrue(full.contains("导航"))
        assertTrue(full.contains("音乐"))
        assertTrue(full.contains("空调"))
        assertFalse(full.contains("音量"))
        assertFalse(full.contains("天气"))
        assertFalse(full.contains("下一首"))

        val noPhone = ProductCapabilities.overlay(mapOf(CapabilityIds.PHONE_PLACE_CALL to false))
        val summary = ProductCapabilities.spokenHelpSummary(noPhone)
        assertFalse(summary.contains("电话"))
        assertTrue(summary.contains("导航"))
    }

    @Test
    fun fakePhonePortDoesNotNeedAndroid() {
        val zhang = ResolvedContact("1", "张三", "13800000000")
        val port = FakePhonePort(contacts = listOf(zhang))
        assertTrue(port.telephonyAvailable())
        assertEquals(ContactMatchKind.UNIQUE, port.resolve("张三").kind)
        assertTrue(port.dial(zhang))
        assertEquals(listOf(zhang), port.dialled)
    }
}
