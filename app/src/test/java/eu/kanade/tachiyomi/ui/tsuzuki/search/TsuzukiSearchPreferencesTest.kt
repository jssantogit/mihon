package eu.kanade.tachiyomi.ui.tsuzuki.search

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class TsuzukiSearchPreferencesTest {

    @Test
    fun `recent searches are distinct newest first and capped at twenty`() {
        val preferences = TsuzukiSearchPreferences(
            InMemoryPreferenceStore(),
        )

        (1..21).forEach { index ->
            preferences.recordSearch("Query $index")
        }
        preferences.recordSearch("query 10")

        val recent = preferences.getRecentSearches()

        recent.size shouldBe 20
        recent.first() shouldBe "query 10"
        recent.count { it.equals("query 10", ignoreCase = true) } shouldBe 1
        recent shouldContainExactly listOf(
            "query 10",
            "Query 21",
            "Query 20",
            "Query 19",
            "Query 18",
            "Query 17",
            "Query 16",
            "Query 15",
            "Query 14",
            "Query 13",
            "Query 12",
            "Query 11",
            "Query 9",
            "Query 8",
            "Query 7",
            "Query 6",
            "Query 5",
            "Query 4",
            "Query 3",
            "Query 2",
        )
    }
}
