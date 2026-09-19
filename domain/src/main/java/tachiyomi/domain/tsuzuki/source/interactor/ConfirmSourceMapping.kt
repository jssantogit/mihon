package tachiyomi.domain.tsuzuki.source.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import tachiyomi.domain.tsuzuki.model.SourceMappingAvailability
import tachiyomi.domain.tsuzuki.model.SourceTitleMapping
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository
import tachiyomi.domain.tsuzuki.source.model.ConfirmSourceResult
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceCandidate
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
        language: String = "",
        matchConfidence: Double? = null,
        verifiedByUser: Boolean = true,
    ): ConfirmSourceResult {
        return execute(
            canonicalTitleId = canonicalTitleId,
            sourceId = candidate.sourceId,
            sourceUrl = candidate.sourceUrl,
            title = candidate.title,
            language = language,
            matchConfidence = matchConfidence,
            verifiedByUser = verifiedByUser,
        )
    }

    suspend fun execute(
        canonicalTitleId: Long,
        candidate: ReadingSourceCandidate,
        language: String = "",
        matchConfidence: Double? = null,
        verifiedByUser: Boolean = true,
    ): ConfirmSourceResult {
        return execute(
            canonicalTitleId = canonicalTitleId.toString(),
            candidate = candidate,
            language = language,
            matchConfidence = matchConfidence,
            verifiedByUser = verifiedByUser,
        )
    }

    suspend fun execute(
        canonicalTitleId: String,
        sourceId: Long,
        sourceUrl: String,
        title: String,
        language: String = "",
        matchConfidence: Double? = null,
        verifiedByUser: Boolean = true,
    ): ConfirmSourceResult {
        try {
            val existing = sourceTitleMappingRepository.getBySource(sourceId, sourceUrl)
            if (existing != null) {
                if (existing.canonicalTitleId == canonicalTitleId) {
                    val updated = if (verifiedByUser && !existing.verifiedByUser) {
                        val now = clock()
                        val verified = existing.copy(verifiedByUser = true, updatedAt = now)
                        sourceTitleMappingRepository.upsert(verified)
                        verified
                    } else {
                        existing
                    }
                    return ConfirmSourceResult.Success(updated)
                } else {
                    return ConfirmSourceResult.Conflict(existing.canonicalTitleId)
                }
            }

            val materializeResult = readingSourceGateway.materializeSource(sourceId, sourceUrl, title)
            val materialized = materializeResult.getOrElse { error ->
                return ConfirmSourceResult.Failure(error)
            }

            val now = clock()
            val mapping = SourceTitleMapping(
                id = idFactory(),
                canonicalTitleId = canonicalTitleId,
                mihonMangaId = materialized.mihonMangaId,
                sourceId = sourceId,
                sourceUrl = sourceUrl,
                language = language,
                matchConfidence = matchConfidence,
                verifiedByUser = verifiedByUser,
                availability = SourceMappingAvailability.AVAILABLE,
                preferredOverride = false,
                createdAt = now,
                updatedAt = now,
            )
            sourceTitleMappingRepository.upsert(mapping)
            return ConfirmSourceResult.Success(mapping)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return ConfirmSourceResult.Failure(t)
        }
    }
}
