package tachiyomi.domain.tsuzuki.library.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.interactor.MaterializeCanonicalTitle
import tachiyomi.domain.tsuzuki.library.model.SourceLibraryRepresentation
import tachiyomi.domain.tsuzuki.model.CanonicalLibraryEntry
import tachiyomi.domain.tsuzuki.model.LibraryStatus
import tachiyomi.domain.tsuzuki.model.SourceMappingAvailability
import tachiyomi.domain.tsuzuki.model.SourceTitleMapping
import tachiyomi.domain.tsuzuki.repository.CanonicalLibraryRepository
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository
import java.util.UUID
import kotlin.time.Clock

class SetSourceLibraryMembership internal constructor(
    private val canonicalLibraryRepository: CanonicalLibraryRepository,
    private val sourceTitleMappingRepository: SourceTitleMappingRepository,
    private val materializeCanonicalTitle: MaterializeCanonicalTitle,
    private val mappingIdFactory: () -> String,
    private val clock: () -> Long,
) {

    @Inject
    constructor(
        canonicalLibraryRepository: CanonicalLibraryRepository,
        sourceTitleMappingRepository: SourceTitleMappingRepository,
        materializeCanonicalTitle: MaterializeCanonicalTitle,
    ) : this(
        canonicalLibraryRepository = canonicalLibraryRepository,
        sourceTitleMappingRepository = sourceTitleMappingRepository,
        materializeCanonicalTitle = materializeCanonicalTitle,
        mappingIdFactory = { UUID.randomUUID().toString() },
        clock = { Clock.System.now().toEpochMilliseconds() },
    )

    suspend fun add(source: SourceLibraryRepresentation) {
        val now = clock()
        val existingMapping = sourceTitleMappingRepository.getBySource(
            sourceId = source.sourceId,
            sourceUrl = source.sourceUrl,
        )

        val canonicalTitleId = if (existingMapping != null) {
            sourceTitleMappingRepository.upsert(
                existingMapping.copy(
                    mihonMangaId = source.mihonMangaId,
                    language = source.language,
                    availability = source.availability(),
                    updatedAt = now,
                ),
            )
            existingMapping.canonicalTitleId
        } else {
            val title = materializeCanonicalTitle.fromSource(source.displayTitle)
            sourceTitleMappingRepository.upsert(
                SourceTitleMapping(
                    id = mappingIdFactory(),
                    canonicalTitleId = title.id,
                    mihonMangaId = source.mihonMangaId,
                    sourceId = source.sourceId,
                    sourceUrl = source.sourceUrl,
                    language = source.language,
                    matchConfidence = 1.0,
                    verifiedByUser = false,
                    availability = source.availability(),
                    preferredOverride = false,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            title.id
        }

        val existingEntry = canonicalLibraryRepository.get(canonicalTitleId)
        canonicalLibraryRepository.upsert(
            CanonicalLibraryEntry(
                canonicalTitleId = canonicalTitleId,
                status = existingEntry?.status ?: if (source.hasStarted) {
                    LibraryStatus.READING
                } else {
                    LibraryStatus.PLANNING
                },
                favorite = true,
                addedAt = existingEntry?.addedAt
                    ?: source.dateAdded.takeIf { it > 0L }
                    ?: now,
                updatedAt = existingEntry?.updatedAt ?: now,
            ),
        )
    }

    suspend fun remove(sourceId: Long, sourceUrl: String) {
        val mapping = sourceTitleMappingRepository.getBySource(sourceId, sourceUrl) ?: return
        canonicalLibraryRepository.remove(mapping.canonicalTitleId)
    }

    private fun SourceLibraryRepresentation.availability(): SourceMappingAvailability {
        return if (sourceAvailable) {
            SourceMappingAvailability.AVAILABLE
        } else {
            SourceMappingAvailability.UNKNOWN
        }
    }
}
