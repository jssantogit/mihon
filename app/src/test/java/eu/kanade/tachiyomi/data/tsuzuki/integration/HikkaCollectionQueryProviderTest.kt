package eu.kanade.tachiyomi.data.tsuzuki.integration

import eu.kanade.tachiyomi.data.track.hikka.HikkaCollectionPage
import eu.kanade.tachiyomi.data.track.hikka.HikkaCollectionQuery
import eu.kanade.tachiyomi.data.track.hikka.HikkaIntegrationApi
import eu.kanade.tachiyomi.data.track.hikka.dto.HKManga
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortDirection
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortKey
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortSelection
import tachiyomi.domain.tsuzuki.collections.query.QueryExpression
import tachiyomi.domain.tsuzuki.collections.query.QueryField
import tachiyomi.domain.tsuzuki.collections.query.QueryOperator
import tachiyomi.domain.tsuzuki.collections.query.QueryValue

class HikkaCollectionQueryProviderTest {

    @Test
    fun `compiler keeps independent mal and hikka score ranges`() {
        val expression = QueryExpression.All(
            QueryExpression.Predicate(
                QueryField.START_YEAR,
                QueryOperator.BETWEEN,
                QueryValue.range(QueryValue.of(2020), QueryValue.of(2024)),
            ),
            QueryExpression.Predicate(
                QueryField.WORK_TYPE,
                QueryOperator.EQUALS,
                QueryValue.of("MANHWA"),
            ),
            QueryExpression.Predicate(
                QueryField.STATUS,
                QueryOperator.EQUALS,
                QueryValue.of("ONGOING"),
            ),
            QueryExpression.Predicate(
                QueryField.Custom("hikka.only_translated"),
                QueryOperator.EQUALS,
                QueryValue.of(true),
            ),
            QueryExpression.Predicate(
                QueryField.GENRE,
                QueryOperator.EQUALS,
                QueryValue.of("romance"),
            ),
            QueryExpression.Predicate(
                QueryField.Custom("hikka.magazine"),
                QueryOperator.EQUALS,
                QueryValue.of("comic-zenon-dc20de"),
            ),
            QueryExpression.Predicate(
                QueryField.Custom("hikka.mal_score"),
                QueryOperator.BETWEEN,
                QueryValue.range(QueryValue.of(6.0), QueryValue.of(9.0)),
            ),
            QueryExpression.Predicate(
                QueryField.SCORE,
                QueryOperator.BETWEEN,
                QueryValue.range(QueryValue.of(7.0), QueryValue.of(10.0)),
            ),
        )
        val sort = CollectionSortSelection(
            CollectionSortKey.Provider("hikka", "native_score"),
            CollectionSortDirection.DESC,
        )

        val query = HikkaCollectionCompiler.compile(expression, sort)

        query.yearFrom shouldBe 2020
        query.yearTo shouldBe 2024
        query.mediaTypes shouldContainExactly listOf("manhwa")
        query.statuses shouldContainExactly listOf("ongoing")
        query.onlyTranslated shouldBe true
        query.genres shouldContainExactly listOf("romance")
        query.magazines shouldContainExactly listOf("comic-zenon-dc20de")
        query.malScoreFrom shouldBe 6.0
        query.malScoreTo shouldBe 9.0
        query.nativeScoreFrom shouldBe 7.0
        query.nativeScoreTo shouldBe 10.0
        query.sort shouldBe "native_score:desc"
    }

    @Test
    fun `provider normalizes Hikka page API to raw offsets`() = runTest {
        val calls = mutableListOf<HikkaCollectionQuery>()
        val api = object : HikkaIntegrationApi {
            override suspend fun searchPublic(query: String): List<HKManga> = emptyList()
            override suspend fun getMangaDetailsPublic(slug: String): HKManga? = null
            override suspend fun collectionSearch(query: HikkaCollectionQuery): HikkaCollectionPage {
                calls += query
                val all = (0 until 12).map { manga("m-$it", "Manga $it") }
                val start = (query.page - 1) * query.size
                val end = minOf(start + query.size, all.size)
                return HikkaCollectionPage(
                    items = if (start >= all.size) emptyList() else all.subList(start, end),
                    page = query.page,
                    pages = (all.size + query.size - 1) / query.size,
                    total = all.size,
                )
            }
        }

        val page = HikkaCollectionQueryProvider.forTest(api).fetch(
            pushdownExpression = null,
            sort = CollectionSortSelection(
                CollectionSortKey.Provider("hikka", "native_score"),
                CollectionSortDirection.DESC,
            ),
            offset = 3,
            limit = 5,
        ).getOrThrow()

        page.items.map { it.providerId } shouldContainExactly listOf("m-3", "m-4", "m-5", "m-6", "m-7")
        page.hasNextPage shouldBe true
        page.totalCount shouldBe 12
        calls.isNotEmpty() shouldBe true
    }

    private fun manga(slug: String, title: String): HKManga =
        Json { ignoreUnknownKeys = true }.decodeFromString(
            """
            {
              "data_type": "manga",
              "title_original": "$title",
              "media_type": "manga",
              "translated_ua": false,
              "status": "ongoing",
              "image": "https://example.invalid/cover.jpg",
              "year": 2024,
              "native_scored_by": 10,
              "native_score": 8.0,
              "scored_by": 100,
              "score": 7.5,
              "slug": "$slug"
            }
            """.trimIndent(),
        )
}
