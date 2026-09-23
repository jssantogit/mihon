package eu.kanade.tachiyomi.data.tsuzuki

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.content.ContentDelivery
import tachiyomi.domain.tsuzuki.content.ContentOption
import tachiyomi.domain.tsuzuki.download.model.CanonicalDownloadArtifact

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
    fun `acquire reuses existing physical artifact without queueing`() = runTest {
        val option = option()
        val artifact = artifact(option)
        var starts = 0
        val gateway = MihonCanonicalDownloadGateway(
            mangaTitleProvider = { "Title" },
            sourceProvider = { StubSource(it, "en", "Source") },
            downloadLookup = { _, _, _ -> false },
            artifactLocator = { artifact },
            downloadStarter = { starts++ },
            downloadCompletion = { true },
        )

        gateway.acquire(option).getOrThrow() shouldBe artifact
        starts shouldBe 0
    }

    @Test
    fun `acquire queues operational chapter and returns artifact after completion`() = runTest {
        val option = option()
        val artifact = artifact(option)
        var locateCalls = 0
        var startedChapterId: Long? = null
        var awaitedChapterId: Long? = null
        val gateway = MihonCanonicalDownloadGateway(
            mangaTitleProvider = { "Title" },
            sourceProvider = { StubSource(it, "en", "Source") },
            downloadLookup = { _, _, _ -> false },
            artifactLocator = {
                locateCalls++
                if (locateCalls >= 2) artifact else null
            },
            downloadStarter = { startedChapterId = it },
            downloadCompletion = {
                awaitedChapterId = it
                true
            },
        )

        gateway.acquire(option).getOrThrow() shouldBe artifact
        startedChapterId shouldBe 99L
        awaitedChapterId shouldBe 99L
    }

    @Test
    fun `acquire fails when queued download does not complete`() = runTest {
        val option = option()
        val gateway = MihonCanonicalDownloadGateway(
            mangaTitleProvider = { "Title" },
            sourceProvider = { StubSource(it, "en", "Source") },
            downloadLookup = { _, _, _ -> false },
            artifactLocator = { null },
            downloadStarter = {},
            downloadCompletion = { false },
        )

        gateway.acquire(option).isFailure shouldBe true
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

    private fun option() = ContentOption(
        key = "mangadex:chapter-1",
        canonicalChapterId = "chapter-1",
        addonId = AddonId("mangadex"),
        language = "en",
        scanlationGroup = "Group",
        releaseDate = 100L,
        delivery = ContentDelivery.Mihon(
            sourceId = 7L,
            mangaId = 55L,
            chapterId = 99L,
        ),
    )

    private fun artifact(option: ContentOption) = CanonicalDownloadArtifact(
        canonicalChapterId = option.canonicalChapterId,
        localUri = "content://downloads/chapter-1.cbz",
        format = "CBZ",
        originatingAddonId = option.addonId,
        originatingOptionKey = option.key,
        completedAt = 100L,
        checksum = null,
    )

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
