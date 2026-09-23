package eu.kanade.domain.tsuzuki.library.interactor

import dev.zacsweers.metro.Inject
import eu.kanade.domain.manga.interactor.UpdateManga
import tachiyomi.domain.tsuzuki.library.interactor.SetSourceLibraryMembership
import tachiyomi.domain.tsuzuki.library.model.SourceLibraryRepresentation

class SetUnifiedLibraryMembership internal constructor(
    private val setMihonFavorite: suspend (Long, Boolean) -> Boolean,
    private val addCanonical: suspend (SourceLibraryRepresentation) -> Unit,
    private val removeCanonical: suspend (Long, String) -> Unit,
) {

    @Inject
    constructor(
        updateManga: UpdateManga,
        setSourceLibraryMembership: SetSourceLibraryMembership,
    ) : this(
        setMihonFavorite = updateManga::awaitUpdateFavorite,
        addCanonical = setSourceLibraryMembership::add,
        removeCanonical = setSourceLibraryMembership::remove,
    )

    suspend fun set(
        source: SourceLibraryRepresentation,
        inLibrary: Boolean,
    ): Boolean {
        if (!setMihonFavorite(source.mihonMangaId, inLibrary)) {
            return false
        }

        try {
            if (inLibrary) {
                addCanonical(source)
            } else {
                removeCanonical(source.sourceId, source.sourceUrl)
            }
        } catch (error: Exception) {
            try {
                setMihonFavorite(source.mihonMangaId, !inLibrary)
            } catch (rollbackError: Exception) {
                error.addSuppressed(rollbackError)
            }
            throw error
        }

        return true
    }
}
