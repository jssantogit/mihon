package tachiyomi.domain.tsuzuki.reader

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.interactor.SelectChapterVariant
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.model.SourceMappingAvailability
import tachiyomi.domain.tsuzuki.model.SourceTitleMapping
import tachiyomi.domain.tsuzuki.reader.interactor.PrepareCanonicalChapterForReader
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterHistory
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterHistoryUpdate
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress
import tachiyomi.domain.tsuzuki.reader.model.CanonicalReaderPreference
import tachiyomi.domain.tsuzuki.reader.model.CanonicalReaderPreparation
import tachiyomi.domain.tsuzuki.reader.model.OperationalReaderChapter
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReaderPreferenceRepository
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReadingRepository
import tachiyomi.domain.tsuzuki.reader.service.CanonicalReaderGateway
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository
import tachiyomi.domain.tsuzuki.source.interactor.GetPreferredReadingSources
import tachiyomi.domain.tsuzuki.source.model.ReadingSourcePreference
import tachiyomi.domain.tsuzuki.source.repository.ReadingSourcePreferenceRepository

class PrepareCanonicalChapterForReaderTest {

    @Test
    fun `preferred variant prepares reader with canonical progress`() = runTest {
        val fixture = fixture(
            variants = listOf(variant("preferred", "mapping-1", 1L)),
            preferredMapping = "mapping-1",
        )
        fixture.reading.progress = CanonicalChapterProgress(
            canonicalChapterId = "chapter-1",
            read = false,
            lastPageRead = 5L,
            updatedAt = 100L,
        )

        val result = fixture.prepare.execute("chapter-1", preferredLanguage = "en")

        result.shouldBeInstanceOf<CanonicalReaderPreparation.Ready>()
        result.usedFallback shouldBe false
        result.target.variantId shouldBe "preferred"
        fixture.gateway.lastProgress shouldBe fixture.reading.progress
    }

    @Test
    fun `missing preferred variant requires explicit fallback without materializing`() = runTest {
        val fixture = fixture(
            variants = listOf(variant("fallback", "mapping-2", 2L)),
            preferredMapping = "mapping-1",
        )

        val result = fixture.prepare.execute("chapter-1", preferredLanguage = "en")

        result.shouldBeInstanceOf<CanonicalReaderPreparation.FallbackRequired>()
        result.canonicalTitleId shouldBe "title-1"
        result.preferredSourceMappingId shouldBe "mapping-1"
        result.fallbackVariant.id shouldBe "fallback"
        fixture.gateway.calls shouldBe 0
    }

    @Test
    fun `read once bypasses fallback prompt without changing persistent preference`() = runTest {
        val fixture = fixture(
            variants = listOf(variant("fallback", "mapping-2", 2L)),
            preferredMapping = "mapping-1",
        )

        val result = fixture.prepare.execute(
            canonicalChapterId = "chapter-1",
            preferredLanguage = "en",
            allowFallbackOnce = true,
        )

        result.shouldBeInstanceOf<CanonicalReaderPreparation.Ready>()
        result.usedFallback shouldBe true
        fixture.gateway.calls shouldBe 1
        fixture.preferences.get("title-1") shouldBe null
    }

    @Test
    fun `automatic fallback preference bypasses repeated prompt`() = runTest {
        val fixture = fixture(
            variants = listOf(variant("fallback", "mapping-2", 2L)),
            preferredMapping = "mapping-1",
        )
        fixture.preferences.preference = CanonicalReaderPreference(
            canonicalTitleId = "title-1",
            automaticFallback = true,
            updatedAt = 100L,
        )

        val result = fixture.prepare.execute("chapter-1", preferredLanguage = "en")

        result.shouldBeInstanceOf<CanonicalReaderPreparation.Ready>()
        result.usedFallback shouldBe true
        fixture.gateway.calls shouldBe 1
    }

    @Test
    fun `no variant returns unavailable without touching operational reader`() = runTest {
        val fixture = fixture(
            variants = emptyList(),
            preferredMapping = "mapping-1",
        )

        fixture.prepare.execute("chapter-1", preferredLanguage = "en")
            .shouldBeInstanceOf<CanonicalReaderPreparation.Unavailable>()
        fixture.gateway.calls shouldBe 0
    }

    private fun fixture(
        variants: List<ChapterVariant>,
        preferredMapping: String,
    ): Fixture {
        val chapters = FakeCanonicalChapterRepository(variants)
        val mappings = FakeSourceTitleMappingRepository(
            mapping("mapping-1", 1L, preferred = preferredMapping == "mapping-1"),
            mapping("mapping-2", 2L, preferred = preferredMapping == "mapping-2"),
        )
        val selector = SelectChapterVariant(
            canonicalChapterRepository = chapters,
            sourceTitleMappingRepository = mappings,
            getPreferredReadingSources = GetPreferredReadingSources(FakeReadingSourcePreferenceRepository()),
        )
        val reading = FakeCanonicalReadingRepository()
        val preferences = FakeCanonicalReaderPreferenceRepository()
        val gateway = FakeCanonicalReaderGateway()
        val prepare = PrepareCanonicalChapterForReader(
            selectChapterVariant = selector,
            canonicalChapterRepository = chapters,
            canonicalReadingRepository = reading,
            canonicalReaderPreferenceRepository = preferences,
            canonicalReaderGateway = gateway,
        )
        return Fixture(prepare, reading, preferences, gateway)
    }

    private data class Fixture(
        val prepare: PrepareCanonicalChapterForReader,
        val reading: FakeCanonicalReadingRepository,
        val preferences: FakeCanonicalReaderPreferenceRepository,
        val gateway: FakeCanonicalReaderGateway,
    )

    private fun variant(
        id: String,
        mappingId: String,
        sourceId: Long,
    ) = ChapterVariant(
        id = id,
        canonicalChapterId = "chapter-1",
        sourceMappingId = mappingId,
        sourceId = sourceId,
        sourceChapterId = "/$id",
        sourceChapterUrl = "/$id",
        language = "en",
        rawName = "Chapter 1",
    )

    private fun mapping(
        id: String,
        sourceId: Long,
        preferred: Boolean,
    ) = SourceTitleMapping(
        id = id,
        canonicalTitleId = "title-1",
        mihonMangaId = 10L + sourceId,
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

    private class FakeCanonicalChapterRepository(
        private val variants: List<ChapterVariant>,
    ) : CanonicalChapterRepository {
        private val chapter = CanonicalChapter(
            id = "chapter-1",
            canonicalTitleId = "title-1",
            displayNumber = "1",
            type = CanonicalChapterType.REGULAR,
            baseNumber = 1,
            confidence = 1.0,
            createdAt = 100L,
            updatedAt = 100L,
        )

        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<CanonicalChapter> =
            listOf(chapter).filter { it.canonicalTitleId == canonicalTitleId }

        override fun observeByCanonicalTitleId(canonicalTitleId: String): Flow<List<CanonicalChapter>> =
            MutableStateFlow(listOf(chapter))

        override suspend fun getById(id: String): CanonicalChapter? = chapter.takeIf { it.id == id }

        override suspend fun getVariantBySourceIdentity(
            sourceId: Long,
            sourceChapterId: String,
        ): ChapterVariant? = variants.firstOrNull {
            it.sourceId == sourceId && it.sourceChapterId == sourceChapterId
        }

        override suspend fun getVariantsByCanonicalChapterId(canonicalChapterId: String): List<ChapterVariant> =
            variants.filter { it.canonicalChapterId == canonicalChapterId }

        override suspend fun getVariantsBySourceMappingId(sourceMappingId: String): List<ChapterVariant> =
            variants.filter { it.sourceMappingId == sourceMappingId }

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

    private class FakeReadingSourcePreferenceRepository : ReadingSourcePreferenceRepository {
        override suspend fun getForLanguage(language: String): List<ReadingSourcePreference> = emptyList()
        override fun observeForLanguage(language: String): Flow<List<ReadingSourcePreference>> =
            MutableStateFlow(emptyList())

        override suspend fun getConfiguredLanguages(): List<String> = emptyList()
        override suspend fun replaceForLanguage(language: String, orderedSourceIds: List<Long>) = Unit
    }

    private class FakeCanonicalReadingRepository : CanonicalReadingRepository {
        var progress: CanonicalChapterProgress? = null

        override suspend fun getProgress(canonicalChapterId: String): CanonicalChapterProgress? = progress
        override fun observeProgress(canonicalChapterId: String): Flow<CanonicalChapterProgress?> =
            MutableStateFlow(progress)

        override suspend fun getProgressByCanonicalTitleId(
            canonicalTitleId: String,
        ): List<CanonicalChapterProgress> = listOfNotNull(progress)

        override suspend fun upsertProgress(progress: CanonicalChapterProgress) {
            this.progress = progress
        }

        override suspend fun getHistory(canonicalChapterId: String): CanonicalChapterHistory? = null
        override suspend fun recordHistory(update: CanonicalChapterHistoryUpdate) = Unit
        override suspend fun recordCheckpoint(
            progress: CanonicalChapterProgress,
            history: CanonicalChapterHistoryUpdate?,
        ) = Unit
    }

    private class FakeCanonicalReaderPreferenceRepository : CanonicalReaderPreferenceRepository {
        var preference: CanonicalReaderPreference? = null

        override suspend fun get(canonicalTitleId: String): CanonicalReaderPreference? =
            preference?.takeIf { it.canonicalTitleId == canonicalTitleId }

        override fun observe(canonicalTitleId: String): Flow<CanonicalReaderPreference?> =
            MutableStateFlow(preference)

        override suspend fun upsert(preference: CanonicalReaderPreference) {
            this.preference = preference
        }
    }

    private class FakeCanonicalReaderGateway : CanonicalReaderGateway {
        var calls = 0
        var lastProgress: CanonicalChapterProgress? = null

        override suspend fun materialize(
            variant: ChapterVariant,
            progress: CanonicalChapterProgress?,
        ): Result<OperationalReaderChapter> {
            calls += 1
            lastProgress = progress
            return Result.success(
                OperationalReaderChapter(
                    canonicalChapterId = variant.canonicalChapterId,
                    variantId = variant.id,
                    sourceMappingId = variant.sourceMappingId,
                    mihonMangaId = 20L,
                    mihonChapterId = 30L,
                    sourceId = variant.sourceId,
                ),
            )
        }
    }
}
