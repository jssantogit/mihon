package tachiyomi.data.tsuzuki.sync

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.data.Database
import tachiyomi.domain.tsuzuki.sync.model.ChapterEvidenceSyncKey
import tachiyomi.domain.tsuzuki.sync.service.ChapterSyncEvidenceRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ChapterSyncEvidenceRepositoryImpl(
    private val database: Database,
) : ChapterSyncEvidenceRepository {

    override suspend fun getStableEvidenceKey(canonicalChapterId: String): String? {
        val evidence = database.tsuzuki_chapter_evidenceQueries
            .getPortableTsuzukiChapterEvidenceKey(
                canonicalChapterId = canonicalChapterId,
                mapper = { producerKind, producerId, externalChapterKey ->
                    Triple(producerKind, producerId, externalChapterKey)
                },
            )
            .awaitAsOneOrNull()
            ?: return null

        val externalKey = evidence.third ?: return null
        return ChapterEvidenceSyncKey.from(
            producerKind = evidence.first,
            producerId = evidence.second,
            externalChapterKey = externalKey,
        )
    }
}
