package tachiyomi.domain.tsuzuki.chapter

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.interactor.CalculateChapterCoverage
import tachiyomi.domain.tsuzuki.chapter.interactor.DetectCanonicalChapterGaps
import tachiyomi.domain.tsuzuki.chapter.interactor.FindChapterFallback
import tachiyomi.domain.tsuzuki.chapter.interactor.SelectChapterVariant
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterGapCertainty
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.chapter.model.ChapterStructureUncertainty
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.model.SourceMappingAvailability
import tachiyomi.domain.tsuzuki.model.SourceTitleMapping
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository
import tachiyomi.domain.tsuzuki.source.interactor.GetPreferredReadingSources
import tachiyomi.domain.tsuzuki.source.model.ReadingSourcePreference
import tachiyomi.domain.tsuzuki.source.repository.ReadingSourcePreferenceRepository

class ChapterCoverageSelectionTest {

    @Test
    fun `coverage counts regular chapters only and reports structural uncertainty`() = runTest {
        val repository = FakeCanonicalChapterRepository(
            chapters = listOf(
                chapter("regular-1", CanonicalChapterType.REGULAR, 1, confidence = 1.0),
                chapter("regular-2", CanonicalChapterType.REGULAR, 2, confidence = 1.0),
                chapter("regular-3", CanonicalChapterType.REGULAR, 3, confidence = 0.50),
                chapter("extra-1", CanonicalChapterType.EXTRA, 1, confidence = 1.0),
                chapter("unknown-1", CanonicalChapterType.UNKNOWN, null, confidence = 0.0),
            ),
            variants = listOf(
                variant("variant-regular", "regular-1", "mapping-1", 10L, "en"),
                variant("variant-extra", "extra-1", "mapping-1", 10L, "en"),
            ),
        )

        val coverage = CalculateChapterCoverage(repository).execute("title-1", "mapping-1")

        coverage.available shouldBe 1
        coverage.total shouldBe 3
        coverage.missing shouldBe 2
        coverage.ratio shouldBe (1.0 / 3.0)
        coverage.structuralUncertainty shouldBe setOf(
            ChapterStructureUncertainty.SOURCE_DERIVED,
            ChapterStructureUncertainty.UNKNOWN_CHAPTERS,
            ChapterStructureUncertainty.LOW_CONFIDENCE,
        )
        coverage.isStructurallyCertain shouldBe false
    }

    @Test
    fun `gaps include only missing regular chapters and retain uncertainty evidence`() = runTest {
        val repository = FakeCanonicalChapterRepository(
            chapters = listOf(
                chapter("regular-1", CanonicalChapterType.REGULAR, 1, confidence = 1.0),
                chapter("regular-2", CanonicalChapterType.REGULAR, 2, confidence = 0.50),
                chapter("extra-2", CanonicalChapterType.EXTRA, 2, confidence = 1.0),
                chapter("unknown-1", CanonicalChapterType.UNKNOWN, null, confidence = 0.0),
            ),
            variants = listOf(
                variant("variant-1", "regular-1", "mapping-1", 10L, "en"),
            ),
        )

        val gaps = DetectCanonicalChapterGaps(repository).execute("title-1", "mapping-1")

        gaps.map { it.canonicalChapterId } shouldContainExactly listOf("regular-2")
        gaps.single().certainty shouldBe CanonicalChapterGapCertainty.STRUCTURAL_UNCERTAIN
        gaps.single().structuralUncertainty shouldBe setOf(
            ChapterStructureUncertainty.SOURCE_DERIVED,
            ChapterStructureUncertainty.UNKNOWN_CHAPTERS,
            ChapterStructureUncertainty.LOW_CONFIDENCE,
        )
    }

    @Test
    fun `selection prioritizes language then title preferred mapping then language source order`() = runTest {
        val repository = FakeCanonicalChapterRepository(
            chapters = listOf(chapter("chapter-1", CanonicalChapterType.REGULAR, 1)),
            variants = listOf(
                variant("ja-preferred", "chapter-1", "mapping-ja", 1L, "ja"),
                variant("en-title-preferred", "chapter-1", "mapping-en-preferred", 2L, "en"),
                variant("en-source-preferred", "chapter-1", "mapping-en-source", 3L, "en"),
            ),
        )
        val mappings = FakeSourceTitleMappingRepository(
            mapping("mapping-ja", 1L, "ja", preferred = true),
            mapping("mapping-en-preferred", 2L, "en", preferred = true),
            mapping("mapping-en-source", 3L, "en"),
        )
        val sourcePreferences = FakeReadingSourcePreferenceRepository(
            mapOf(
                "en" to listOf(
                    ReadingSourcePreference("en", 3L, 0),
                    ReadingSourcePreference("en", 2L, 1),
                ),
            ),
        )
        val selector = SelectChapterVariant(
            canonicalChapterRepository = repository,
            sourceTitleMappingRepository = mappings,
            getPreferredReadingSources = GetPreferredReadingSources(sourcePreferences),
        )

        val selection = selector.execute("chapter-1", preferredLanguage = "en")

        selection.selected?.id shouldBe "en-title-preferred"
        selection.usedPreferredLanguage shouldBe true
        selection.usedPreferredMapping shouldBe true
        selection.candidates.map { it.id } shouldContainExactly listOf(
            "en-title-preferred",
            "en-source-preferred",
            "ja-preferred",
        )
    }

    @Test
    fun `source preference applies when no title override wins and unavailable mappings are excluded`() = runTest {
        val repository = FakeCanonicalChapterRepository(
            chapters = listOf(chapter("chapter-1", CanonicalChapterType.REGULAR, 1)),
            variants = listOf(
                variant("source-second", "chapter-1", "mapping-2", 2L, "en"),
                variant("source-first", "chapter-1", "mapping-3", 3L, "en"),
                variant("unavailable", "chapter-1", "mapping-4", 4L, "en"),
            ),
        )
        val mappings = FakeSourceTitleMappingRepository(
            mapping("mapping-2", 2L, "en"),
            mapping("mapping-3", 3L, "en"),
            mapping("mapping-4", 4L, "en", availability = SourceMappingAvailability.UNAVAILABLE),
        )
        val sourcePreferences = FakeReadingSourcePreferenceRepository(
            mapOf(
                "en" to listOf(
                    ReadingSourcePreference("en", 3L, 0),
                    ReadingSourcePreference("en", 2L, 1),
                    ReadingSourcePreference("en", 4L, 2),
                ),
            ),
        )
        val selector = SelectChapterVariant(
            repository,
            mappings,
            GetPreferredReadingSources(sourcePreferences),
        )

        val selection = selector.execute("chapter-1", preferredLanguage = "en")

        selection.selected?.id shouldBe "source-first"
        selection.candidates.map { it.id } shouldContainExactly listOf("source-first", "source-second")
    }

    @Test
    fun `verified release date and stable id provide deterministic tie breakers`() = runTest {
        val repository = FakeCanonicalChapterRepository(
            chapters = listOf(chapter("chapter-1", CanonicalChapterType.REGULAR, 1)),
            variants = listOf(
                variant("unverified-newer", "chapter-1", "mapping-1", 1L, "en", releaseDate = 300L),
                variant("verified-older", "chapter-1", "mapping-2", 2L, "en", releaseDate = 100L),
                variant("b-stable", "chapter-1", "mapping-3", 3L, "en", releaseDate = 200L),
                variant("a-stable", "chapter-1", "mapping-4", 4L, "en", releaseDate = 200L),
            ),
        )
        val mappings = FakeSourceTitleMappingRepository(
            mapping("mapping-1", 1L, "en"),
            mapping("mapping-2", 2L, "en", verified = true),
            mapping("mapping-3", 3L, "en"),
            mapping("mapping-4", 4L, "en"),
        )
        val selector = SelectChapterVariant(
            repository,
            mappings,
            GetPreferredReadingSources(FakeReadingSourcePreferenceRepository()),
        )

        val selection = selector.execute("chapter-1", preferredLanguage = "en")

        selection.candidates.map { it.id } shouldContainExactly listOf(
            "verified-older",
            "unverified-newer",
            "a-stable",
            "b-stable",
        )
    }

    @Test
    fun `fallback excludes unavailable source mapping without changing title preference`() = runTest {
        val repository = FakeCanonicalChapterRepository(
            chapters = listOf(chapter("chapter-1", CanonicalChapterType.REGULAR, 1)),
            variants = listOf(
                variant("primary", "chapter-1", "mapping-1", 1L, "en"),
                variant("fallback", "chapter-1", "mapping-2", 2L, "en"),
            ),
        )
        val mappings = FakeSourceTitleMappingRepository(
            mapping("mapping-1", 1L, "en", preferred = true),
            mapping("mapping-2", 2L, "en"),
        )
        val selector = SelectChapterVariant(
            repository,
            mappings,
            GetPreferredReadingSources(FakeReadingSourcePreferenceRepository()),
        )
        val fallback = FindChapterFallback(selector)

        val selection = fallback.execute(
            canonicalChapterId = "chapter-1",
            unavailableSourceMappingId = "mapping-1",
            preferredLanguage = "en",
        )

        selection.selected?.id shouldBe "fallback"
        selection.candidates.map { it.id } shouldContainExactly listOf("fallback")
        mappings.setPreferredCalls shouldBe 0
        mappings.getByCanonicalTitleId("title-1").first { it.id == "mapping-1" }.preferredOverride shouldBe true
    }

    private fun chapter(
        id: String,
        type: CanonicalChapterType,
        baseNumber: Int?,
        confidence: Double = 1.0,
    ) = CanonicalChapter(
        id = id,
        canonicalTitleId = "title-1",
        displayNumber = baseNumber?.toString() ?: id,
        type = type,
        baseNumber = baseNumber,
        confidence = confidence,
        createdAt = 100L,
        updatedAt = 100L,
    )

    private fun variant(
        id: String,
        chapterId: String,
        mappingId: String,
        sourceId: Long,
        language: String,
        releaseDate: Long? = null,
    ) = ChapterVariant(
        id = id,
        canonicalChapterId = chapterId,
        sourceMappingId = mappingId,
        sourceId = sourceId,
        sourceChapterId = "/$id",
        sourceChapterUrl = "/$id",
        language = language,
        releaseDate = releaseDate,
        rawName = id,
        createdAt = 100L,
        updatedAt = 100L,
    )

    private fun mapping(
        id: String,
        sourceId: Long,
        language: String,
        preferred: Boolean = false,
        verified: Boolean = false,
        availability: SourceMappingAvailability = SourceMappingAvailability.AVAILABLE,
    ) = SourceTitleMapping(
        id = id,
        canonicalTitleId = "title-1",
        mihonMangaId = sourceId,
        sourceId = sourceId,
        sourceUrl = "/$id",
        language = language,
        matchConfidence = 1.0,
        verifiedByUser = verified,
        availability = availability,
        preferredOverride = preferred,
        createdAt = 100L,
        updatedAt = 100L,
    )

    private class FakeCanonicalChapterRepository(
        chapters: List<CanonicalChapter>,
        variants: List<ChapterVariant>,
    ) : CanonicalChapterRepository {
        private val chapters = chapters.associateBy { it.id }.toMutableMap()
        private val variants = variants.associateBy { it.id }.toMutableMap()

        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<CanonicalChapter> =
            chapters.values.filter { it.canonicalTitleId == canonicalTitleId }

        override fun observeByCanonicalTitleId(canonicalTitleId: String): Flow<List<CanonicalChapter>> =
            MutableStateFlow(chapters.values.filter { it.canonicalTitleId == canonicalTitleId })

        override suspend fun getById(id: String): CanonicalChapter? = chapters[id]

        override suspend fun getVariantBySourceIdentity(
            sourceId: Long,
            sourceChapterId: String,
        ): ChapterVariant? = variants.values.firstOrNull {
            it.sourceId == sourceId && it.sourceChapterId == sourceChapterId
        }

        override suspend fun getVariantsByCanonicalChapterId(canonicalChapterId: String): List<ChapterVariant> =
            variants.values.filter { it.canonicalChapterId == canonicalChapterId }

        override suspend fun getVariantsBySourceMappingId(sourceMappingId: String): List<ChapterVariant> =
            variants.values.filter { it.sourceMappingId == sourceMappingId }

        override suspend fun upsert(chapter: CanonicalChapter) {
            chapters[chapter.id] = chapter
        }

        override suspend fun upsertVariant(variant: ChapterVariant) {
            variants[variant.id] = variant
        }

        override suspend fun upsertBatch(
            chapters: List<CanonicalChapter>,
            variants: List<ChapterVariant>,
        ) {
            chapters.forEach { upsert(it) }
            variants.forEach { upsertVariant(it) }
        }
    }

    private class FakeSourceTitleMappingRepository(
        vararg initial: SourceTitleMapping,
    ) : SourceTitleMappingRepository {
        private val mappings = initial.associateBy { it.id }.toMutableMap()
        var setPreferredCalls = 0

        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<SourceTitleMapping> =
            mappings.values.filter { it.canonicalTitleId == canonicalTitleId }

        override fun getByCanonicalTitleIdAsFlow(canonicalTitleId: String): Flow<List<SourceTitleMapping>> =
            MutableStateFlow(mappings.values.filter { it.canonicalTitleId == canonicalTitleId })

        override suspend fun getBySource(sourceId: Long, sourceUrl: String): SourceTitleMapping? =
            mappings.values.firstOrNull { it.sourceId == sourceId && it.sourceUrl == sourceUrl }

        override suspend fun upsert(mapping: SourceTitleMapping) {
            mappings[mapping.id] = mapping
        }

        override suspend fun setPreferredForTitle(
            canonicalTitleId: String,
            mappingId: String?,
            updatedAt: Long,
        ) {
            setPreferredCalls += 1
        }
    }

    private class FakeReadingSourcePreferenceRepository(
        private val preferences: Map<String, List<ReadingSourcePreference>> = emptyMap(),
    ) : ReadingSourcePreferenceRepository {

        override suspend fun getForLanguage(language: String): List<ReadingSourcePreference> =
            preferences[language].orEmpty()

        override fun observeForLanguage(language: String): Flow<List<ReadingSourcePreference>> =
            MutableStateFlow(preferences[language].orEmpty())

        override suspend fun getConfiguredLanguages(): List<String> = preferences.keys.toList()

        override suspend fun replaceForLanguage(language: String, orderedSourceIds: List<Long>) = Unit
    }
}
