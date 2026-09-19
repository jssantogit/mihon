package tachiyomi.domain.tsuzuki.source.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository
import kotlin.time.Clock

class SetTitleSourceOverride internal constructor(
    private val repository: SourceTitleMappingRepository,
    private val clock: () -> Long,
) {

    @Inject
    constructor(
        repository: SourceTitleMappingRepository,
    ) : this(
        repository = repository,
        clock = { Clock.System.now().toEpochMilliseconds() },
    )

    suspend fun execute(canonicalTitleId: String, mappingId: String?) {
        repository.setPreferredForTitle(canonicalTitleId, mappingId, clock())
    }
}
