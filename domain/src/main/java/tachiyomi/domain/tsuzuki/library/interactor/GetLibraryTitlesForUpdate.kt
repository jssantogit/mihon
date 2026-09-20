package tachiyomi.domain.tsuzuki.library.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.first
import tachiyomi.domain.tsuzuki.library.model.LibraryTitle
import tachiyomi.domain.tsuzuki.repository.CanonicalLibraryRepository
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository

@Inject
class GetLibraryTitlesForUpdate(
    private val canonicalLibraryRepository: CanonicalLibraryRepository,
    private val sourceTitleMappingRepository: SourceTitleMappingRepository,
) {

    suspend fun await(): List<LibraryTitle> {
        return canonicalLibraryRepository.getAllItemsAsFlow()
            .first()
            .map { item ->
                LibraryTitle(
                    title = item.title,
                    entry = item.entry,
                    sources = sourceTitleMappingRepository.getByCanonicalTitleId(item.id),
                )
            }
    }
}
