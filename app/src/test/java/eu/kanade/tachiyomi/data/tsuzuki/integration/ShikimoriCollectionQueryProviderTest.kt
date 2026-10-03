package eu.kanade.tachiyomi.data.tsuzuki.integration

import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.data.track.shikimori.ShikimoriCollectionPage
import eu.kanade.tachiyomi.data.track.shikimori.ShikimoriCollectionQuery
import eu.kanade.tachiyomi.data.track.shikimori.ShikimoriIntegrationApi
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.collections.capability.FilterOption
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortKey
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortSelection
import tachiyomi.domain.tsuzuki.collections.query.QueryExpression
import tachiyomi.domain.tsuzuki.collections.query.QueryField
import tachiyomi.domain.tsuzuki.collections.query.QueryOperator
import tachiyomi.domain.tsuzuki.collections.query.QueryValue

class ShikimoriCollectionQueryProviderTest {

    @Test
    fun `compiler preserves native include exclude filters and fixed order`() {
        val expression = QueryExpression.All(
            QueryExpression.Predicate(
                QueryField.WORK_TYPE,
                QueryOperator.EQUALS,
                QueryValue.of("MANGA"),
            ),
            QueryExpression.Not(
                QueryExpression.Predicate(
                    QueryField.STATUS,
                    QueryOperator.EQUALS,
                    QueryValue.of("COMPLETED"),
                ),
            ),
            QueryExpression.Predicate(
                QueryField.START_YEAR,
                QueryOperator.BETWEEN,
                QueryValue.range(QueryValue.of(2018), QueryValue.of(2024)),
            ),
            QueryExpression.Predicate(
                QueryField.SCORE,
                QueryOperator.GREATER_OR_EQUAL,
                QueryValue.of(7),
            ),
            QueryExpression.Predicate(
                QueryField.GENRE,
                QueryOperator.EQUALS,
                QueryValue.of("1"),
            ),
            QueryExpression.Not(
                QueryExpression.Predicate(
                    QueryField.PUBLISHER,
                    QueryOperator.EQUALS,
                    QueryValue.of("4"),
                ),
            ),
            QueryExpression.Predicate(
                QueryField.Custom("shikimori.franchise"),
                QueryOperator.EQUALS,
                QueryValue.of("berserk"),
            ),
            QueryExpression.Predicate(
                QueryField.Custom("shikimori.censored"),
                QueryOperator.EQUALS,
                QueryValue.of(false),
            ),
        )
        val sort = CollectionSortSelection(
            CollectionSortKey.Provider("shikimori", "popularity"),
            direction = null,
        )

        val query = ShikimoriCollectionCompiler.compile(expression, sort)

        query.kind shouldBe "manga"
        query.status shouldBe "!released"
        query.season shouldBe "2018_2024"
        query.score shouldBe 7
        query.genre shouldBe "1"
        query.publisher shouldBe "!4"
        query.franchise shouldBe "berserk"
        query.censored shouldBe false
        query.order shouldBe "popularity"
    }

    @Test
    fun `compiler combines native include and exclude values for the same field`() {
        val expression = QueryExpression.All(
            QueryExpression.Predicate(
                QueryField.GENRE,
                QueryOperator.EQUALS,
                QueryValue.of("1"),
            ),
            QueryExpression.Not(
                QueryExpression.Predicate(
                    QueryField.GENRE,
                    QueryOperator.EQUALS,
                    QueryValue.of("2"),
                ),
            ),
            QueryExpression.Predicate(
                QueryField.PUBLISHER,
                QueryOperator.EQUALS,
                QueryValue.of("4"),
            ),
            QueryExpression.Not(
                QueryExpression.Predicate(
                    QueryField.PUBLISHER,
                    QueryOperator.EQUALS,
                    QueryValue.of("5"),
                ),
            ),
        )

        val query = ShikimoriCollectionCompiler.compile(
            expression = expression,
            sort = CollectionSortSelection(
                CollectionSortKey.Provider("shikimori", "popularity"),
                direction = null,
            ),
        )

        query.genre shouldBe "1,!2"
        query.publisher shouldBe "4,!5"
    }

    @Test
    fun `provider exposes Shikimori genre and publisher lookups through shared gate`() = runTest {
        var gateCalls = 0
        val gate = ShikimoriRequestGate(
            nowMillis = { 0L },
            pause = {},
            onPermit = { gateCalls++ },
        )
        val api = object : ShikimoriIntegrationApi {
            override suspend fun searchPublic(query: String): List<TrackSearch> = emptyList()
            override suspend fun getMangaDetailsPublic(id: Int): TrackSearch? = null
            override suspend fun lookupGenres(): List<Pair<String, String>> =
                listOf("Romance" to "1", "Action" to "2")
            override suspend fun lookupPublishers(): List<Pair<String, String>> =
                listOf("Shueisha" to "4", "Kodansha" to "5")
        }
        val provider = ShikimoriCollectionQueryProvider.forTest(api, gate)

        provider.lookupValues("shikimori.genres", "roma").getOrThrow() shouldContainExactly listOf(
            FilterOption("1", "Romance", QueryValue.of("1")),
        )
        provider.lookupValues("shikimori.publishers", "shuei").getOrThrow() shouldContainExactly listOf(
            FilterOption("4", "Shueisha", QueryValue.of("4")),
        )
        gateCalls shouldBe 2
    }

    @Test
    fun `provider normalizes Shikimori page API under shared request gate`() = runTest {
        var gateCalls = 0
        val gate = ShikimoriRequestGate(
            nowMillis = { 0L },
            pause = {},
            onPermit = { gateCalls++ },
        )
        val calls = mutableListOf<ShikimoriCollectionQuery>()
        val api = object : ShikimoriIntegrationApi {
            override suspend fun searchPublic(query: String): List<TrackSearch> = emptyList()
            override suspend fun getMangaDetailsPublic(id: Int): TrackSearch? = null
            override suspend fun collectionSearch(query: ShikimoriCollectionQuery): ShikimoriCollectionPage {
                calls += query
                val all = (0 until 12).map { track(it.toLong()) }
                val start = (query.page - 1) * query.limit
                val end = minOf(start + query.limit, all.size)
                return ShikimoriCollectionPage(
                    items = if (start >= all.size) emptyList() else all.subList(start, end),
                    page = query.page,
                    limit = query.limit,
                    hasNextPage = end < all.size,
                )
            }
        }

        val page = ShikimoriCollectionQueryProvider.forTest(api, gate).fetch(
            pushdownExpression = null,
            sort = CollectionSortSelection(
                CollectionSortKey.Provider("shikimori", "popularity"),
                direction = null,
            ),
            offset = 3,
            limit = 5,
        ).getOrThrow()

        page.items.map { it.providerId } shouldContainExactly listOf("3", "4", "5", "6", "7")
        page.hasNextPage shouldBe true
        calls.isNotEmpty() shouldBe true
        gateCalls shouldBe calls.size
    }

    private fun track(id: Long): TrackSearch = TrackSearch.create(4L).apply {
        remote_id = id
        title = "Title $id"
        publishing_type = "manga"
        publishing_status = "ongoing"
        score = 8.0
        start_date = "2024"
        tracking_url = "https://shikimori.one/mangas/$id"
    }
}
