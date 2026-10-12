package com.novadrive.app.nav

import com.novadrive.app.AmapPoiClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/** P51: the nearby search sends Amap a GCJ-02 origin, never the raw WGS-84 platform fix. */
class LiveDestinationCandidateSourceTest {
    private val server = MockWebServer()

    @AfterEach
    fun closeServer() = server.close()

    private fun client() = AmapPoiClient(
        aroundUrl = server.url("/v3/place/around").toString(),
        baseUrl = server.url("/v3/place/text").toString(),
    )

    private fun recorded() = generateSequence { server.takeRequest(100, TimeUnit.MILLISECONDS) }.toList()

    @Test
    fun theNearbySearchSendsTheConvertedOriginOnBothAttempts() {
        server.enqueue(MockResponse().setBody("""{"status":"1","pois":[]}"""))
        server.enqueue(MockResponse().setBody("""{"status":"1","pois":[]}"""))
        val source = LiveDestinationCandidateSource(
            poiClient = client(),
            platformFix = { LocationFix(22.13403, 113.53691) },
            toGcj02 = { it.copy(latitude = 22.13113, longitude = 113.54203) },
            webKey = { "test-key" },
            savedPlaces = { null },
        )
        runBlocking { source.resolve("附近的麦当劳") }
        val sent = recorded()
        assertEquals(2, sent.size, "the keyword and the verbatim retry")
        sent.forEach { request ->
            assertEquals("113.542030,22.131130", request.requestUrl?.queryParameter("location"))
            assertFalse(request.path.orEmpty().contains("113.536910"))
        }
    }

    @Test
    fun withoutAFixTheTextSearchIsUsedAndNothingIsConverted() {
        server.enqueue(MockResponse().setBody("""{"status":"1","pois":[]}"""))
        server.enqueue(MockResponse().setBody("""{"status":"1","pois":[]}"""))
        val source = LiveDestinationCandidateSource(
            poiClient = client(),
            platformFix = { null },
            toGcj02 = { error("no fix, nothing to convert") },
            webKey = { "test-key" },
            savedPlaces = { null },
        )
        runBlocking { source.resolve("横琴镇") }
        val sent = recorded()
        assertTrue(sent.isNotEmpty())
        sent.forEach { request ->
            assertEquals("/v3/place/text", request.requestUrl?.encodedPath)
            assertNull(request.requestUrl?.queryParameter("location"))
        }
    }

    @Test
    fun aSavedPlaceIsNeitherConvertedNorSearched() {
        val home = SavedPlace(PlaceSlot.HOME, "家", "横琴", 22.1, 113.5)
        val source = LiveDestinationCandidateSource(
            poiClient = client(),
            platformFix = { LocationFix(22.13403, 113.53691) },
            toGcj02 = { error("a saved place must not be converted") },
            webKey = { "test-key" },
            savedPlaces = { slot -> home.takeIf { slot == PlaceSlot.HOME } },
        )
        val result = runBlocking { source.resolve("回家") }
        assertEquals(1, result.size)
        assertEquals(0, server.requestCount)
    }
}
