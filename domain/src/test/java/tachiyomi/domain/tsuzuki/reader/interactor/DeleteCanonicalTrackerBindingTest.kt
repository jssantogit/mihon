package tachiyomi.domain.tsuzuki.reader.interactor

import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.domain.tsuzuki.model.SourceMappingAvailability
import tachiyomi.domain.tsuzuki.model.SourceTitleMapping
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository

class DeleteCanonicalTrackerBindingTest {

    @Test
    fun `deleting canonical tracker removes it from every materialized representation only`() = runTest {
        val mappings = FakeMappings(
            listOf(
                mapping("one", "title-1", 11L),
                mapping("two", "title-1", 22L),
                mapping("other", "title-2", 33L),
                mapping("remote-only", "title-1", null),
            ),
        )
        val tracks = FakeTracks()

        DeleteCanonicalTrackerBinding(mappings, tracks).execute(
            canonicalTitleId = "title-1",
            trackerId = 100L,
        )

        tracks.deleted shouldContainExactly listOf(
            11L to 100L,
            22L to 100L,
        )
    }

    private fun mapping(
        id: String,
        canonicalTitleId: String,
        mihonMangaId: Long?,
    ) = SourceTitleMapping(
        id = id,
        canonicalTitleId = canonicalTitleId,
        mihonMangaId = mihonMangaId,
        sourceId = mihonMangaId ?: 99L,
        sourceUrl = "/$id",
        language = "en",
        matchConfidence = 1.0,
        verifiedByUser = true,
        availability = SourceMappingAvailability.AVAILABLE,
        preferredOverride = false,
        createdAt = 100L,
        updatedAt = 100L,
    )

    private class FakeMappings(
        private val mappings: List<SourceTitleMapping>,
    ) : SourceTitleMappingRepository {
        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<SourceTitleMapping> =
            mappings.filter { it.canonicalTitleId == canonicalTitleId }

        override fun getByCanonicalTitleIdAsFlow(canonicalTitleId: String): Flow<List<SourceTitleMapping>> =
            MutableStateFlow(mappings.filter { it.canonicalTitleId == canonicalTitleId })

        override suspend fun getBySource(sourceId: Long, sourceUrl: String): SourceTitleMapping? =
            mappings.firstOrNull { it.sourceId == sourceId && it.sourceUrl == sourceUrl }

        override suspend fun upsert(mapping: SourceTitleMapping) = Unit

        override suspend fun setPreferredForTitle(canonicalTitleId: String, mappingId: String?, updatedAt: Long) = Unit
    }

    private class FakeTracks : TrackRepository {
        val deleted = mutableListOf<Pair<Long, Long>>()

        override suspend fun getTrackById(id: Long): Track? = null
        override suspend fun getTracksByMangaId(mangaId: Long): List<Track> = emptyList()
        override fun getTracksAsFlow(): Flow<List<Track>> = MutableStateFlow(emptyList())
        override fun getTracksByMangaIdAsFlow(mangaId: Long): Flow<List<Track>> = MutableStateFlow(emptyList())

        override suspend fun delete(mangaId: Long, trackerId: Long) {
            deleted += mangaId to trackerId
        }

        override suspend fun insert(track: Track) = Unit
        override suspend fun insertAll(tracks: List<Track>) = Unit
    }
}
