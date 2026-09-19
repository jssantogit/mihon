package tachiyomi.domain.tsuzuki.source.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.model.SourceMappingAvailability
import tachiyomi.domain.tsuzuki.model.SourceTitleMapping
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceCandidate
import tachiyomi.domain.tsuzuki.source.model.SourceResolutionResult
import tachiyomi.domain.tsuzuki.source.service.ReadingSourceGateway
import java.util.UUID
import kotlin.time.Clock

class ConfirmSourceMapping internal constructor(
    private val sourceTitleMappingRepository: SourceTitleMappingRepository,
    private val readingSourceGateway: ReadingSourceGateway,
    private val idFactory: () -> String,
    private val clock: () -> Long,
) {

    @Inject
    constructor(
        sourceTitleMappingRepository: SourceTitleMappingRepository,
        readingSourceGateway: ReadingSourceGateway,
    ) : this(
        sourceTitleMappingRepository = sourceTitleMappingRepository,
        readingSourceGateway = readingSourceGateway,
        idFactory = { UUID.randomUUID().toString() },
        clock = { Clock.System.now().toEpochMilliseconds() },
    )

    suspend fun execute(
        canonicalTitleId: String,
        candidate: ReadingSourceCandidate,
        matchConfidence: Double? = null,
        verifiedByUser: Boolean = true,
    ): SourceResolutionResult {
        val existing = sourceTitleMappingRepository.getBySource(
            sourceId = candidate.sourceId,
            sourceUrl = candidate.sourceUrl,
        )
        if (existing != null) {
            if (existing.canonicalTitleId != canonicalTitleId) {
                return SourceResolutionResult.Conflict(existing.canonicalTitleId)
            }

            val mapping = if (verifiedByUser && !existing.verifiedByUser) {
                existing.copy(
                    verifiedByUser = true,
                    updatedAt = clock(),
                ).also { sourceTitleMappingRepository.upsert(it) }
            } else {
                existing
            }
            return SourceResolutionResult.Resolved(
                mapping = mapping,
                reused = true,
            )
        }

        val materialized = readingSourceGateway.materialize(candidate).getOrThrow()
        val now = clock()
        val mapping = SourceTitleMapping(
            id = idFactory(),
            canonicalTitleId = canonicalTitleId,
            mihonMangaId = materialized.mihonMangaId,
            sourceId = candidate.sourceId,
            sourceUrl = candidate.sourceUrl,
            language = candidate.language,
            matchConfidence = matchConfidence,
            verifiedByUser = verifiedByUser,
            availability = SourceMappingAvailability.AVAILABLE,
            preferredOverride = false,
            createdAt = now,
            updatedAt = now,
        )
        sourceTitleMappingRepository.upsert(mapping)
        return SourceResolutionResult.Resolved(
            mapping = mapping,
            reused = false,
        )
    }
}
