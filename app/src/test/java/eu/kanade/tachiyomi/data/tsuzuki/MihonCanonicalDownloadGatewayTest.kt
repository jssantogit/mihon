package eu.kanade.tachiyomi.data.tsuzuki

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant

class MihonCanonicalDownloadGatewayTest {

    @Test
    fun `download lookup uses variant source metadata and local manga title`() = runTest {
        var lookup: Lookup? = null
        val gateway = MihonCanonicalDownloadGateway(
            mangaTitleProvider = { mangaId ->
                mangaId shouldBe 55L
                "Local Manga Title"
            },
            downloadLookup = { variant, mangaTitle ->
                lookup = Lookup(
                    chapterName = variant.rawName,
                    scanlator = variant.scanlationGroup,
                    chapterUrl = variant.sourceChapterUrl!!,
                    mangaTitle = mangaTitle,
                    sourceId = variant.sourceId,
                )
                true
            },
        )

        gateway.isDownloaded(variant()) shouldBe true
        lookup shouldBe Lookup(
            chapterName = "Chapter 1",
            scanlator = "Group",
            chapterUrl = "/chapter/1",
            mangaTitle = "Local Manga Title",
            sourceId = 7L,
        )
    }

    @Test
    fun `unmaterialized variant cannot claim a physical mihon download`() = runTest {
        var mangaLookupCalled = false
        var downloadLookupCalled = false
        val gateway = MihonCanonicalDownloadGateway(
            mangaTitleProvider = {
                mangaLookupCalled = true
                "Title"
            },
            downloadLookup = { _, _ ->
                downloadLookupCalled = true
                true
            },
        )

        gateway.isDownloaded(variant(mihonMangaId = null)) shouldBe false
        mangaLookupCalled shouldBe false
        downloadLookupCalled shouldBe false
    }

    @Test
    fun `variant without source chapter url cannot claim a physical mihon download`() = runTest {
        var downloadLookupCalled = false
        val gateway = MihonCanonicalDownloadGateway(
            mangaTitleProvider = { "Title" },
            downloadLookup = { _, _ ->
                downloadLookupCalled = true
                true
            },
        )

        gateway.isDownloaded(variant(sourceChapterUrl = null)) shouldBe false
        downloadLookupCalled shouldBe false
    }

    private fun variant(
        mihonMangaId: Long? = 55L,
        sourceChapterUrl: String? = "/chapter/1",
    ) = ChapterVariant(
        id = "variant-1",
        canonicalChapterId = "chapter-1",
        sourceMappingId = "mapping-1",
        sourceId = 7L,
        mihonMangaId = mihonMangaId,
        mihonChapterId = 99L,
        sourceChapterId = "/chapter/1",
        sourceChapterUrl = sourceChapterUrl,
        language = "en",
        scanlationGroup = "Group",
        rawName = "Chapter 1",
    )

    private data class Lookup(
        val chapterName: String,
        val scanlator: String?,
        val chapterUrl: String,
        val mangaTitle: String,
        val sourceId: Long,
    )
}
