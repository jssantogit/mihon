package eu.kanade.tachiyomi.data.track.mangaupdates

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MangaUpdatesPaginationTest {

    @Test
    fun `server page-size cap does not truncate a longer user list`() {
        MangaUpdatesApi.shouldFetchNextUserListPage(
            totalHits = 120,
            accumulatedCount = 50,
            resultCount = 50,
            responsePageSize = 50,
        ) shouldBe true
    }

    @Test
    fun `short final page completes user-list pagination`() {
        MangaUpdatesApi.shouldFetchNextUserListPage(
            totalHits = 120,
            accumulatedCount = 120,
            resultCount = 20,
            responsePageSize = 50,
        ) shouldBe false
    }
}
