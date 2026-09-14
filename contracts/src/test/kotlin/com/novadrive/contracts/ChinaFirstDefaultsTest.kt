package com.novadrive.contracts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ChinaFirstDefaultsTest {
    @Test
    fun localePersonaWakeAndMetricAreChinaFirst() {
        val config = VoiceAssistantConfig()
        assertEquals("zh-CN", config.locale)
        assertEquals("小诺", config.personaName)
        assertEquals("你好小诺", config.wakePhrase)
        assertEquals(UnitSystem.METRIC, config.unitSystem)
        assertEquals(CoordinateSystem.GCJ02, ChinaFirstDefaults.preferredCoordinateSystem)
    }

    @Test
    fun geoCoordinateCarriesCoordinateSystemAndRejectsProviderDefinedWithoutId() {
        val gcj = GeoCoordinate(31.2304, 121.4737, CoordinateSystem.GCJ02)
        assertEquals(CoordinateSystem.GCJ02, gcj.coordinateSystem)
        assertNull(gcj.providerDefinedSystemId)
        assertThrows<IllegalArgumentException> {
            GeoCoordinate(31.0, 121.0, CoordinateSystem.PROVIDER_DEFINED)
        }
        val oem = GeoCoordinate(31.0, 121.0, CoordinateSystem.PROVIDER_DEFINED, "oem-frame-a")
        assertEquals("oem-frame-a", oem.providerDefinedSystemId)
        assertTrue(NavigationProviderKind.entries.containsAll(listOf(
            NavigationProviderKind.FAKE,
            NavigationProviderKind.AMAP,
            NavigationProviderKind.BAIDU,
            NavigationProviderKind.OEM,
        )))
    }
}
