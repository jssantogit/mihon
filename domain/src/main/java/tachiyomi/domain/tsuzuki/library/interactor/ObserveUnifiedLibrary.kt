package tachiyomi.domain.tsuzuki.library.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import tachiyomi.domain.tsuzuki.library.model.ExternalLibraryMembership
import tachiyomi.domain.tsuzuki.library.model.LibraryTitleCategory
import tachiyomi.domain.tsuzuki.library.model.TitleFormatObservation
import tachiyomi.domain.tsuzuki.library.model.UnifiedLibraryTitle
import tachiyomi.domain.tsuzuki.library.model.projectUnifiedLibrary
import tachiyomi.domain.tsuzuki.model.CanonicalLibraryEntry
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.SourceRepresentation
import tachiyomi.domain.tsuzuki.repository.CanonicalLibraryRepository
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import tachiyomi.domain.tsuzuki.repository.ExternalLibraryRepository
import tachiyomi.domain.tsuzuki.repository.LibraryTitleCategoryRepository
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository
import tachiyomi.domain.tsuzuki.repository.TitleFormatObservationRepository

@Inject
class ObserveUnifiedLibrary(
    private val canonicalTitleRepository: CanonicalTitleRepository,
    private val canonicalLibraryRepository: CanonicalLibraryRepository,
    private val externalLibraryRepository: ExternalLibraryRepository,
    private val sourceTitleMappingRepository: SourceTitleMappingRepository,
    private val libraryTitleCategoryRepository: LibraryTitleCategoryRepository,
    private val titleFormatObservationRepository: TitleFormatObservationRepository,
) {

    fun subscribe(): Flow<List<UnifiedLibraryTitle>> {
        val core = combine(
            canonicalTitleRepository.getAllAsFlow(),
            canonicalLibraryRepository.getAllAsFlow(),
            externalLibraryRepository.observeAll(),
            titleFormatObservationRepository.observeAll(),
        ) { titles, localEntries, externalMemberships, formatObservations ->
            CoreSnapshot(
                titles = titles,
                localEntries = localEntries,
                externalMemberships = externalMemberships,
                formatObservations = formatObservations,
            )
        }
        val decorations = combine(
            sourceTitleMappingRepository.getAllAsFlow(),
            libraryTitleCategoryRepository.getAllAsFlow(),
        ) { sources, categories ->
            DecorationSnapshot(sources, categories)
        }

        return combine(core, decorations) { snapshot, decoration ->
            projectUnifiedLibrary(
                titles = snapshot.titles,
                localEntries = snapshot.localEntries,
                externalMemberships = snapshot.externalMemberships,
                formatObservations = snapshot.formatObservations,
                sources = decoration.sources,
                categories = decoration.categories,
            )
        }
    }

    private data class CoreSnapshot(
        val titles: List<CanonicalTitle>,
        val localEntries: List<CanonicalLibraryEntry>,
        val externalMemberships: List<ExternalLibraryMembership>,
        val formatObservations: List<TitleFormatObservation>,
    )

    private data class DecorationSnapshot(
        val sources: List<SourceRepresentation>,
        val categories: List<LibraryTitleCategory>,
    )
}
