package eu.kanade.tachiyomi.data.tsuzuki.integration

import eu.kanade.tachiyomi.data.track.mangaupdates.MangaUpdatesCollectionPage
import eu.kanade.tachiyomi.data.track.mangaupdates.MangaUpdatesCollectionQuery
import eu.kanade.tachiyomi.data.track.mangaupdates.MangaUpdatesIntegrationApi
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.collections.capability.FilterOption
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortKey
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortSelection
import tachiyomi.domain.tsuzuki.collections.query.QueryExpression
import tachiyomi.domain.tsuzuki.collections.query.QueryField
import tachiyomi.domain.tsuzuki.collections.query.QueryOperator
import tachiyomi.domain.tsuzuki.collections.query.QueryValue

class MangaUpdatesCollectionQueryProviderTest {

    @Test
    fun `compiler maps manga include and exclude genres and native order exactly`() {
        val expression = QueryExpression.All(
            QueryExpression.Predicate(
                QueryField.WORK_TYPE,
                QueryOperator.EQUALS,
                QueryValue.of("MANGA"),
            ),
            QueryExpression.Predicate(
                QueryField.GENRE,
                QueryOperator.CONTAINS,
                QueryValue.of("Romance"),
            ),
            QueryExpression.Not(
                QueryExpression.Predicate(
                    QueryField.GENRE,
                    QueryOperator.CONTAINS,
                    QueryValue.of("Hentai"),
                ),
            ),
        )
        val sort = CollectionSortSelection(
            CollectionSortKey.Provider("mangaupdates", "week_pos"),
            direction = null,
        )

        val query = MangaUpdatesCollectionCompiler.compile(
            pushdownExpression = expression,
            sort = sort,
        )

        query.type shouldBe "Manga"
        query.genre shouldBe "Romance"
        query.excludeGenre shouldBe "Hentai"
        query.orderBy shouldBe "week_pos"
    }

    @Test
    fun `provider normalizes page API to absolute raw offsets without skips`() = runTest {
        val calls = mutableListOf<MangaUpdatesCollectionQuery>()
        val api = object : MangaUpdatesIntegrationApi {
            override suspend fun search(query: String): List<TrackSearch> = emptyList()
            override suspend fun discover(orderBy: String, offset: Int, limit: Int): List<TrackSearch> = emptyList()
            override suspend fun getMangaDetails(id: Long): TrackSearch = track(id)
            override suspend fun collectionSearch(query: MangaUpdatesCollectionQuery): MangaUpdatesCollectionPage {
                calls += query
                val all = (0L until 12L).map(::track)
                val start = (query.page - 1) * query.perPage
                val end = minOf(start + query.perPage, all.size)
                return MangaUpdatesCollectionPage(
                    items = if (start >= all.size) emptyList() else all.subList(start, end),
                    page = query.page,
                    perPage = query.perPage,
                    totalHits = all.size,
                )
            }
        }
        val provider = MangaUpdatesCollectionQueryProvider.forTest(api)

        val result = provider.fetch(
            pushdownExpression = null,
            sort = CollectionSortSelection(
                CollectionSortKey.Provider("mangaupdates", "score"),
                direction = null,
            ),
            offset = 3,
            limit = 5,
        ).getOrThrow()

        result.items.map { it.providerId } shouldContainExactly listOf("3", "4", "5", "6", "7")
        result.hasNextPage shouldBe true
        result.totalCount shouldBe 12
        calls.isNotEmpty() shouldBe true
    }

    @Test
    fun `blank MangaUpdates category lookup does not call remote search`() = runTest {
        var categoryCalls = 0
        val api = object : MangaUpdatesIntegrationApi {
            override suspend fun search(query: String): List<TrackSearch> = emptyList()
            override suspend fun discover(orderBy: String, offset: Int, limit: Int): List<TrackSearch> = emptyList()
            override suspend fun getMangaDetails(id: Long): TrackSearch = track(id)
            override suspend fun lookupCategories(query: String): List<Pair<String, String>> {
                categoryCalls++
                return emptyList()
            }
        }
        val provider = MangaUpdatesCollectionQueryProvider.forTest(api)

        provider.lookupValues("mangaupdates.categories", "").getOrThrow() shouldBe emptyList()
        categoryCalls shouldBe 0
    }

    @Test
    fun `provider exposes dynamic genre and category lookups`() = runTest {
        val api = object : MangaUpdatesIntegrationApi {
            override suspend fun search(query: String): List<TrackSearch> = emptyList()
            override suspend fun discover(orderBy: String, offset: Int, limit: Int): List<TrackSearch> = emptyList()
            override suspend fun getMangaDetails(id: Long): TrackSearch = track(id)
            override suspend fun lookupGenres(): List<Pair<String, String>> =
                listOf("Romance" to "Romance")
            override suspend fun lookupCategories(query: String): List<Pair<String, String>> {
                query shouldBe "award"
                return listOf("Award Winning" to "Award Winning")
            }
        }
        val provider = MangaUpdatesCollectionQueryProvider.forTest(api)

        provider.lookupValues("mangaupdates.genres").getOrThrow() shouldContainExactly listOf(
            FilterOption("Romance", "Romance", QueryValue.of("Romance")),
        )
        provider.lookupValues("mangaupdates.categories", "award").getOrThrow() shouldContainExactly listOf(
            FilterOption("Award Winning", "Award Winning", QueryValue.of("Award Winning")),
        )
    }

    @Test
    fun `descriptor keeps unverified year syntax residual while exposing proven remote fields`() {
        val descriptor = MangaUpdatesCollectionCapabilities.descriptor

        descriptor.capabilitiesFor(QueryField.START_YEAR)
            .all { capability ->
                tachiyomi.domain.tsuzuki.collections.capability.FilterExecutionMode.REMOTE_EXACT !in
                    capability.execution
            } shouldBe true
        MangaUpdatesCollectionCapabilities.canPushPredicate(
            QueryField.Custom("mangaupdates.licensed"),
            QueryOperator.EQUALS,
            QueryValue.of(true),
        ) shouldBe true
        MangaUpdatesCollectionCapabilities.canPushPredicate(
            QueryField.CATEGORY,
            QueryOperator.EQUALS,
            QueryValue.of("Award Winning"),
        ) shouldBe true
    }

    private fun track(id: Long): TrackSearch = TrackSearch.create(0).apply {
        remote_id = id
        title = "Title $id"
        publishing_type = CatalogItemFormat.MANGA.name
        tracking_url = "https://example.invalid/$id"
    }
}
