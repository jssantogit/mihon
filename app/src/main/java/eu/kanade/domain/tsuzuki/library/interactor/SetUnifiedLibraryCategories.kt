package eu.kanade.domain.tsuzuki.library.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.tsuzuki.repository.LibraryTitleCategoryRepository
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository

class SetUnifiedLibraryCategories internal constructor(
    private val getMihonMangaIds: suspend (String) -> List<Long>,
    private val getMihonCategoryIds: suspend (Long) -> List<Long>,
    private val setMihonCategories: suspend (Long, List<Long>) -> Unit,
    private val setCanonicalCategories: suspend (String, List<Long>) -> Unit,
) {

    @Inject
    constructor(
        sourceTitleMappingRepository: SourceTitleMappingRepository,
        categoryRepository: CategoryRepository,
        mangaRepository: MangaRepository,
        libraryTitleCategoryRepository: LibraryTitleCategoryRepository,
    ) : this(
        getMihonMangaIds = { canonicalTitleId ->
            sourceTitleMappingRepository
                .getByCanonicalTitleId(canonicalTitleId)
                .mapNotNull { it.mihonMangaId }
                .distinct()
                .mapNotNull { mangaId ->
                    try {
                        mangaRepository.getMangaById(mangaId).id
                    } catch (_: Exception) {
                        null
                    }
                }
        },
        getMihonCategoryIds = { mangaId ->
            categoryRepository.getCategoriesByMangaId(mangaId).map { it.id }
        },
        setMihonCategories = mangaRepository::setMangaCategories,
        setCanonicalCategories = libraryTitleCategoryRepository::setCategories,
    )

    suspend fun execute(canonicalTitleId: String, categoryIds: List<Long>) {
        val normalizedCategoryIds = categoryIds.distinct()
        val mangaIds = getMihonMangaIds(canonicalTitleId)
        val previousCategories = mangaIds.associateWith { mangaId ->
            getMihonCategoryIds(mangaId)
        }
        val projectedMangaIds = mutableListOf<Long>()

        try {
            mangaIds.forEach { mangaId ->
                setMihonCategories(mangaId, normalizedCategoryIds)
                projectedMangaIds += mangaId
            }

            setCanonicalCategories(canonicalTitleId, normalizedCategoryIds)
        } catch (error: Exception) {
            rollback(
                projectedMangaIds = projectedMangaIds,
                previousCategories = previousCategories,
                originalError = error,
            )
            throw error
        }
    }

    private suspend fun rollback(
        projectedMangaIds: List<Long>,
        previousCategories: Map<Long, List<Long>>,
        originalError: Exception,
    ) {
        projectedMangaIds.asReversed().forEach { mangaId ->
            try {
                setMihonCategories(
                    mangaId,
                    previousCategories[mangaId].orEmpty(),
                )
            } catch (rollbackError: Exception) {
                originalError.addSuppressed(rollbackError)
            }
        }
    }
}
