package tachiyomi.domain.tsuzuki.reader

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.model.SourceMappingAvailability
import tachiyomi.domain.tsuzuki.model.SourceTitleMapping
import tachiyomi.domain.tsuzuki.reader.interactor.GetCanonicalTrackerProgress
import tachiyomi.domain.tsuzuki.reader.interactor.ResolveCanonicalTrackerBindings
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository

class CanonicalTrackerProgressTest {

    @Test
    fun `only whole regular canonical chapters advance tracker progress`() = runTest {
        val repository = FakeCanonicalChapterRepository(
            listOf(
                chapter("regular", CanonicalChapterType.REGULAR, 12),
                chapter("decimal", CanonicalChapterType.REGULAR, 12, part = 5),
                chapter("suffix", CanonicalChapterType.REGULAR, 12, alphaSuffix = "a"),
                chapter("extra", CanonicalChapterType.EXTRA, 12),
                chapter("special", CanonicalChapterType.SPECIAL, 12),
            ),
        )
        val interactor = GetCanonicalTrackerProgress(repository)

        interactor.execute("regular")?.chapterNumber shouldBe 12.0
        interactor.execute("decimal") shouldBe null
        interactor.execute("suffix") shouldBe null
        interactor.execute("extra") shouldBe null
        interactor.execute("special") shouldBe null
    }

    @Test
    fun `tracker bindings are title owned and deduplicated across source mappings`() = runTest {
        val mappings = FakeSourceTitleMappingRepository(
            mapping("fallback", 2L, 22L, preferred = false),
            mapping("preferred", 1L, 11L, preferred = true),
        )
        val tracks = FakeTrackRepository(
            mapOf(
                11L to listOf(
                    track(id = 1L, mangaId = 11L, trackerId = 100L, remoteId = 1000L),
                    track(id = 2L, mangaId = 11L, trackerId = 200L, remoteId = 2000L),
                ),
                22L to listOf(
                    track(id = 3L, mangaId = 22L, trackerId = 100L, remoteId = 9999L),
                    track(id = 4L, mangaId = 22L, trackerId = 300L, remoteId = 3000L),
                ),
            ),
        )

        val resolved = ResolveCanonicalTrackerBindings(mappings, tracks).execute("title-1")

        resolved.map { it.trackerId } shouldContainExactly listOf(100L, 200L, 300L)
        resolved.first { it.trackerId == 100L }.mangaId shouldBe 11L
        resolved.first { it.trackerId == 100L }.remoteId shouldBe 1000L
    }

    private fun chapter(
        id: String,
        type: CanonicalChapterType,
        baseNumber: Int?,
        part: Int? = null,
        alphaSuffix: String? = null,
    ) = CanonicalChapter(
        id = id,
        canonicalTitleId = "title-1",
        displayNumber = id,
        type = type,
        baseNumber = baseNumber,
        part = part,
        alphaSuffix = alphaSuffix,
        confidence = 1.0,
    )

    private fun mapping(
        id: String,
        sourceId: Long,
        mihonMangaId: Long,
        preferred: Boolean,
    ) = SourceTitleMapping(
        id = id,
        canonicalTitleId = "title-1",
        mihonMangaId = mihonMangaId,
        sourceId = sourceId,
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
        title = "Title",
        lastChapterRead = 0.0,
        totalChapters = 0L,
        status = 0L,
        score = 0.0,
        remoteUrl = "",
        startDate = 0L,
        finishDate = 0L,
        private = false,
    )

    private class FakeCanonicalChapterRepository(
        private val chapters: List<CanonicalChapter>,
    ) : CanonicalChapterRepository {
        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<CanonicalChapter> =
            chapters.filter { it.canonicalTitleId == canonicalTitleId }

        override fun observeByCanonicalTitleId(canonicalTitleId: String): Flow<List<CanonicalChapter>> =
            MutableStateFlow(chapters.filter { it.canonicalTitleId == canonicalTitleId })

        override suspend fun getById(id: String): CanonicalChapter? = chapters.firstOrNull { it.id == id }

        override suspend fun getVariantBySourceIdentity(
            sourceId: Long,
            sourceChapterId: String,
        ): ChapterVariant? = null

        override suspend fun getVariantsByCanonicalChapterId(canonicalChapterId: String): List<ChapterVariant> =
            emptyList()

        override suspend fun getVariantsBySourceMappingId(sourceMappingId: String): List<ChapterVariant> =
            emptyList()

        override suspend fun upsert(chapter: CanonicalChapter) = Unit
        override suspend fun upsertVariant(variant: ChapterVariant) = Unit
        override suspend fun upsertBatch(
            chapters: List<CanonicalChapter>,
            variants: List<ChapterVariant>,
        ) = Unit
    }

    private class FakeSourceTitleMappingRepository(
        private vararg val mappings: SourceTitleMapping,
    ) : SourceTitleMappingRepository {
        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<SourceTitleMapping> =
            mappings.filter { it.canonicalTitleId == canonicalTitleId }

        override fun getByCanonicalTitleIdAsFlow(canonicalTitleId: String): Flow<List<SourceTitleMapping>> =
            MutableStateFlow(mappings.filter { it.canonicalTitleId == canonicalTitleId })

        override suspend fun getBySource(sourceId: Long, sourceUrl: String): SourceTitleMapping? =
            mappings.firstOrNull { it.sourceId == sourceId && it.sourceUrl == sourceUrl }

        override suspend fun upsert(mapping: SourceTitleMapping) = Unit
        override suspend fun setPreferredForTitle(
            canonicalTitleId: String,
            mappingId: String?,
            updatedAt: Long,
        ) = Unit
    }

    private class FakeTrackRepository(
        private val byManga: Map<Long, List<Track>>,
    ) : TrackRepository {
        override suspend fun getTrackById(id: Long): Track? =
            byManga.values.flatten().firstOrNull { it.id == id }

        override suspend fun getTracksByMangaId(mangaId: Long): List<Track> = byManga[mangaId].orEmpty()
        override fun getTracksAsFlow(): Flow<List<Track>> = MutableStateFlow(byManga.values.flatten())
        override fun getTracksByMangaIdAsFlow(mangaId: Long): Flow<List<Track>> =
            MutableStateFlow(byManga[mangaId].orEmpty())

        override suspend fun delete(mangaId: Long, trackerId: Long) = Unit
        override suspend fun insert(track: Track) = Unit
        override suspend fun insertAll(tracks: List<Track>) = Unit
    }
}
