package com.aeriotv.android.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** One page of /api/epg/programs/search/?airing_at=..&fields=id,custom_properties. */
class NowAiringSearchParserTest {
    private val fixture = """
        {"count": 712, "next": "http://dispatcharr:9191/api/epg/programs/search/?airing_at=2026-10-26T18%3A00%3A00Z&page=2&page_size=500", "previous": null,
         "results": [
           {"id": 101, "custom_properties": {"categories": ["Sports", " Football ", ""], "rating": "TV-PG"}},
           {"id": 102, "custom_properties": {"categories": []}},
           {"id": 103, "custom_properties": null},
           {"id": 104, "custom_properties": {"season": 3}},
           {"id": "105", "custom_properties": {"categories": ["News"]}},
           {"custom_properties": {"categories": ["Orphan"]}}
         ]}
    """.trimIndent()

    @Test fun parsesCategoriesAndNext() {
        val page = NowAiringSearchParser.parse(fixture)
        assertTrue(page.hasNext)
        assertEquals(mapOf(101 to "Sports,Football", 102 to "", 103 to "", 104 to "", 105 to "News"), page.categories)
    }

    @Test fun lastPageHasNoNext() {
        val page = NowAiringSearchParser.parse("""{"count":1,"next":null,"previous":null,"results":[{"id":7,"custom_properties":{"categories":["Movie"]}}]}""")
        assertFalse(page.hasNext)
        assertEquals(mapOf(7 to "Movie"), page.categories)
    }

    @Test fun flatArrayAndGarbage() {
        assertEquals(mapOf(9 to "Kids"), NowAiringSearchParser.parse("""[{"id":9,"custom_properties":{"categories":["Kids"]}}]""").categories)
        val bad = NowAiringSearchParser.parse("<html>nope</html>")
        assertTrue(bad.categories.isEmpty()); assertFalse(bad.hasNext)
    }
}
