package eu.kanade.tachiyomi.data.tsuzuki.integration

import eu.kanade.tachiyomi.data.track.kitsu.KitsuUserLibraryApi
import eu.kanade.tachiyomi.data.track.kitsu.KitsuUserListEntry
import eu.kanade.tachiyomi.data.track.kitsu.dto.KitsuManga
import eu.kanade.tachiyomi.data.track.kitsu.dto.KitsuMangaPoster
import eu.kanade.tachiyomi.data.track.kitsu.dto.KitsuMangaPosters
import eu.kanade.tachiyomi.data.track.kitsu.dto.KitsuMangaStaffData
import eu.kanade.tachiyomi.data.track.kitsu.dto.KitsuMangaTitles
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import tachiyomi.data.tsuzuki.kitsu.KitsuCatalogProvider
import tachiyomi.data.tsuzuki.kitsu.client.KitsuClient
import tachiyomi.data.tsuzuki.kitsu.dto.KitsuGenreResponse
import tachiyomi.data.tsuzuki.kitsu.dto.KitsuMangaAttributes
import tachiyomi.data.tsuzuki.kitsu.dto.KitsuMangaResource
import tachiyomi.data.tsuzuki.kitsu.dto.KitsuMangaResponse
import tachiyomi.data.tsuzuki.kitsu.dto.KitsuMappingAttributes
import tachiyomi.data.tsuzuki.kitsu.dto.KitsuMappingItemRelationship
import tachiyomi.data.tsuzuki.kitsu.dto.KitsuMappingRelationships
import tachiyomi.data.tsuzuki.kitsu.dto.KitsuMappingResource
import tachiyomi.data.tsuzuki.kitsu.dto.KitsuResourceIdentifier
import tachiyomi.data.tsuzuki.kitsu.dto.KitsuSingleMangaResponse
import tachiyomi.domain.tsuzuki.catalog.model.CatalogError
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
import tachiyomi.domain.tsuzuki.integration.ChapterEvidenceProvider
import tachiyomi.domain.tsuzuki.integration.RatingsProvider
import tachiyomi.domain.tsuzuki.integration.UserListProvider
import tachiyomi.domain.tsuzuki.model.LibraryStatus
import kotlin.time.Instant

class KitsuIntegrationProviderTest {

    @Test
    fun `kitsu search exposes chapter count but does not create chapter evidence`() = runTest {
        val provider = KitsuIntegrationProvider(
            KitsuCatalogProvider(
                FakeKitsuClient(
                    searchResult = Result.success(
                        KitsuMangaResponse(
                            data = listOf(
                                KitsuMangaResource(
                                    id = "kitsu-1",
                                    type = "manga",
                                    attributes = KitsuMangaAttributes(
                                        canonicalTitle = "Dandadan",
                                        chapterCount = 205,
                                    ),
                                ),
                            ),
                            included = listOf(
                                KitsuMappingResource(
                                    id = "mapping-1",
                                    type = "mappings",
                                    attributes = KitsuMappingAttributes(
                                        externalSite = "myanimelist/manga",
                                        externalId = "57325",
                                    ),
                                    relationships = KitsuMappingRelationships(
                                        item = KitsuMappingItemRelationship(
                                            data = KitsuResourceIdentifier(
                                                type = "manga",
                                                id = "kitsu-1",
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val page = provider.search(CatalogQuery(query = "Dandadan")).getOrThrow()

        page.items.single().chapterCount shouldBe 205
        page.items.single().externalIds shouldBe mapOf("mal" to "57325")
        (provider as Any is ChapterEvidenceProvider) shouldBe false
    }

    @Test
    fun `kitsu reads MAL mapping from live JSON API relationship linkage`() = runTest {
        val response = Json {
            ignoreUnknownKeys = true
        }.decodeFromString<KitsuMangaResponse>(
            """
            {
              "data": [
                {
                  "id": "kitsu-one-piece",
                  "type": "manga",
                  "attributes": {
                    "canonicalTitle": "One Piece",
                    "averageRating": "85.08"
                  },
                  "relationships": {
                    "mappings": {
                      "data": [
                        {
                          "type": "mappings",
                          "id": "mapping-one-piece"
                        }
                      ]
                    }
                  }
                }
              ],
              "included": [
                {
                  "id": "mapping-one-piece",
                  "type": "mappings",
                  "attributes": {
                    "externalSite": "myanimelist/manga",
                    "externalId": "13"
                  }
                }
              ]
            }
            """.trimIndent(),
        )
        val provider = KitsuIntegrationProvider(
            KitsuCatalogProvider(
                FakeKitsuClient(
                    searchResult = Result.success(response),
                ),
            ),
        )

        val page = provider.search(CatalogQuery(query = "One Piece")).getOrThrow()

        page.items.single().externalIds shouldBe mapOf("mal" to "13")
    }

    @Test
    fun `kitsu resolves MAL catalog work only through exact published mapping`() = runTest {
        val provider = KitsuIntegrationProvider(
            KitsuCatalogProvider(
                FakeKitsuClient(
                    searchResult = Result.success(
                        KitsuMangaResponse(
                            data = listOf(
                                KitsuMangaResource(
                                    id = "kitsu-one-piece",
                                    type = "manga",
                                    attributes = KitsuMangaAttributes(
                                        canonicalTitle = "One Piece",
                                        averageRating = "85.08",
                                    ),
                                ),
                            ),
                            included = listOf(
                                KitsuMappingResource(
                                    id = "mapping-one-piece",
                                    type = "mappings",
                                    attributes = KitsuMappingAttributes(
                                        externalSite = "myanimelist/manga",
                                        externalId = "13",
                                    ),
                                    relationships = KitsuMappingRelationships(
                                        item = KitsuMappingItemRelationship(
                                            data = KitsuResourceIdentifier(
                                                type = "manga",
                                                id = "kitsu-one-piece",
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val identities = (provider as RatingsProvider).resolveExternalIds(
            CatalogItem(
                provider = "mal",
                providerId = "13",
                title = "One Piece",
            ),
        ).getOrThrow()

        identities shouldBe mapOf(
            "kitsu" to "kitsu-one-piece",
            "mal" to "13",
        )
    }

    @Test
    fun `kitsu resolves missing MAL mapping from exact Kitsu details`() = runTest {
        val provider = KitsuIntegrationProvider(
            KitsuCatalogProvider(
                FakeKitsuClient(
                    searchResult = Result.success(KitsuMangaResponse()),
                    detailResult = Result.success(
                        KitsuSingleMangaResponse(
                            data = KitsuMangaResource(
                                id = "kitsu-one-piece",
                                type = "manga",
                                attributes = KitsuMangaAttributes(
                                    canonicalTitle = "One Piece",
                                    averageRating = "85.08",
                                ),
                            ),
                            included = listOf(
                                KitsuMappingResource(
                                    id = "mapping-one-piece",
                                    type = "mappings",
                                    attributes = KitsuMappingAttributes(
                                        externalSite = "myanimelist/manga",
                                        externalId = "13",
                                    ),
                                    relationships = KitsuMappingRelationships(
                                        item = KitsuMappingItemRelationship(
                                            data = KitsuResourceIdentifier(
                                                type = "manga",
                                                id = "kitsu-one-piece",
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val identities = (provider as RatingsProvider).resolveExternalIds(
            CatalogItem(
                provider = "kitsu",
                providerId = "kitsu-one-piece",
                title = "One Piece",
            ),
        ).getOrThrow()

        identities shouldBe mapOf(
            "kitsu" to "kitsu-one-piece",
            "mal" to "13",
        )
    }

    @Test
    fun `kitsu recovers missing MAL bridge only as an ephemeral rating match`() = runTest {
        val provider = KitsuIntegrationProvider(
            KitsuCatalogProvider(
                FakeKitsuClient(
                    searchResult = Result.success(
                        KitsuMangaResponse(
                            data = listOf(
                                KitsuMangaResource(
                                    id = "kitsu-star",
                                    type = "manga",
                                    attributes = KitsuMangaAttributes(
                                        canonicalTitle = "Star Embracing Swordmaster",
                                        averageRating = "79.88",
                                        startDate = "2023-09-19",
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val match = (provider as RatingsProvider).ratingFor(
            CatalogItem(
                provider = "mal",
                providerId = "123",
                title = "Star-Embracing Swordmaster",
                startDate = "2023-09-19",
            ),
        ).getOrThrow()

        match?.rating?.value shouldBe 79.88
        match?.verifiedIdentity shouldBe false
    }

    @Test
    fun `kitsu exposes account library capability`() = runTest {
        val provider = KitsuIntegrationProvider(
            KitsuCatalogProvider(
                FakeKitsuClient(
                    searchResult = Result.success(KitsuMangaResponse()),
                ),
            ),
        )

        (provider as Any is UserListProvider) shouldBe true
    }

    @Test
    fun `kitsu projects remote statuses formats and timestamps into unified library`() = runTest {
        val updatedAt = "2026-09-30T08:00:00Z"
        val provider = KitsuIntegrationProvider.forTest(
            delegate = KitsuCatalogProvider(
                FakeKitsuClient(
                    searchResult = Result.success(KitsuMangaResponse()),
                ),
            ),
            userLibraryApi = FakeKitsuUserLibraryApi(
                listOf(
                    KitsuUserListEntry(
                        manga = KitsuManga(
                            id = "42",
                            titles = KitsuMangaTitles(preferred = "Solo Leveling"),
                            chapterCount = 200,
                            staff = KitsuMangaStaffData(nodes = emptyList()),
                            posterImage = KitsuMangaPosters(
                                views = listOf(
                                    KitsuMangaPoster(
                                        name = "small",
                                        url = "",
                                    ),
                                ),
                                original = KitsuMangaPoster(
                                    name = "original",
                                    url = "https://example.com/solo.jpg",
                                ),
                            ),
                            description = mapOf("en" to "Hunters and gates."),
                            status = "FINISHED",
                            subtype = "MANHWA",
                            startDate = "2018-03-04",
                            endDate = "2021-12-29",
                            slug = "solo-leveling",
                            averageRating = 84.5,
                        ),
                        status = "PLANNED",
                        progress = 12.0,
                        score = 18.0,
                        updatedAt = updatedAt,
                    ),
                ),
            ),
        )

        val snapshot = (provider as UserListProvider).fetchLibrary().getOrThrow()
        snapshot.lists.map { it.key } shouldBe listOf(
            "kitsu:status:current",
            "kitsu:status:planned",
            "kitsu:status:completed",
            "kitsu:status:on_hold",
            "kitsu:status:dropped",
        )

        val entry = snapshot.entries.single()
        entry.item.provider shouldBe "kitsu"
        entry.item.providerId shouldBe "42"
        entry.item.format shouldBe CatalogItemFormat.MANHWA
        entry.item.score?.value shouldBe 84.5
        entry.item.score?.maxValue shouldBe 100.0
        entry.item.coverUrl shouldBe "https://example.com/solo.jpg"
        entry.listKeys shouldBe setOf("kitsu:status:planned")
        entry.status shouldBe LibraryStatus.PLANNING
        entry.remoteStatus shouldBe "planned"
        entry.progress shouldBe 12.0
        entry.score shouldBe 18.0
        entry.listedAt shouldBe Instant.parse(updatedAt).toEpochMilliseconds()
    }

    @Test
    fun `kitsu exposes provider specific rating capability`() = runTest {
        val provider = KitsuIntegrationProvider(
            KitsuCatalogProvider(
                FakeKitsuClient(
                    searchResult = Result.success(KitsuMangaResponse()),
                    detailResult = Result.success(
                        KitsuSingleMangaResponse(
                            data = KitsuMangaResource(
                                id = "1234",
                                type = "manga",
                                attributes = KitsuMangaAttributes(
                                    canonicalTitle = "Berserk",
                                    averageRating = "84.51",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        (provider as Any is RatingsProvider) shouldBe true
        val rating = (provider as RatingsProvider).ratings("1234").getOrThrow().single()
        rating.providerId shouldBe "kitsu"
        rating.label shouldBe "Kitsu"
        rating.value shouldBe 84.51
        rating.scaleMax shouldBe 100.0
    }

    private class FakeKitsuUserLibraryApi(
        private val entries: List<KitsuUserListEntry>,
    ) : KitsuUserLibraryApi {
        override suspend fun getUserMangaList(): List<KitsuUserListEntry> = entries
    }

    private class FakeKitsuClient(
        private val searchResult: Result<KitsuMangaResponse>,
        private val detailResult: Result<KitsuSingleMangaResponse> =
            Result.failure(CatalogError.ItemNotFound("missing")),
    ) : KitsuClient {

        override suspend fun searchManga(
            query: String?,
            offset: Int,
            limit: Int,
            sort: String?,
            status: String?,
            genres: List<String>,
            subtype: String?,
        ): Result<KitsuMangaResponse> = searchResult

        override suspend fun getGenres(query: String?): Result<KitsuGenreResponse> =
            Result.success(KitsuGenreResponse())

        override suspend fun getTrendingManga(limit: Int): Result<KitsuMangaResponse> =
            Result.success(KitsuMangaResponse())

        override suspend fun getPopularManga(offset: Int, limit: Int): Result<KitsuMangaResponse> =
            Result.success(KitsuMangaResponse())

        override suspend fun getMangaDetails(kitsuId: String): Result<KitsuSingleMangaResponse> =
            detailResult
    }
}
