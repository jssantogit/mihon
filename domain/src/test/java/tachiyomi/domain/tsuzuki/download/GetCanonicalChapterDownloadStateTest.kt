package tachiyomi.domain.tsuzuki.download

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.download.interactor.GetCanonicalChapterDownloadState
import tachiyomi.domain.tsuzuki.download.service.CanonicalDownloadGateway

class GetCanonicalChapterDownloadStateTest {

    @Test
    fun `canonical chapter aggregates downloads from source specific variants`() = runTest {
        val variants = listOf(
            variant("variant-a", mappingId = "mapping-a", sourceId = 10L),
            variant("variant-b", mappingId = "mapping-b", sourceId = 20L),
        )
        val repository = FakeCanonicalChapterRepository(variants)
        val gateway = FakeCanonicalDownloadGateway(downloaded = setOf("variant-b"))
        val interactor = GetCanonicalChapterDownloadState(repository, gateway)

        val state = interactor.execute("chapter-1")

        state.canonicalChapterId shouldBe "chapter-1"
        state.hasDownload shouldBe true
        state.downloadedVariants.map { it.id } shouldContainExactly listOf("variant-b")
        state.downloadedVariants.single().sourceMappingId shouldBe "mapping-b"
    }

    @Test
    fun `no downloaded variant leaves canonical chapter available without a download`() = runTest {
        val variants = listOf(
            variant("variant-a", mappingId = "mapping-a", sourceId = 10L),
            variant("variant-b", mappingId = "mapping-b", sourceId = 20L),
        )
        val interactor = GetCanonicalChapterDownloadState(
            FakeCanonicalChapterRepository(variants),
            FakeCanonicalDownloadGateway(downloaded = emptySet()),
        )

        val state = interactor.execute("chapter-1")

        state.hasDownload shouldBe false
        state.downloadedVariants shouldBe emptyList()
    }

    @Test
    fun `downloaded variant identity stays source specific and never collapses to mihon ids`() = runTest {
        val downloaded = variant(
            id = "variant-b",
            mappingId = "mapping-b",
            sourceId = 20L,
            mihonMangaId = 200L,
            mihonChapterId = 201L,
        )
        val state = GetCanonicalChapterDownloadState(
            FakeCanonicalChapterRepository(listOf(downloaded)),
            FakeCanonicalDownloadGateway(downloaded = setOf("variant-b")),
        ).execute("chapter-1")

        state.downloadedVariants.single() shouldBe downloaded
        state.canonicalChapterId shouldBe "chapter-1"
    }

    private fun variant(
        id: String,
        mappingId: String,
        sourceId: Long,
        mihonMangaId: Long? = null,
        mihonChapterId: Long? = null,
    ) = ChapterVariant(
        id = id,
        canonicalChapterId = "chapter-1",
        sourceMappingId = mappingId,
        sourceId = sourceId,
        mihonMangaId = mihonMangaId,
        mihonChapterId = mihonChapterId,
        sourceChapterId = "/$id",
        sourceChapterUrl = "/$id",
        language = "en",
        rawName = "Chapter 1",
    )

    private class FakeCanonicalDownloadGateway(
        private val downloaded: Set<String>,
    ) : CanonicalDownloadGateway {
        override suspend fun isDownloaded(variant: ChapterVariant): Boolean = variant.id in downloaded
    }

    private class FakeCanonicalChapterRepository(
        private val variants: List<ChapterVariant>,
    ) : CanonicalChapterRepository {
        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<CanonicalChapter> = emptyList()

        override fun observeByCanonicalTitleId(canonicalTitleId: String): Flow<List<CanonicalChapter>> =
            MutableStateFlow(emptyList())

        override suspend fun getById(id: String): CanonicalChapter? = null

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
}
