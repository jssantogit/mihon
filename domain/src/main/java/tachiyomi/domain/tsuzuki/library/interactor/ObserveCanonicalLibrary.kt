package tachiyomi.domain.tsuzuki.library.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.tsuzuki.library.model.CanonicalLibraryItem
import tachiyomi.domain.tsuzuki.repository.CanonicalLibraryRepository

@Inject
class ObserveCanonicalLibrary(
    private val canonicalLibraryRepository: CanonicalLibraryRepository,
) {

    fun subscribe(): Flow<List<CanonicalLibraryItem>> {
        return canonicalLibraryRepository.getAllItemsAsFlow()
    }
}
