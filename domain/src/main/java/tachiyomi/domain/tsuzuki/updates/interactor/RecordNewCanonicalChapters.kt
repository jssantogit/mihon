package tachiyomi.domain.tsuzuki.updates.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.repository.CanonicalLibraryRepository
import tachiyomi.domain.tsuzuki.updates.repository.ChapterUpdateState
import tachiyomi.domain.tsuzuki.updates.repository.ChapterUpdateStateRepository
import kotlin.time.Clock

class RecordNewCanonicalChapters internal constructor(
    private val canonicalChapterRepository: CanonicalChapterRepository,
    private val canonicalLibraryRepository: CanonicalLibraryRepository,
    private val chapterUpdateStateRepository: ChapterUpdateStateRepository,
    private val clock: () -> Long,
) {

    @Inject
    constructor(
        canonicalChapterRepository: CanonicalChapterRepository,
        canonicalLibraryRepository: CanonicalLibraryRepository,
        chapterUpdateStateRepository: ChapterUpdateStateRepository,
    ) : this(
        canonicalChapterRepository = canonicalChapterRepository,
        canonicalLibraryRepository = canonicalLibraryRepository,
        chapterUpdateStateRepository = chapterUpdateStateRepository,
        clock = { Clock.System.now().toEpochMilliseconds() },
    )

    suspend fun execute(canonicalTitleId: String): List<ChapterUpdateState> {
        if (canonicalLibraryRepository.get(canonicalTitleId) == null) return emptyList()

        val chapters = canonicalChapterRepository.getByCanonicalTitleId(canonicalTitleId)
        if (chapters.isEmpty()) return emptyList()

        val observedIds = chapterUpdateStateRepository
            .getByCanonicalTitleId(canonicalTitleId)
            .asSequence()
            .map { it.canonicalChapterId }
            .toHashSet()

        val firstSeenAt = clock()
        return chapters
            .filterNot { it.id in observedIds }
            .map { chapter ->
                ChapterUpdateState(
                    canonicalChapterId = chapter.id,
                    canonicalTitleId = canonicalTitleId,
                    firstSeenAt = firstSeenAt,
                    acknowledgedAt = null,
                ).also { chapterUpdateStateRepository.upsert(it) }
            }
    }
}
