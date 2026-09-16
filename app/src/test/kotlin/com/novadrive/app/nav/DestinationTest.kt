package com.novadrive.app.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DestinationTest {
    @Test
    fun validCoordinatesConstruct() {
        val destination = Destination(
            name = "珠海站",
            latitude = 22.2024,
            longitude = 113.5432,
            poiId = "B000A",
            address = "广东省珠海市",
        )
        assertEquals("珠海站", destination.name)
        assertEquals(22.2024, destination.latitude)
        assertEquals(113.5432, destination.longitude)
        assertEquals("B000A", destination.poiId)
        assertEquals("广东省珠海市", destination.address)
    }

    @Test
    fun latitude91ThrowsIllegalArgumentException() {
        assertThrows<IllegalArgumentException> {
            Destination(name = "invalid", latitude = 91.0, longitude = 0.0)
        }
    }

    @Test
    fun longitudeNegative181ThrowsIllegalArgumentException() {
        assertThrows<IllegalArgumentException> {
            Destination(name = "invalid", latitude = 0.0, longitude = -181.0)
        }
    }
}
