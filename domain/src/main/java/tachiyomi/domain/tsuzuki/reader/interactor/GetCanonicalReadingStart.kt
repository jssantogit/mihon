package tachiyomi.domain.tsuzuki.reader.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress
import tachiyomi.domain.tsuzuki.reader.model.CanonicalReadingStart
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReadingRepository

@Inject
class GetCanonicalReadingStart(
    private val canonicalChapterRepository: CanonicalChapterRepository,
    private val canonicalReadingRepository: CanonicalReadingRepository,
) {

    suspend fun execute(canonicalTitleId: String): CanonicalReadingStart {
        val chapters = canonicalChapterRepository.getByCanonicalTitleId(canonicalTitleId)
        if (chapters.isEmpty()) return CanonicalReadingStart.Unavailable(canonicalTitleId)

        val progressByChapter = canonicalReadingRepository
            .getProgressByCanonicalTitleId(canonicalTitleId)
            .associateBy { it.canonicalChapterId }

        val active = chapters
            .mapNotNull { chapter ->
                progressByChapter[chapter.id]
                    ?.takeIf { !it.read }
                    ?.let { progress -> chapter to progress }
            }
            .maxWithOrNull(
                compareBy<Pair<CanonicalChapter, CanonicalChapterProgress>> { it.second.updatedAt }
                    .thenBy { it.first.sortKey },
            )
            ?.first

        val target = active
            ?: chapters.firstOrNull { progressByChapter[it.id]?.read != true }
            ?: chapters.last()

        return CanonicalReadingStart.Ready(target.id)
    }
}
