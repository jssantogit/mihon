package tachiyomi.domain.tsuzuki.library.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.model.LibraryStatus
import tachiyomi.domain.tsuzuki.repository.CanonicalLibraryRepository
import kotlin.time.Clock

class SetCanonicalLibraryStatus internal constructor(
    private val canonicalLibraryRepository: CanonicalLibraryRepository,
    private val clock: () -> Long,
) {

    @Inject
    constructor(
        canonicalLibraryRepository: CanonicalLibraryRepository,
    ) : this(
        canonicalLibraryRepository = canonicalLibraryRepository,
        clock = { Clock.System.now().toEpochMilliseconds() },
    )

    suspend fun execute(canonicalTitleId: String, status: LibraryStatus) {
        val existingEntry = canonicalLibraryRepository.get(canonicalTitleId) ?: return
        val updatedEntry = existingEntry.copy(
            status = status,
            updatedAt = clock(),
        )
        canonicalLibraryRepository.upsert(updatedEntry)
    }
}
