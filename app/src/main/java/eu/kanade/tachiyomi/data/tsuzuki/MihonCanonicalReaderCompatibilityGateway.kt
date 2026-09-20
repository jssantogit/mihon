package eu.kanade.tachiyomi.data.tsuzuki

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.tsuzuki.reader.service.CanonicalReaderCompatibilityGateway
import java.util.Date

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class MihonCanonicalReaderCompatibilityGateway(
    private val chapterRepository: ChapterRepository,
    private val historyRepository: HistoryRepository,
) : CanonicalReaderCompatibilityGateway {

    override suspend fun projectProgress(
        mihonChapterId: Long,
        read: Boolean,
        lastPageRead: Long,
    ) {
        chapterRepository.update(
            ChapterUpdate(
                id = mihonChapterId,
                read = read,
                lastPageRead = lastPageRead,
            ),
        )
    }

    override suspend fun projectHistory(
        mihonChapterId: Long,
        readAt: Long,
        sessionReadDuration: Long,
    ) {
        historyRepository.upsertHistory(
            HistoryUpdate(
                chapterId = mihonChapterId,
                readAt = Date(readAt),
                sessionReadDuration = sessionReadDuration,
            ),
        )
    }
}
