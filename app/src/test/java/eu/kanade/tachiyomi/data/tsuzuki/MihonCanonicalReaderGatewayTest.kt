package eu.kanade.tachiyomi.data.tsuzuki

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.model.SourceMappingAvailability
import tachiyomi.domain.tsuzuki.model.SourceTitleMapping
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository

class MihonCanonicalReaderGatewayTest {

    @Test
    fun `materialize creates one operational chapter and projects canonical progress`() = runTest {
        val chapters = FakeChapterRepository()
        val canonical = FakeCanonicalChapterRepository()
        val mappings = FakeSourceTitleMappingRepository(mapping())
        val variant = variant()
        canonical.chapter = canonicalChapter()
        canonical.variant = variant

        val gateway = MihonCanonicalReaderGateway(chapters, canonical, mappings)
        val target = gateway.materialize(
            variant = variant,
            progress = CanonicalChapterProgress(
                canonicalChapterId = "canonical-chapter-1",
                read = true,
                lastPageRead = 7L,
                updatedAt = 200L,
            ),
        ).getOrThrow()

        target.canonicalChapterId shouldBe "canonical-chapter-1"
        target.variantId shouldBe "variant-1"
        target.mihonMangaId shouldBe 55L
        target.mihonChapterId shouldBe 1L
        chapters.addCalls shouldBe 1

        chapters.getChapterById(1L)!!.let { chapter ->
            chapter.mangaId shouldBe 55L
            chapter.url shouldBe "/chapter/1"
            chapter.name shouldBe "Chapter 1"
            chapter.read shouldBe true
            chapter.lastPageRead shouldBe 7L
            chapter.scanlator shouldBe "Group"
            chapter.chapterNumber shouldBe 1.0
            chapter.sourceOrder shouldBe 4L
            chapter.memo["nested"] shouldBe JsonPrimitive("raw")
        }

        canonical.variant!!.mihonMangaId shouldBe 55L
        canonical.variant!!.mihonChapterId shouldBe 1L
    }

    @Test
    fun `materialize reuses operational chapter by source url and refreshes projected progress`() = runTest {
        val chapters = FakeChapterRepository().apply {
            rows[9L] = Chapter.create().copy(
                id = 9L,
                mangaId = 55L,
                url = "/chapter/1",
                name = "Chapter 1",
                read = false,
                lastPageRead = 2L,
            )
        }
        val canonical = FakeCanonicalChapterRepository().apply {
            chapter = canonicalChapter()
            variant = variant(mihonChapterId = 999L)
        }
        val mappings = FakeSourceTitleMappingRepository(mapping())
        val gateway = MihonCanonicalReaderGateway(chapters, canonical, mappings)

        val target = gateway.materialize(
            canonical.variant!!,
            CanonicalChapterProgress(
                canonicalChapterId = "canonical-chapter-1",
                read = true,
                lastPageRead = 8L,
                updatedAt = 300L,
            ),
        ).getOrThrow()

        target.mihonChapterId shouldBe 9L
        chapters.addCalls shouldBe 0
        chapters.rows.getValue(9L).read shouldBe true
        chapters.rows.getValue(9L).lastPageRead shouldBe 8L
        canonical.variant!!.mihonChapterId shouldBe 9L
    }

    @Test
    fun `materialize fails closed on invalid mapping or missing materialization`() = runTest {
        val chapters = FakeChapterRepository()
        val canonical = FakeCanonicalChapterRepository().apply {
            chapter = canonicalChapter()
            variant = variant()
        }

        MihonCanonicalReaderGateway(
            chapters,
            canonical,
            FakeSourceTitleMappingRepository(mapping(sourceId = 999L)),
        ).materialize(canonical.variant!!, null).isFailure shouldBe true
        chapters.addCalls shouldBe 0

        MihonCanonicalReaderGateway(
            chapters,
            canonical,
            FakeSourceTitleMappingRepository(mapping(mihonMangaId = null)),
        ).materialize(canonical.variant!!, null).isFailure shouldBe true
        chapters.addCalls shouldBe 0
    }

    @Test
    fun `materialize rejects progress belonging to another canonical chapter`() = runTest {
        val chapters = FakeChapterRepository()
        val canonical = FakeCanonicalChapterRepository().apply {
            chapter = canonicalChapter()
            variant = variant()
        }
        val gateway = MihonCanonicalReaderGateway(
            chapters,
            canonical,
            FakeSourceTitleMappingRepository(mapping()),
        )

        gateway.materialize(
            canonical.variant!!,
            CanonicalChapterProgress(canonicalChapterId = "other"),
        ).isFailure shouldBe true
        chapters.addCalls shouldBe 0
    }

    @Test
    fun `cancellation from operational persistence propagates`() = runTest {
        val chapters = FakeChapterRepository().apply {
            addError = CancellationException("cancelled")
        }
        val canonical = FakeCanonicalChapterRepository().apply {
            chapter = canonicalChapter()
            variant = variant()
        }
        val gateway = MihonCanonicalReaderGateway(
            chapters,
            canonical,
            FakeSourceTitleMappingRepository(mapping()),
        )

        shouldThrow<CancellationException> {
            gateway.materialize(canonical.variant!!, null)
        }
    }

    private fun canonicalChapter() = CanonicalChapter(
        id = "canonical-chapter-1",
        canonicalTitleId = "title-1",
        displayNumber = "1",
        type = CanonicalChapterType.REGULAR,
        baseNumber = 1,
        confidence = 1.0,
        createdAt = 100L,
        updatedAt = 100L,
    )

    private fun variant(mihonChapterId: Long? = null) = ChapterVariant(
        id = "variant-1",
        canonicalChapterId = "canonical-chapter-1",
        sourceMappingId = "mapping-1",
        sourceId = 7L,
        mihonMangaId = null,
        mihonChapterId = mihonChapterId,
        sourceChapterId = "/chapter/1",
        sourceChapterUrl = "/chapter/1",
        language = "en",
        scanlationGroup = "Group",
        version = 2L,
        releaseDate = 123L,
        rawName = "Chapter 1",
        rawNumberHint = 1.0,
        rawSourceOrder = 4L,
        rawSourceMetadata = buildJsonObject {
            put("nested", JsonPrimitive("raw"))
        },
        createdAt = 100L,
        updatedAt = 100L,
    )

    private fun mapping(
        sourceId: Long = 7L,
        mihonMangaId: Long? = 55L,
    ) = SourceTitleMapping(
        id = "mapping-1",
        canonicalTitleId = "title-1",
        mihonMangaId = mihonMangaId,
        sourceId = sourceId,
        sourceUrl = "/title",
        language = "en",
        matchConfidence = 1.0,
        verifiedByUser = true,
        availability = SourceMappingAvailability.AVAILABLE,
        preferredOverride = true,
        createdAt = 100L,
        updatedAt = 100L,
    )

    private class FakeChapterRepository : ChapterRepository {
        val rows = linkedMapOf<Long, Chapter>()
        var addCalls = 0
        var addError: Throwable? = null
        private var nextId = 1L

        override suspend fun addAll(chapters: List<Chapter>): List<Chapter> {
            addError?.let { throw it }
            addCalls += 1
            return chapters.map { chapter ->
                chapter.copy(id = nextId++).also { rows[it.id] = it }
            }
        }

        override suspend fun update(chapterUpdate: ChapterUpdate) {
            val current = rows[chapterUpdate.id] ?: return
            rows[chapterUpdate.id] = current.copy(
                read = chapterUpdate.read ?: current.read,
                lastPageRead = chapterUpdate.lastPageRead ?: current.lastPageRead,
            )
        }

        override suspend fun updateAll(chapterUpdates: List<ChapterUpdate>) {
            chapterUpdates.forEach { update(it) }
        }

        override suspend fun removeChaptersWithIds(chapterIds: List<Long>) {
            chapterIds.forEach(rows::remove)
        }

        override suspend fun getChapterByMangaId(mangaId: Long, applyScanlatorFilter: Boolean): List<Chapter> =
            rows.values.filter { it.mangaId == mangaId }

        override suspend fun getScanlatorsByMangaId(mangaId: Long): List<String> = emptyList()
        override fun getScanlatorsByMangaIdAsFlow(mangaId: Long): Flow<List<String>> = emptyFlow()
        override suspend fun getBookmarkedChaptersByMangaId(mangaId: Long): List<Chapter> = emptyList()
        override suspend fun getChapterById(id: Long): Chapter? = rows[id]
        override suspend fun getChapterByMangaIdAsFlow(
            mangaId: Long,
            applyScanlatorFilter: Boolean,
        ): Flow<List<Chapter>> = MutableStateFlow(rows.values.filter { it.mangaId == mangaId })

        override suspend fun getChapterByUrlAndMangaId(url: String, mangaId: Long): Chapter? =
            rows.values.firstOrNull { it.url == url && it.mangaId == mangaId }
    }

    private class FakeCanonicalChapterRepository : CanonicalChapterRepository {
        var chapter: CanonicalChapter? = null
        var variant: ChapterVariant? = null

        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<CanonicalChapter> =
            listOfNotNull(chapter).filter { it.canonicalTitleId == canonicalTitleId }

        override fun observeByCanonicalTitleId(canonicalTitleId: String): Flow<List<CanonicalChapter>> =
            MutableStateFlow(emptyList())

        override suspend fun getById(id: String): CanonicalChapter? = chapter?.takeIf { it.id == id }

        override suspend fun getVariantBySourceIdentity(
            sourceId: Long,
            sourceChapterId: String,
        ): ChapterVariant? = variant?.takeIf {
            it.sourceId == sourceId && it.sourceChapterId == sourceChapterId
        }

        override suspend fun getVariantsByCanonicalChapterId(canonicalChapterId: String): List<ChapterVariant> =
            listOfNotNull(variant).filter { it.canonicalChapterId == canonicalChapterId }

        override suspend fun getVariantsBySourceMappingId(sourceMappingId: String): List<ChapterVariant> =
            listOfNotNull(variant).filter { it.sourceMappingId == sourceMappingId }

        override suspend fun upsert(chapter: CanonicalChapter) {
            this.chapter = chapter
        }

        override suspend fun upsertVariant(variant: ChapterVariant) {
            this.variant = variant
        }

        override suspend fun upsertBatch(
            chapters: List<CanonicalChapter>,
            variants: List<ChapterVariant>,
        ) {
            chapters.lastOrNull()?.let { chapter = it }
            variants.lastOrNull()?.let { variant = it }
        }
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
}
