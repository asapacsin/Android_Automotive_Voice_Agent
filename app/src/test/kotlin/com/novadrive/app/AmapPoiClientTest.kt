package com.novadrive.app

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AmapPoiClientTest {
    private val server = MockWebServer()

    @AfterEach
    fun closeServer() = server.close()

    @Test
    fun resolveSendsKeywordsAndKeyAndParsesBody() {
        server.enqueue(
            MockResponse().setBody(
                """{"status":"1","pois":[{"name":"天安门广场","location":"116.397500,39.908740"}]}""",
            ),
        )
        val result = client().resolve("天安门广场", "test-key")
        assertEquals(PoiResult("天安门广场", 39.908740, 116.397500), result)
        val recorded = server.takeRequest()
        assertEquals("天安门广场", recorded.requestUrl?.queryParameter("keywords"))
        assertEquals("test-key", recorded.requestUrl?.queryParameter("key"))
        assertEquals("1", recorded.requestUrl?.queryParameter("offset"))
        assertEquals("1", recorded.requestUrl?.queryParameter("page"))
        assertEquals("base", recorded.requestUrl?.queryParameter("extensions"))
        assertEquals("JSON", recorded.requestUrl?.queryParameter("output"))
    }

    @Test
    fun http500ReturnsNullWithoutThrowing() {
        server.enqueue(MockResponse().setResponseCode(500))
        assertNull(client().resolve("天安门广场", "test-key"))
    }

    @Test
    fun garbageBodyReturnsNullWithoutThrowing() {
        server.enqueue(MockResponse().setBody("not-json"))
        assertNull(client().resolve("天安门广场", "test-key"))
    }

    @Test
    fun resolveNearbySendsLocationRadiusAndDistanceSort() {
        server.enqueue(
            MockResponse().setBody(
                """{"status":"1","pois":[{"name":"天安门广场","location":"116.397500,39.908740"}]}""",
            ),
        )
        val result = aroundClient().resolveNearby("加油站", "test-key", 39.908740, 116.397500)
        assertEquals(PoiResult("天安门广场", 39.908740, 116.397500), result)
        val recorded = server.takeRequest()
        assertEquals("加油站", recorded.requestUrl?.queryParameter("keywords"))
        assertEquals("test-key", recorded.requestUrl?.queryParameter("key"))
        assertEquals("116.397500,39.908740", recorded.requestUrl?.queryParameter("location"))
        assertEquals("50000", recorded.requestUrl?.queryParameter("radius"))
        assertEquals("distance", recorded.requestUrl?.queryParameter("sortrule"))
        assertEquals("1", recorded.requestUrl?.queryParameter("offset"))
        assertEquals("1", recorded.requestUrl?.queryParameter("page"))
        assertEquals("base", recorded.requestUrl?.queryParameter("extensions"))
        assertEquals("JSON", recorded.requestUrl?.queryParameter("output"))
    }

    @Test
    fun resolveNearbyHttp500ReturnsNull() {
        server.enqueue(MockResponse().setResponseCode(500))
        assertNull(aroundClient().resolveNearby("加油站", "test-key", 39.908740, 116.397500))
    }

    @Test
    fun resolveCandidatesSendsLimitAsOffsetAndParsesWholeArray() {
        server.enqueue(
            MockResponse().setBody(
                """{"status":"1","pois":[""" +
                    """{"name":"珠海站","address":"香洲区","location":"113.543200,22.202400","id":"B001","adname":"香洲区"},""" +
                    """{"name":"珠海机场","address":"金湾区","location":"113.376000,22.006400","id":"B002","adname":"金湾区"}""" +
                    """]}""",
            ),
        )
        val result = client().resolveCandidates("珠海", "test-key", limit = 5)
        assertEquals(2, result.size)
        assertEquals("B001", result[0].id)
        assertEquals("B002", result[1].id)
        val recorded = server.takeRequest()
        assertEquals("珠海", recorded.requestUrl?.queryParameter("keywords"))
        assertEquals("test-key", recorded.requestUrl?.queryParameter("key"))
        assertEquals("5", recorded.requestUrl?.queryParameter("offset"))
        assertEquals("1", recorded.requestUrl?.queryParameter("page"))
    }

    @Test
    fun resolveNearbyCandidatesPopulatesDistanceAndUsesAroundUrl() {
        server.enqueue(
            MockResponse().setBody(
                """{"status":"1","pois":[{"name":"加油站","address":"近处","location":"116.397500,39.908740","id":"P1","adname":"东城区","distance":"120"}]}""",
            ),
        )
        val result = aroundClient().resolveNearbyCandidates("加油站", "test-key", 39.908740, 116.397500, limit = 5)
        assertEquals(1, result.size)
        assertEquals(120, result[0].distanceMeters)
        val recorded = server.takeRequest()
        assertEquals("116.397500,39.908740", recorded.requestUrl?.queryParameter("location"))
        assertEquals("distance", recorded.requestUrl?.queryParameter("sortrule"))
        assertEquals("5", recorded.requestUrl?.queryParameter("offset"))
    }

    @Test
    fun resolveCandidatesHttp500ReturnsEmptyList() {
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(emptyList<com.novadrive.app.nav.DestinationCandidate>(), client().resolveCandidates("珠海", "test-key"))
    }

    private fun client() = AmapPoiClient(baseUrl = server.url("/v3/place/text").toString())

    private fun aroundClient() = AmapPoiClient(aroundUrl = server.url("/v3/place/around").toString())
}
