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
import org.junit.jupiter.api.Test
import tachiyomi.data.tsuzuki.kitsu.KitsuCatalogProvider
import tachiyomi.data.tsuzuki.kitsu.client.KitsuClient
import tachiyomi.data.tsuzuki.kitsu.dto.KitsuMangaAttributes
import tachiyomi.data.tsuzuki.kitsu.dto.KitsuMangaRelationships
import tachiyomi.data.tsuzuki.kitsu.dto.KitsuMangaResource
import tachiyomi.data.tsuzuki.kitsu.dto.KitsuMangaResponse
import tachiyomi.data.tsuzuki.kitsu.dto.KitsuMappingAttributes
import tachiyomi.data.tsuzuki.kitsu.dto.KitsuMappingResource
import tachiyomi.data.tsuzuki.kitsu.dto.KitsuResourceIdentifier
import tachiyomi.data.tsuzuki.kitsu.dto.KitsuResourceIdentifiersRelationship
import tachiyomi.data.tsuzuki.kitsu.dto.KitsuSingleMangaResponse
import tachiyomi.domain.tsuzuki.catalog.model.CatalogError
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
                                    relationships = KitsuMangaRelationships(
                                        mappings = KitsuResourceIdentifiersRelationship(
                                            data = listOf(
                                                KitsuResourceIdentifier(
                                                    type = "mappings",
                                                    id = "mapping-1",
                                                ),
                                            ),
                                        ),
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
                                views = emptyList(),
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

    @Test
    fun `kitsu resolves its rating from a MAL catalog identity without title matching`() = runTest {
        val provider = KitsuIntegrationProvider(
            KitsuCatalogProvider(
                FakeKitsuClient(
                    searchResult = Result.success(KitsuMangaResponse()),
                    malLookupResult = Result.success(
                        KitsuSingleMangaResponse(
                            data = KitsuMangaResource(
                                id = "kitsu-12",
                                type = "manga",
                                attributes = KitsuMangaAttributes(
                                    canonicalTitle = "One Piece",
                                    averageRating = "85.08",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val resolution = (provider as RatingsProvider).resolveRatings(
            CatalogItem(
                provider = "mal",
                providerId = "13",
                title = "One Piece",
            ),
        ).getOrThrow()

        resolution?.externalId shouldBe "kitsu-12"
        resolution?.ratings?.single()?.providerId shouldBe "kitsu"
        resolution?.ratings?.single()?.value shouldBe 85.08
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
        private val malLookupResult: Result<KitsuSingleMangaResponse?> =
            Result.success(null),
    ) : KitsuClient {

        override suspend fun searchManga(
            query: String?,
            offset: Int,
            limit: Int,
            sort: String?,
            status: String?,
        ): Result<KitsuMangaResponse> = searchResult

        override suspend fun getTrendingManga(limit: Int): Result<KitsuMangaResponse> =
            Result.success(KitsuMangaResponse())

        override suspend fun getPopularManga(offset: Int, limit: Int): Result<KitsuMangaResponse> =
            Result.success(KitsuMangaResponse())

        override suspend fun getMangaDetails(kitsuId: String): Result<KitsuSingleMangaResponse> =
            detailResult

        override suspend fun getMangaByMalId(malId: String): Result<KitsuSingleMangaResponse?> =
            malLookupResult
    }
}
