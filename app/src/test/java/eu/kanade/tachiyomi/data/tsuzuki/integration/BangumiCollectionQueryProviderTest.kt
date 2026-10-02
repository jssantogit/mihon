package eu.kanade.tachiyomi.data.tsuzuki.integration

import eu.kanade.tachiyomi.data.track.bangumi.BangumiCollectionPage
import eu.kanade.tachiyomi.data.track.bangumi.BangumiCollectionQuery
import eu.kanade.tachiyomi.data.track.bangumi.BangumiIntegrationApi
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortKey
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortSelection
import tachiyomi.domain.tsuzuki.collections.query.QueryExpression
import tachiyomi.domain.tsuzuki.collections.query.QueryField
import tachiyomi.domain.tsuzuki.collections.query.QueryOperator
import tachiyomi.domain.tsuzuki.collections.query.QueryValue

class BangumiCollectionQueryProviderTest {

    @Test
    fun `compiler maps advanced ranges tags exclusions and native sort`() {
        val expression = QueryExpression.All(
            QueryExpression.Predicate(
                QueryField.TAG,
                QueryOperator.EQUALS,
                QueryValue.of("幻想"),
            ),
            QueryExpression.Not(
                QueryExpression.Predicate(
                    QueryField.Custom("bangumi.meta_tag"),
                    QueryOperator.EQUALS,
                    QueryValue.of("科幻"),
                ),
            ),
            QueryExpression.Predicate(
                QueryField.START_DATE,
                QueryOperator.BETWEEN,
                QueryValue.range(QueryValue.of("2020-01-01"), QueryValue.of("2024-12-31")),
            ),
            QueryExpression.Predicate(
                QueryField.SCORE,
                QueryOperator.BETWEEN,
                QueryValue.range(QueryValue.of(7.0), QueryValue.of(9.0)),
            ),
            QueryExpression.Predicate(
                QueryField.Custom("bangumi.rating_count"),
                QueryOperator.GREATER_OR_EQUAL,
                QueryValue.of(200),
            ),
            QueryExpression.Predicate(
                QueryField.RANK,
                QueryOperator.LESS_OR_EQUAL,
                QueryValue.of(5000),
            ),
            QueryExpression.Predicate(
                QueryField.Custom("bangumi.nsfw"),
                QueryOperator.EQUALS,
                QueryValue.of(false),
            ),
        )

        val query = BangumiCollectionCompiler.compile(
            expression = expression,
            sort = CollectionSortSelection(
                CollectionSortKey.Provider("bangumi", "score"),
                direction = null,
            ),
        )

        query.tags shouldContainExactly listOf("幻想")
        query.metaTags shouldContainExactly listOf("-科幻")
        query.airDate shouldContainExactly listOf(">=2020-01-01", "<=2024-12-31")
        query.rating shouldContainExactly listOf(">=7.0", "<=9.0")
        query.ratingCount shouldContainExactly listOf(">=200")
        query.rank shouldContainExactly listOf("<=5000")
        query.nsfw shouldBe false
        query.sort shouldBe "score"
    }

    @Test
    fun `Bangumi eligible paging keeps manga when Book stream contains other platforms`() = runTest {
        val raw = listOf(
            track(0, "Manga"),
            track(1, ""),
            track(2, "Manga"),
            track(3, ""),
            track(4, "Manga"),
            track(5, "Manga"),
        )
        val api = FakeBangumiApi(raw)
        val provider = BangumiCollectionQueryProvider.forTest(api)

        val first = provider.fetch(
            pushdownExpression = null,
            sort = CollectionSortSelection(
                CollectionSortKey.Provider("bangumi", "rank"),
                direction = null,
            ),
            offset = 0,
            limit = 2,
        ).getOrThrow()
        val second = provider.fetch(
            pushdownExpression = null,
            sort = CollectionSortSelection(
                CollectionSortKey.Provider("bangumi", "rank"),
                direction = null,
            ),
            offset = 2,
            limit = 2,
        ).getOrThrow()

        (first.items + second.items).map { it.providerId } shouldContainExactly listOf("0", "2", "4", "5")
        second.hasNextPage shouldBe false
    }

    private class FakeBangumiApi(
        private val raw: List<TrackSearch>,
    ) : BangumiIntegrationApi {
        override suspend fun search(query: String): List<TrackSearch> = emptyList()

        override suspend fun browse(sort: String, offset: Int, limit: Int): List<TrackSearch> = emptyList()

        override suspend fun collectionSearch(query: BangumiCollectionQuery): BangumiCollectionPage {
            val end = minOf(query.offset + query.limit, raw.size)
            return BangumiCollectionPage(
                items = if (query.offset >= raw.size) emptyList() else raw.subList(query.offset, end),
                total = raw.size,
            )
        }

        override suspend fun getMangaDetails(id: Int): TrackSearch = raw.first()
    }

    private fun track(id: Long, type: String): TrackSearch = TrackSearch.create(5L).apply {
        remote_id = id
        title = "Title $id"
        publishing_type = type
        score = 8.0
        score_votes = 100
        start_date = "2022-01-01"
    }
}
