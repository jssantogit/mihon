package eu.kanade.tachiyomi.data.track.mangaupdates.dto

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class MUUserListsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `user list metadata preserves custom title and type`() {
        val list = json.decodeFromString<MUUserList>(
            """
            {
              "list_id": 12,
              "title": "Favorites",
              "description": "Personal picks",
              "type": "read",
              "icon": "star",
              "custom": true,
              "options": { "public": false }
            }
            """.trimIndent(),
        )

        list.listId shouldBe 12L
        list.title shouldBe "Favorites"
        list.type shouldBe "read"
        list.custom shouldBe true
    }

    @Test
    fun `list search preserves progress rating and membership timestamp`() {
        val page = json.decodeFromString<MUUserListSearchResponse>(
            """
            {
              "total_hits": 1,
              "page": 1,
              "per_page": 100,
              "results": [
                {
                  "series_id": 42,
                  "series_title": "Solo Leveling",
                  "volume": 10,
                  "chapter": 77,
                  "metadata": {
                    "user_rating": 9.0,
                    "user_list": {
                      "series": { "id": 42, "title": "Solo Leveling" },
                      "list_id": 12,
                      "list_type": "read",
                      "status": { "volume": 10, "chapter": 77 },
                      "priority": 0,
                      "time_added": {
                        "timestamp": 1790755200,
                        "as_rfc3339": "2026-09-30T08:00:00Z",
                        "as_string": "2026-09-30"
                      }
                    }
                  }
                }
              ]
            }
            """.trimIndent(),
        )

        val result = page.results.single()
        page.totalHits shouldBe 1
        result.seriesId shouldBe 42L
        result.metadata.userRating shouldBe 9.0
        result.metadata.userList?.listId shouldBe 12L
        result.metadata.userList?.status?.chapter shouldBe 77
        result.metadata.userList?.timeAdded?.asRfc3339 shouldBe "2026-09-30T08:00:00Z"
    }
}
