package eu.kanade.tachiyomi.data.library

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.model.ChapterReconciliationReport

class LibraryUpdateCanonicalSequenceTest {

    @Test
    fun `operational chapters are persisted before canonical reconciliation`() = runTest {
        val events = mutableListOf<String>()

        val result = runCanonicalUpdateCycle(
            updateOperational = {
                events += "operational"
                emptyList()
            },
            refreshCanonical = {
                events += "canonical"
                Result.success(
                    ChapterReconciliationReport(
                        canonicalTitleId = "title-1",
                        createdCanonicalChapterIds = setOf("chapter-1"),
                    ),
                )
            },
        )

        events shouldContainExactly listOf("operational", "canonical")
        result.canonicalRefresh?.createdCanonicalChapterIds shouldBe setOf("chapter-1")
        result.newOperationalChapters shouldBe emptyList()
    }
}
