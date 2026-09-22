package tachiyomi.domain.tsuzuki.chapter.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import kotlin.time.Clock

/**
 * A reported count makes a slot visible immediately; canonical storage is
 * created only when the reader/download action actually needs a chapter ID.
 */
class MaterializeInferredChapter internal constructor(
    private val repository: CanonicalChapterRepository,
    private val gate: ChapterMutationGate,
    private val clock: () -> Long,
) {

    @Inject
    constructor(
        repository: CanonicalChapterRepository,
        gate: ChapterMutationGate,
    ) : this(repository, gate, { Clock.System.now().toEpochMilliseconds() })

    suspend fun execute(slot: CanonicalChapter): CanonicalChapter {
        require(isInferredChapter(slot)) { "Only count-derived slots can be materialized" }
        return gate.withLock {
            repository.getByCanonicalTitleId(slot.canonicalTitleId)
                .firstOrNull { it.identity == slot.identity }
                ?: run {
                    val now = clock()
                    slot.copy(createdAt = now, updatedAt = now).also { repository.upsert(it) }
                }
        }
    }
}
