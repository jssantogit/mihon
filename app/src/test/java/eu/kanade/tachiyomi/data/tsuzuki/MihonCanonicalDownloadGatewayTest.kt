package eu.kanade.tachiyomi.data.tsuzuki

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.model.StubSource
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
            sourceProvider = { sourceId ->
                sourceId shouldBe 7L
                StubSource(sourceId, "en", "Removed Source")
            },
            downloadLookup = { variant, mangaTitle, source ->
                source.id shouldBe 7L
                source.name shouldBe "Removed Source"
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
        var sourceLookupCalled = false
        var downloadLookupCalled = false
        val gateway = MihonCanonicalDownloadGateway(
            mangaTitleProvider = {
                mangaLookupCalled = true
                "Title"
            },
            sourceProvider = {
                sourceLookupCalled = true
                StubSource(it, "en", "Source")
            },
            downloadLookup = { _, _, _ ->
                downloadLookupCalled = true
                true
            },
        )

        gateway.isDownloaded(variant(mihonMangaId = null)) shouldBe false
        mangaLookupCalled shouldBe false
        sourceLookupCalled shouldBe false
        downloadLookupCalled shouldBe false
    }

    @Test
    fun `variant without source chapter url cannot claim a physical mihon download`() = runTest {
        var sourceLookupCalled = false
        var downloadLookupCalled = false
        val gateway = MihonCanonicalDownloadGateway(
            mangaTitleProvider = { "Title" },
            sourceProvider = {
                sourceLookupCalled = true
                StubSource(it, "en", "Source")
            },
            downloadLookup = { _, _, _ ->
                downloadLookupCalled = true
                true
            },
        )

        gateway.isDownloaded(variant(sourceChapterUrl = null)) shouldBe false
        sourceLookupCalled shouldBe false
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
