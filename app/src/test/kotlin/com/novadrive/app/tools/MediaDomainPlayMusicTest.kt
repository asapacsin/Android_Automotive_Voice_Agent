package com.novadrive.app.tools

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** SPEC-017: `play_music` argument rules. */
class MediaDomainPlayMusicTest {
    private fun v(json: String) = MediaDomain.validate("play_music", JSONObject(json))

    @Test
    fun describedRequestsAreValid() {
        assertNull(v("""{"artist":"梶浦由記","album_or_work":"空の境界","mood":"燃"}"""))
        assertNull(v("""{"title":"晴天"}"""))
        assertNull(v("""{"query":"来点开车提神的"}"""))
        assertNull(v("""{"mood":"安静"}"""))
        assertNull(v("""{"title":"sprinter","exclude_title":"oblivious"}"""))
    }

    @Test
    fun emptyOrAllBlankIsMissingValue() {
        assertEquals("INVALID_FIELDS", v("{}"))
        assertEquals("MISSING_VALUE", v("""{"title":"  ","query":""}"""))
        assertEquals("MISSING_VALUE", v("""{"exclude_title":"晴天"}"""))
    }

    @Test
    fun unknownFieldWrongTypeAndTooLongAreRejected() {
        assertEquals("INVALID_FIELDS", v("""{"title":"晴天","volume":"5"}"""))
        assertEquals("INVALID_FIELD_TYPE", v("""{"title":3}"""))
        assertEquals("INVALID_FIELD_VALUE", v("""{"query":"${"长".repeat(81)}"}"""))
        assertNull(v("""{"query":"${"长".repeat(80)}"}"""))
    }

    @Test
    fun declaredRepeatSensitiveWithAllSixOptionalFields() {
        val spec = MediaDomain.specs().first { it.name == "play_music" }
        assertTrue(spec.repeatSensitive)
        val props = spec.parameters.getJSONObject("properties")
        assertEquals(setOf("title", "artist", "album_or_work", "mood", "query", "exclude_title"), props.keySet())
        assertTrue(!spec.parameters.has("required"))
        assertTrue(spec.description.contains("now_playing"))
        assertTrue("play_music" in ToolRegistry.PRODUCT.repeatSensitiveNames)
    }
}
