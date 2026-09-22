package tachiyomi.data.tsuzuki.home

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.domain.tsuzuki.home.model.HomeContinueReadingSeed
import tachiyomi.domain.tsuzuki.home.repository.HomeContinueReadingSource

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class HomeContinueReadingSourceImpl(
    private val database: Database,
) : HomeContinueReadingSource {

    override fun observe(): Flow<List<HomeContinueReadingSeed>> =
        database.tsuzuki_chapter_progressQueries
            .observeTsuzukiContinueReadingCandidates(::mapSeed)
            .subscribeToList()

    private fun mapSeed(
        canonicalTitleId: String,
        displayTitle: String,
        canonicalChapterId: String,
        displayNumber: String,
        lastPageRead: Long,
        read: Boolean,
        updatedAt: Long,
        lastVariantId: String?,
    ) = HomeContinueReadingSeed(
        canonicalTitleId = canonicalTitleId,
        title = displayTitle,
        canonicalChapterId = canonicalChapterId,
        chapterDisplayNumber = displayNumber,
        lastPageRead = lastPageRead,
        read = read,
        updatedAt = updatedAt,
        lastVariantId = lastVariantId,
    )
}
