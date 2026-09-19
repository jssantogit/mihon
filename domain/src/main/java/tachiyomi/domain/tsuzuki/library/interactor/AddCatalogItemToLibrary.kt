package tachiyomi.domain.tsuzuki.library.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.interactor.MaterializeCanonicalTitleFromCatalog
import tachiyomi.domain.tsuzuki.library.model.CanonicalLibraryItem
import tachiyomi.domain.tsuzuki.model.CanonicalLibraryEntry
import tachiyomi.domain.tsuzuki.model.LibraryStatus
import tachiyomi.domain.tsuzuki.repository.CanonicalLibraryRepository
import kotlin.time.Clock

class AddCatalogItemToLibrary internal constructor(
    private val materializeCanonicalTitleFromCatalog: MaterializeCanonicalTitleFromCatalog,
    private val canonicalLibraryRepository: CanonicalLibraryRepository,
    private val clock: () -> Long,
) {

    @Inject
    constructor(
        materializeCanonicalTitleFromCatalog: MaterializeCanonicalTitleFromCatalog,
        canonicalLibraryRepository: CanonicalLibraryRepository,
    ) : this(
        materializeCanonicalTitleFromCatalog = materializeCanonicalTitleFromCatalog,
        canonicalLibraryRepository = canonicalLibraryRepository,
        clock = { Clock.System.now().toEpochMilliseconds() },
    )

    suspend fun execute(
        item: CatalogItem,
        status: LibraryStatus = LibraryStatus.PLANNING,
    ): CanonicalLibraryItem {
        val title = materializeCanonicalTitleFromCatalog.execute(item)
        val existingEntry = canonicalLibraryRepository.get(title.id)
        val entry = if (existingEntry != null) {
            existingEntry
        } else {
            val now = clock()
            val newEntry = CanonicalLibraryEntry(
                canonicalTitleId = title.id,
                status = status,
                favorite = true,
                addedAt = now,
                updatedAt = now,
            )
            canonicalLibraryRepository.upsert(newEntry)
            newEntry
        }
        return CanonicalLibraryItem(
            title = title,
            entry = entry,
        )
    }
}
