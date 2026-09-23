package tachiyomi.domain.tsuzuki.reader.interactor

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.domain.tsuzuki.model.SourceMappingAvailability
import tachiyomi.domain.tsuzuki.model.SourceTitleMapping
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository

class ObserveCanonicalTrackerBindingsForMangaTest {

    @Test
    fun `any representation observes one logical tracker binding for the canonical title`() = runTest {
        val mappings = FakeMappings(
            listOf(
                mapping("preferred", "title-1", 11L, preferred = true),
                mapping("other", "title-1", 22L),
                mapping("unrelated", "title-2", 33L),
            ),
        )
        val tracks = FakeTracks(
            listOf(
                track(id = 1L, mangaId = 11L, trackerId = 100L, remoteId = 900L),
                track(id = 2L, mangaId = 22L, trackerId = 100L, remoteId = 900L),
                track(id = 3L, mangaId = 33L, trackerId = 100L, remoteId = 901L),
            ),
        )

        val result = ObserveCanonicalTrackerBindingsForManga(mappings, tracks)
            .execute(mangaId = 22L)
            .first()

        result.tracks.map { it.id } shouldContainExactly listOf(1L)
        result.conflictingTrackerIds shouldBe emptySet()
    }

    @Test
    fun `different remote identities for one tracker become a canonical conflict`() = runTest {
        val mappings = FakeMappings(
            listOf(
                mapping("one", "title-1", 11L),
                mapping("two", "title-1", 22L),
            ),
        )
        val tracks = FakeTracks(
            listOf(
                track(id = 1L, mangaId = 11L, trackerId = 100L, remoteId = 900L),
                track(id = 2L, mangaId = 22L, trackerId = 100L, remoteId = 901L),
            ),
        )

        val result = ObserveCanonicalTrackerBindingsForManga(mappings, tracks)
            .execute(mangaId = 11L)
            .first()

        result.tracks shouldBe emptyList()
        result.conflictingTrackerIds shouldBe setOf(100L)
    }

    @Test
    fun `unmapped operational manga observes no canonical bindings`() = runTest {
        val result = ObserveCanonicalTrackerBindingsForManga(
            sourceTitleMappingRepository = FakeMappings(emptyList()),
            trackRepository = FakeTracks(emptyList()),
        ).execute(mangaId = 999L).first()

        result.tracks shouldBe emptyList()
        result.conflictingTrackerIds shouldBe emptySet()
    }

    private fun mapping(
        id: String,
        canonicalTitleId: String,
        mihonMangaId: Long?,
        preferred: Boolean = false,
    ) = SourceTitleMapping(
        id = id,
        canonicalTitleId = canonicalTitleId,
        mihonMangaId = mihonMangaId,
        sourceId = mihonMangaId ?: 999L,
        sourceUrl = "/$id",
        language = "en",
        matchConfidence = 1.0,
        verifiedByUser = true,
        availability = SourceMappingAvailability.AVAILABLE,
        preferredOverride = preferred,
        createdAt = 100L,
        updatedAt = 100L,
    )

    private fun track(
        id: Long,
        mangaId: Long,
        trackerId: Long,
        remoteId: Long,
    ) = Track(
        id = id,
        mangaId = mangaId,
        trackerId = trackerId,
        remoteId = remoteId,
        libraryId = null,
        title = "Track",
        lastChapterRead = 0.0,
        totalChapters = 0L,
        status = 0L,
        score = 0.0,
        remoteUrl = "",
        startDate = 0L,
        finishDate = 0L,
        private = false,
    )

    private class FakeMappings(
        private val mappings: List<SourceTitleMapping>,
    ) : SourceTitleMappingRepository {
        override suspend fun getAll(): List<SourceTitleMapping> = mappings

        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<SourceTitleMapping> =
            mappings.filter { it.canonicalTitleId == canonicalTitleId }

        override fun getByCanonicalTitleIdAsFlow(canonicalTitleId: String): Flow<List<SourceTitleMapping>> =
            MutableStateFlow(mappings.filter { it.canonicalTitleId == canonicalTitleId })

        override suspend fun getBySource(sourceId: Long, sourceUrl: String): SourceTitleMapping? =
            mappings.firstOrNull { it.sourceId == sourceId && it.sourceUrl == sourceUrl }

        override suspend fun upsert(mapping: SourceTitleMapping) = Unit

        override suspend fun setPreferredForTitle(canonicalTitleId: String, mappingId: String?, updatedAt: Long) = Unit
    }

    private class FakeTracks(
        tracks: List<Track>,
    ) : TrackRepository {
        private val tracks = MutableStateFlow(tracks)

        override suspend fun getTrackById(id: Long): Track? = tracks.value.firstOrNull { it.id == id }
        override suspend fun getTracksByMangaId(mangaId: Long): List<Track> =
            tracks.value.filter { it.mangaId == mangaId }

        override fun getTracksAsFlow(): Flow<List<Track>> = tracks
        override fun getTracksByMangaIdAsFlow(mangaId: Long): Flow<List<Track>> =
            MutableStateFlow(tracks.value.filter { it.mangaId == mangaId })

        override suspend fun delete(mangaId: Long, trackerId: Long) = Unit
        override suspend fun insert(track: Track) = Unit
        override suspend fun insertAll(tracks: List<Track>) = Unit
    }
}
