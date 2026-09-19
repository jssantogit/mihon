package tachiyomi.domain.tsuzuki.library.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.repository.CanonicalLibraryRepository

@Inject
class RemoveCanonicalLibraryItem(
    private val canonicalLibraryRepository: CanonicalLibraryRepository,
) {

    suspend fun execute(canonicalTitleId: String) {
        canonicalLibraryRepository.remove(canonicalTitleId)
    }
}
