package eu.kanade.domain.tsuzuki.library.interactor

import dev.zacsweers.metro.Inject
import eu.kanade.domain.manga.interactor.UpdateManga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.tsuzuki.library.interactor.RemoveCanonicalLibraryItem
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository

class RemoveUnifiedLibraryTitle internal constructor(
    private val getFavoriteMihonMangaIds: suspend (String) -> List<Long>,
    private val setMihonFavorite: suspend (Long, Boolean) -> Boolean,
    private val removeCanonical: suspend (String) -> Unit,
) {

    @Inject
    constructor(
        sourceTitleMappingRepository: SourceTitleMappingRepository,
        mangaRepository: MangaRepository,
        updateManga: UpdateManga,
        removeCanonicalLibraryItem: RemoveCanonicalLibraryItem,
    ) : this(
        getFavoriteMihonMangaIds = { canonicalTitleId ->
            sourceTitleMappingRepository
                .getByCanonicalTitleId(canonicalTitleId)
                .mapNotNull { it.mihonMangaId }
                .distinct()
                .mapNotNull { mangaId ->
                    try {
                        mangaRepository.getMangaById(mangaId)
                            .takeIf { it.favorite }
                            ?.id
                    } catch (_: Exception) {
                        null
                    }
                }
        },
        setMihonFavorite = updateManga::awaitUpdateFavorite,
        removeCanonical = removeCanonicalLibraryItem::execute,
    )

    suspend fun execute(canonicalTitleId: String): Boolean {
        val favoriteMihonMangaIds = getFavoriteMihonMangaIds(canonicalTitleId)
        val clearedMangaIds = mutableListOf<Long>()

        for (mangaId in favoriteMihonMangaIds) {
            if (!setMihonFavorite(mangaId, false)) {
                restoreFavorites(clearedMangaIds)
                return false
            }
            clearedMangaIds += mangaId
        }

        try {
            removeCanonical(canonicalTitleId)
        } catch (error: Exception) {
            restoreFavorites(clearedMangaIds)
            throw error
        }

        return true
    }

    private suspend fun restoreFavorites(mangaIds: List<Long>) {
        mangaIds.asReversed().forEach { mangaId ->
            try {
                setMihonFavorite(mangaId, true)
            } catch (_: Exception) {
                // Best effort rollback. Canonical membership remains authoritative.
            }
        }
    }
}
