package tachiyomi.domain.tsuzuki.updates.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReadingRepository
import kotlin.time.Clock

class ProjectCanonicalUpdateReadStatus internal constructor(
    private val canonicalChapterRepository: CanonicalChapterRepository,
    private val canonicalReadingRepository: CanonicalReadingRepository,
    private val clock: () -> Long,
) {

    @Inject
    constructor(
        canonicalChapterRepository: CanonicalChapterRepository,
        canonicalReadingRepository: CanonicalReadingRepository,
    ) : this(
        canonicalChapterRepository = canonicalChapterRepository,
        canonicalReadingRepository = canonicalReadingRepository,
        clock = { Clock.System.now().toEpochMilliseconds() },
    )

    suspend fun execute(
        sourceId: Long,
        sourceChapterId: String,
        read: Boolean,
    ): Boolean {
        val variant = canonicalChapterRepository
            .getVariantBySourceIdentity(sourceId, sourceChapterId)
            ?: return false

        val existing = canonicalReadingRepository.getProgress(variant.canonicalChapterId)
        canonicalReadingRepository.upsertProgress(
            CanonicalChapterProgress(
                canonicalChapterId = variant.canonicalChapterId,
                read = read,
                lastPageRead = if (read) existing?.lastPageRead ?: 0L else 0L,
                lastVariantId = variant.id,
                updatedAt = clock(),
            ),
        )
        return true
    }
}
