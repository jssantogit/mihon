package eu.kanade.tachiyomi.data.tsuzuki.integration

import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.data.track.myanimelist.MalIntegrationApi
import eu.kanade.tachiyomi.data.track.myanimelist.MalUserListEntry
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.collections.capability.FilterExecutionMode
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortKey
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortSelection
import tachiyomi.domain.tsuzuki.collections.query.QueryField
import tachiyomi.domain.tsuzuki.collections.query.QueryOperator
import tachiyomi.domain.tsuzuki.collections.query.QueryValue

class MalCollectionQueryProviderTest {

    @Test
    fun `MAL descriptor keeps advanced predicates residual`() {
        val descriptor = MalCollectionCapabilities.descriptor

        descriptor.capabilitiesFor(QueryField.GENRE).single().execution shouldBe
            setOf(FilterExecutionMode.RESIDUAL_EXACT)
        descriptor.capabilitiesFor(QueryField.AUTHOR).single().execution shouldBe
            setOf(FilterExecutionMode.RESIDUAL_EXACT)
        descriptor.scanPolicy.maxRawItemsPerLogicalPage shouldBe 250
        MalCollectionCapabilities.canPushPredicate(
            QueryField.GENRE,
            QueryOperator.EQUALS,
            QueryValue.of("Action"),
        ) shouldBe false
    }

    @Test
    fun `MAL eligible paging ignores novels without skip or duplicate`() = runTest {
        val raw = listOf(
            track(0, "manga"),
            track(1, "novel"),
            track(2, "manga"),
            track(3, "light_novel"),
            track(4, "manhwa"),
            track(5, "manga"),
            track(6, "novel"),
            track(7, "manga"),
        )
        val api = FakeMalApi(raw)
        val provider = MalCollectionQueryProvider.forTest(api)

        val first = provider.fetch(
            pushdownExpression = null,
            sort = CollectionSortSelection(
                CollectionSortKey.Provider("mal", "popularity"),
                direction = null,
            ),
            offset = 0,
            limit = 3,
        ).getOrThrow()
        val second = provider.fetch(
            pushdownExpression = null,
            sort = CollectionSortSelection(
                CollectionSortKey.Provider("mal", "popularity"),
                direction = null,
            ),
            offset = 3,
            limit = 3,
        ).getOrThrow()

        (first.items + second.items).map { it.providerId } shouldContainExactly
            listOf("0", "2", "4", "5", "7")
        second.hasNextPage shouldBe false
        api.rankingTypes.toSet() shouldBe setOf("bypopularity")
    }

    private class FakeMalApi(
        private val raw: List<TrackSearch>,
    ) : MalIntegrationApi {
        val rankingTypes = mutableListOf<String>()

        override suspend fun search(query: String): List<TrackSearch> = emptyList()

        override suspend fun getRanking(
            rankingType: String,
            offset: Int,
            limit: Int,
        ): List<TrackSearch> = getRankingRaw(rankingType, offset, limit)
            .filterNot { it.publishing_type.contains("novel", ignoreCase = true) }

        override suspend fun getRankingRaw(
            rankingType: String,
            offset: Int,
            limit: Int,
        ): List<TrackSearch> {
            rankingTypes += rankingType
            return raw.drop(offset).take(limit)
        }

        override suspend fun getMangaDetails(id: Int): TrackSearch = raw.first()

        override suspend fun getUserMangaList(): List<MalUserListEntry> = emptyList()
    }

    private fun track(id: Long, type: String): TrackSearch = TrackSearch.create(1L).apply {
        remote_id = id
        title = "Title $id"
        publishing_type = type
        publishing_status = "publishing"
        score = 8.0
        genres = listOf("Action")
        start_date = "2020-01-01"
        total_chapters = 100
        total_volumes = 10
    }
}
