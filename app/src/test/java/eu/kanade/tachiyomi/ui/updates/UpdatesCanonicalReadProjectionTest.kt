package eu.kanade.tachiyomi.ui.updates

import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.updates.model.UpdatesWithRelations

class UpdatesCanonicalReadProjectionTest {

    @Test
    fun `selected operational updates project their source identity to canonical progress`() = runTest {
        val calls = mutableListOf<Triple<Long, String, Boolean>>()
        val updates = listOf(
            update(sourceId = 7L, chapterUrl = "/chapter/1"),
            update(sourceId = 8L, chapterUrl = "/chapter/2"),
        )

        projectCanonicalReadStatusForUpdates(
            updates = updates,
            read = true,
            project = { sourceId, sourceChapterId, read ->
                calls += Triple(sourceId, sourceChapterId, read)
                true
            },
        )

        calls shouldContainExactly listOf(
            Triple(7L, "/chapter/1", true),
            Triple(8L, "/chapter/2", true),
        )
    }

    private fun update(
        sourceId: Long,
        chapterUrl: String,
    ) = UpdatesWithRelations(
        mangaId = sourceId * 10,
        mangaTitle = "Title",
        chapterId = sourceId * 100,
        chapterName = "Chapter",
        scanlator = null,
        chapterUrl = chapterUrl,
        read = false,
        bookmark = false,
        lastPageRead = 0L,
        sourceId = sourceId,
        dateFetch = 100L,
        coverData = MangaCover(
            mangaId = sourceId * 10,
            sourceId = sourceId,
            isMangaFavorite = true,
            url = null,
            lastModified = 0L,
        ),
    )
}
