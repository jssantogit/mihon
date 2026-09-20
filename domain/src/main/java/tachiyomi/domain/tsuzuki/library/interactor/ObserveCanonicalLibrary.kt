package tachiyomi.domain.tsuzuki.library.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import tachiyomi.domain.tsuzuki.library.model.LibraryTitle
import tachiyomi.domain.tsuzuki.model.SourceTitleMapping
import tachiyomi.domain.tsuzuki.repository.CanonicalLibraryRepository
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository

@Inject
class ObserveCanonicalLibrary(
    private val canonicalLibraryRepository: CanonicalLibraryRepository,
    private val sourceTitleMappingRepository: SourceTitleMappingRepository = EmptySourceTitleMappingRepository,
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

private object EmptySourceTitleMappingRepository : SourceTitleMappingRepository {
    override suspend fun getAll(): List<SourceTitleMapping> = emptyList()

    override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<SourceTitleMapping> = emptyList()

    override fun getByCanonicalTitleIdAsFlow(canonicalTitleId: String): Flow<List<SourceTitleMapping>> =
        flowOf(emptyList())

    override suspend fun getBySource(sourceId: Long, sourceUrl: String): SourceTitleMapping? = null

    override suspend fun upsert(mapping: SourceTitleMapping) = Unit

    override suspend fun setPreferredForTitle(
        canonicalTitleId: String,
        mappingId: String?,
        updatedAt: Long,
    ) = Unit
}
