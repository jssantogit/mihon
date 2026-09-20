package tachiyomi.domain.tsuzuki.library.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import tachiyomi.domain.tsuzuki.library.model.LibraryTitle
import tachiyomi.domain.tsuzuki.repository.CanonicalLibraryRepository
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository

@Inject
class ObserveCanonicalLibrary(
    private val canonicalLibraryRepository: CanonicalLibraryRepository,
    private val sourceTitleMappingRepository: SourceTitleMappingRepository,
) {

    fun subscribe(): Flow<List<LibraryTitle>> {
        return combine(
            canonicalLibraryRepository.getAllItemsAsFlow(),
            sourceTitleMappingRepository.getAllAsFlow(),
        ) { items, mappings ->
            val mappingsByTitle = mappings.groupBy { it.canonicalTitleId }
            items.map { item ->
                item.copy(sources = mappingsByTitle[item.id].orEmpty())
            }
        }
    }
}
