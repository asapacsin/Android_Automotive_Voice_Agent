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
    fun fakePhonePortDoesNotNeedAndroid() {
        val zhang = ResolvedContact("1", "张三", "13800000000")
        val port = FakePhonePort(contacts = listOf(zhang))
        assertTrue(port.telephonyAvailable())
        assertEquals(ContactMatchKind.UNIQUE, port.resolve("张三").kind)
        assertTrue(port.dial(zhang))
        assertEquals(listOf(zhang), port.dialled)
    }
}
