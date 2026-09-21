package tachiyomi.domain.tsuzuki.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleMergeRepository

@Inject
class MergeCanonicalTitles(
    private val repository: CanonicalTitleMergeRepository,
) {

    suspend fun execute(targetId: String, localId: String) {
        repository.convergeTo(targetId = targetId, localId = localId)
    }
}
