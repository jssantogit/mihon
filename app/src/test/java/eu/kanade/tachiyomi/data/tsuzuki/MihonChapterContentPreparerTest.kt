package eu.kanade.tachiyomi.data.tsuzuki

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.content.ContentDelivery
import tachiyomi.domain.tsuzuki.content.ContentOption
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress
import tachiyomi.domain.tsuzuki.reader.model.OperationalReaderChapter
import tachiyomi.domain.tsuzuki.reader.model.PreparedChapterContent
import tachiyomi.domain.tsuzuki.reader.service.CanonicalReaderGateway

class MihonChapterContentPreparerTest {

    @Test
    fun `mihon delivery becomes operational content without changing canonical chapter`() = runTest {
        val gateway = FakeCanonicalReaderGateway()
        val preparer = MihonChapterContentPreparer(gateway)
        val option = option(
            delivery = ContentDelivery.Mihon(
                sourceId = 7L,
                mangaId = 20L,
                chapterId = 30L,
            ),
        )
        val progress = CanonicalChapterProgress(
            canonicalChapterId = "chapter-1",
            lastPageRead = 4L,
            lastVariantId = option.key,
            updatedAt = 100L,
        )

        val result = preparer.prepare(option, progress).getOrThrow()

        result shouldBe PreparedChapterContent.MihonOperational(
            mangaId = 20L,
            chapterId = 30L,
            sourceId = 7L,
        )
        gateway.lastCanonicalChapterId shouldBe "chapter-1"
        gateway.lastDelivery shouldBe option.delivery
        gateway.lastProgress shouldBe progress
    }

    @Test
    fun `local delivery stays provider neutral and never touches Mihon gateway`() = runTest {
        val gateway = FakeCanonicalReaderGateway()
        val preparer = MihonChapterContentPreparer(gateway)

        preparer.prepare(
            option(ContentDelivery.LocalArchive("content://archive/chapter-1.cbz")),
            null,
        ).getOrThrow().shouldBeInstanceOf<PreparedChapterContent.LocalArchive>()

        preparer.prepare(
            option(ContentDelivery.LocalDirectory("content://directory/chapter-1")),
            null,
        ).getOrThrow().shouldBeInstanceOf<PreparedChapterContent.LocalDirectory>()

        gateway.calls shouldBe 0
    }

    private fun option(delivery: ContentDelivery) = ContentOption(
        key = "option-1",
        canonicalChapterId = "chapter-1",
        addonId = AddonId("mangadex"),
        language = "en",
        scanlationGroup = null,
        releaseDate = null,
        delivery = delivery,
    )

    private class FakeCanonicalReaderGateway : CanonicalReaderGateway {
        var calls = 0
        var lastCanonicalChapterId: String? = null
        var lastDelivery: ContentDelivery.Mihon? = null
        var lastProgress: CanonicalChapterProgress? = null

        override suspend fun materialize(
            canonicalChapterId: String,
            delivery: ContentDelivery.Mihon,
            progress: CanonicalChapterProgress?,
        ): Result<OperationalReaderChapter> {
            calls++
            lastCanonicalChapterId = canonicalChapterId
            lastDelivery = delivery
            lastProgress = progress
            return Result.success(
                OperationalReaderChapter(
                    canonicalChapterId = canonicalChapterId,
                    variantId = "option-1",
                    sourceMappingId = "",
                    mihonMangaId = delivery.mangaId,
                    mihonChapterId = delivery.chapterId,
                    sourceId = delivery.sourceId,
                ),
            )
        }
    }
}
