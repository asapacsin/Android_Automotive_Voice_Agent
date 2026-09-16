package com.novadrive.app.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class NavigationCandidatesTest {
    @Test
    fun metresUnderOneKilometreStayMetres() {
        assertEquals("850 m", NavigationFormatters.formatDistanceMeters(850))
        assertEquals("0 m", NavigationFormatters.formatDistanceMeters(0))
        assertEquals("999 m", NavigationFormatters.formatDistanceMeters(999))
    }

    @Test
    fun metresAtOrAboveOneKilometreUseOneDecimalKm() {
        assertEquals("18.2 km", NavigationFormatters.formatDistanceMeters(18_200))
        assertEquals("1.0 km", NavigationFormatters.formatDistanceMeters(1_000))
    }

    @Test
    fun secondsUnderSixtyMinutesAreMinutes() {
        assertEquals("29 分钟", NavigationFormatters.formatDurationSeconds(29 * 60))
        assertEquals("0 分钟", NavigationFormatters.formatDurationSeconds(0))
        assertEquals("59 分钟", NavigationFormatters.formatDurationSeconds(59 * 60))
    }

    @Test
    fun secondsAtOrAboveSixtyMinutesIncludeHours() {
        assertEquals("1 小时", NavigationFormatters.formatDurationSeconds(60 * 60))
        assertEquals("1 小时 30 分钟", NavigationFormatters.formatDurationSeconds(90 * 60))
        assertEquals("2 小时", NavigationFormatters.formatDurationSeconds(120 * 60))
    }

    @Test
    fun routeCandidateSdkValuesDriveDisplayedStrings() {
        val route = RouteCandidate(
            routeId = 12,
            distanceMeters = 18_200,
            durationSeconds = 29 * 60,
            labels = "高速优先",
            trafficLightCount = 4,
        )
        assertEquals("18.2 km", NavigationFormatters.formatDistanceMeters(route.distanceMeters))
        assertEquals("29 分钟", NavigationFormatters.formatDurationSeconds(route.durationSeconds))
        assertEquals("高速优先", route.labels)
    }

    @Test
    fun destinationCandidateRejectsOutOfRangeCoordinates() {
        assertThrows<IllegalArgumentException> {
            DestinationCandidate(
                id = "x",
                name = "invalid",
                address = "",
                district = "",
                latitude = 91.0,
                longitude = 0.0,
            )
        }
        assertThrows<IllegalArgumentException> {
            DestinationCandidate(
                id = "x",
                name = "invalid",
                address = "",
                district = "",
                latitude = 0.0,
                longitude = -181.0,
            )
        }
    }

    @Test
    fun destinationCandidateMapsToDestination() {
        val candidate = DestinationCandidate(
            id = "p1",
            name = "珠海站",
            address = "广东省珠海市",
            district = "香洲区",
            latitude = 22.2024,
            longitude = 113.5432,
            distanceMeters = 850,
            poiId = "B000A",
        )
        val destination = candidate.toDestination()
        assertEquals("珠海站", destination.name)
        assertEquals(22.2024, destination.latitude)
        assertEquals(113.5432, destination.longitude)
        assertEquals("B000A", destination.poiId)
        assertEquals("广东省珠海市", destination.address)
        assertEquals(850, candidate.distanceMeters)
        assertNull(RouteCandidate(1, 1, 1).labels)
    }
}
