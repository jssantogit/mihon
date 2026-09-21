package eu.kanade.tachiyomi.ui.reader

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.reader.model.PreparedChapterContent

class CanonicalReaderTargetPlanTest {

    @Test
    fun `local archive plan does not require Mihon coordinates`() {
        val plan = planCanonicalReaderTarget(
            canonicalChapterId = "chapter-1",
            target = PreparedChapterContent.LocalArchive("content://downloads/chapter-1.cbz"),
        ).shouldBeInstanceOf<CanonicalReaderTargetPlan.Local>()

        plan.canonicalChapterId shouldBe "chapter-1"
        plan.uri shouldBe "content://downloads/chapter-1.cbz"
        plan.format shouldBe CanonicalLocalReaderFormat.ARCHIVE
    }

    @Test
    fun `local directory plan stays provider neutral`() {
        val plan = planCanonicalReaderTarget(
            canonicalChapterId = "chapter-2",
            target = PreparedChapterContent.LocalDirectory("content://downloads/chapter-2"),
        ).shouldBeInstanceOf<CanonicalReaderTargetPlan.Local>()

        plan.uri shouldBe "content://downloads/chapter-2"
        plan.format shouldBe CanonicalLocalReaderFormat.DIRECTORY
    }

    @Test
    fun `canonical EPUB download preserves explicit format`() {
        val plan = planCanonicalReaderTarget(
            canonicalChapterId = "chapter-3",
            target = PreparedChapterContent.CanonicalDownload(
                uri = "content://downloads/chapter-3",
                format = "EPUB",
            ),
        ).shouldBeInstanceOf<CanonicalReaderTargetPlan.Local>()

        plan.format shouldBe CanonicalLocalReaderFormat.EPUB
    }
}
