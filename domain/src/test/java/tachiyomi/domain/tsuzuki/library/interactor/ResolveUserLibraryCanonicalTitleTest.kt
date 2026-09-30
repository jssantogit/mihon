package tachiyomi.domain.tsuzuki.library.interactor

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.interactor.MaterializeCanonicalTitle
import tachiyomi.domain.tsuzuki.interactor.MaterializeCanonicalTitleFromCatalog
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.model.SourceMappingAvailability
import tachiyomi.domain.tsuzuki.model.SourceTitleMapping
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository

class ResolveUserLibraryCanonicalTitleTest {

    @Test
    fun `exact legacy tracker binding attaches provider identity to existing canonical title`() = runTest {
        val titleRepository = FakeCanonicalTitleRepository(
            titles = mutableMapOf(
                "local" to canonicalTitle("local", CanonicalIdentityState.SOURCE_ONLY),
            ),
        )
        val resolver = resolver(
            titleRepository = titleRepository,
            mappings = listOf(sourceMapping("local", mangaId = 10L)),
            tracks = listOf(track(mangaId = 10L, trackerId = 1L, remoteId = 42L)),
        )

        val result = resolver.execute(
            item = CatalogItem(provider = "mal", providerId = "42", title = "Monster"),
            legacyTrackerId = 1L,
        )

        result.id shouldBe "local"
        titleRepository.getByExternalIdentity("mal", "42")?.id shouldBe "local"
        titleRepository.titles.size shouldBe 1
    }

    @Test
    fun `ambiguous legacy binding never merges canonical titles`() = runTest {
        val titleRepository = FakeCanonicalTitleRepository(
            titles = mutableMapOf(
                "local-a" to canonicalTitle("local-a", CanonicalIdentityState.SOURCE_ONLY),
                "local-b" to canonicalTitle("local-b", CanonicalIdentityState.SOURCE_ONLY),
            ),
        )
        val resolver = resolver(
            titleRepository = titleRepository,
            mappings = listOf(
                sourceMapping("local-a", mangaId = 10L),
                sourceMapping("local-b", mangaId = 20L),
            ),
            tracks = listOf(
                track(mangaId = 10L, trackerId = 1L, remoteId = 42L),
                track(mangaId = 20L, trackerId = 1L, remoteId = 42L),
            ),
        )

        val result = resolver.execute(
            item = CatalogItem(provider = "mal", providerId = "42", title = "Monster"),
            legacyTrackerId = 1L,
        )

        result.id shouldBe "new-canonical"
        titleRepository.getByExternalIdentity("mal", "42")?.id shouldBe "new-canonical"
        titleRepository.titles.keys shouldBe setOf("local-a", "local-b", "new-canonical")
    }

    @Test
    fun `non numeric provider identity does not guess from legacy track data`() = runTest {
        val titleRepository = FakeCanonicalTitleRepository(
            titles = mutableMapOf(
                "local" to canonicalTitle("local", CanonicalIdentityState.SOURCE_ONLY),
            ),
        )
        val resolver = resolver(
            titleRepository = titleRepository,
            mappings = listOf(sourceMapping("local", mangaId = 10L)),
            tracks = listOf(track(mangaId = 10L, trackerId = 1L, remoteId = 42L)),
        )

        val result = resolver.execute(
            item = CatalogItem(provider = "future-provider", providerId = "slug-42", title = "Monster"),
            legacyTrackerId = 1L,
        )

        result.id shouldBe "new-canonical"
        titleRepository.titles.size shouldBe 2
    }

    private fun resolver(
        titleRepository: FakeCanonicalTitleRepository,
        mappings: List<SourceTitleMapping>,
        tracks: List<Track>,
    ): ResolveUserLibraryCanonicalTitle {
        val materialize = MaterializeCanonicalTitleFromCatalog(
            MaterializeCanonicalTitle(
                repository = titleRepository,
                idFactory = { "new-canonical" },
                clock = { 100L },
            ),
        )
        return ResolveUserLibraryCanonicalTitle(
            canonicalTitleRepository = titleRepository,
            sourceTitleMappingRepository = FakeSourceTitleMappingRepository(mappings),
            trackRepository = FakeTrackRepository(tracks),
            materializeCanonicalTitleFromCatalog = materialize,
            clock = { 200L },
        )
    }

    private fun canonicalTitle(id: String, state: CanonicalIdentityState) = CanonicalTitle(
        id = id,
        displayTitle = id,
        identityState = state,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun sourceMapping(canonicalTitleId: String, mangaId: Long) = SourceTitleMapping(
        id = "mapping-$canonicalTitleId",
        canonicalTitleId = canonicalTitleId,
        mihonMangaId = mangaId,
        sourceId = mangaId,
        sourceUrl = "/$mangaId",
        language = "en",
        matchConfidence = null,
        verifiedByUser = true,
        availability = SourceMappingAvailability.AVAILABLE,
        preferredOverride = false,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun track(mangaId: Long, trackerId: Long, remoteId: Long) = Track(
        id = mangaId,
        mangaId = mangaId,
        trackerId = trackerId,
        remoteId = remoteId,
        libraryId = null,
        title = "fixture",
        lastChapterRead = 0.0,
        totalChapters = 0L,
        status = 0L,
        score = 0.0,
        remoteUrl = "",
        startDate = 0L,
        finishDate = 0L,
        private = false,
    )

    private class FakeTrackRepository(
        tracks: List<Track>,
    ) : TrackRepository {
        private val flow = MutableStateFlow(tracks)

        override suspend fun getTrackById(id: Long): Track? = flow.value.firstOrNull { it.id == id }
        override suspend fun getTracksByMangaId(mangaId: Long): List<Track> =
            flow.value.filter { it.mangaId == mangaId }
        override fun getTracksAsFlow(): Flow<List<Track>> = flow
        override fun getTracksByMangaIdAsFlow(mangaId: Long): Flow<List<Track>> =
            MutableStateFlow(flow.value.filter { it.mangaId == mangaId })
        override suspend fun delete(mangaId: Long, trackerId: Long) = Unit
        override suspend fun insert(track: Track) = Unit
        override suspend fun insertAll(tracks: List<Track>) = Unit
    }

    private class FakeSourceTitleMappingRepository(
        private val mappings: List<SourceTitleMapping>,
    ) : SourceTitleMappingRepository {
        override suspend fun getAll(): List<SourceTitleMapping> = mappings
        override fun getAllAsFlow(): Flow<List<SourceTitleMapping>> = MutableStateFlow(mappings)
        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<SourceTitleMapping> =
            mappings.filter { it.canonicalTitleId == canonicalTitleId }
        override fun getByCanonicalTitleIdAsFlow(canonicalTitleId: String): Flow<List<SourceTitleMapping>> =
            MutableStateFlow(mappings.filter { it.canonicalTitleId == canonicalTitleId })
        override suspend fun getBySource(sourceId: Long, sourceUrl: String): SourceTitleMapping? = null
        override suspend fun upsert(mapping: SourceTitleMapping) = Unit
        override suspend fun setPreferredForTitle(
            canonicalTitleId: String,
            mappingId: String?,
            updatedAt: Long,
        ) = Unit
    }

    private class FakeCanonicalTitleRepository(
        val titles: MutableMap<String, CanonicalTitle>,
    ) : CanonicalTitleRepository {
        private val identities = mutableListOf<ExternalIdentity>()

        override suspend fun getById(id: String): CanonicalTitle? = titles[id]
        override fun getByIdAsFlow(id: String): Flow<CanonicalTitle?> = MutableStateFlow(titles[id])
        override fun getAllAsFlow(): Flow<List<CanonicalTitle>> = MutableStateFlow(titles.values.toList())

        override suspend fun getByExternalIdentity(provider: String, externalId: String): CanonicalTitle? {
            val titleId = identities
                .firstOrNull { it.provider == provider && it.externalId == externalId }
                ?.canonicalTitleId
            return titleId?.let(titles::get)
        }

        override suspend fun getOrCreateByExternalIdentity(
            title: CanonicalTitle,
            identity: ExternalIdentity,
        ): CanonicalTitle {
            getByExternalIdentity(identity.provider, identity.externalId)?.let { return it }
            titles[title.id] = title
            identities += identity
            return title
        }

        override suspend fun insert(title: CanonicalTitle) {
            titles[title.id] = title
        }

        override suspend fun addExternalIdentity(identity: ExternalIdentity) {
            val existing = identities.firstOrNull {
                it.provider == identity.provider && it.externalId == identity.externalId
            }
            require(existing == null || existing.canonicalTitleId == identity.canonicalTitleId)
            if (existing == null) identities += identity
        }
    }
}
