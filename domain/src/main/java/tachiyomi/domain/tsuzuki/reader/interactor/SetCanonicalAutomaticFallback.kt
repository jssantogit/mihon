package tachiyomi.domain.tsuzuki.reader.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.reader.model.CanonicalReaderPreference
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReaderPreferenceRepository
import kotlin.time.Clock

class SetCanonicalAutomaticFallback internal constructor(
    private val repository: CanonicalReaderPreferenceRepository,
    private val clock: () -> Long,
) {

    @Inject
    constructor(repository: CanonicalReaderPreferenceRepository) : this(
        repository = repository,
        clock = { Clock.System.now().toEpochMilliseconds() },
    )

    suspend fun execute(
        canonicalTitleId: String,
        enabled: Boolean,
    ) {
        repository.upsert(
            CanonicalReaderPreference(
                canonicalTitleId = canonicalTitleId,
                automaticFallback = enabled,
                updatedAt = clock(),
            ),
        )
    }
}
