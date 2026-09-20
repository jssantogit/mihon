package tachiyomi.domain.tsuzuki.migration.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.interactor.MaterializeCanonicalTitle
import tachiyomi.domain.tsuzuki.migration.model.CanonicalMigrationReport
import tachiyomi.domain.tsuzuki.migration.repository.CanonicalLibraryMigrationStateRepository
import tachiyomi.domain.tsuzuki.migration.service.MihonLibraryGateway
import tachiyomi.domain.tsuzuki.model.CanonicalLibraryEntry
import tachiyomi.domain.tsuzuki.model.LibraryStatus
import tachiyomi.domain.tsuzuki.model.SourceMappingAvailability
import tachiyomi.domain.tsuzuki.model.SourceTitleMapping
import tachiyomi.domain.tsuzuki.repository.CanonicalLibraryRepository
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository
import java.util.UUID
import kotlin.time.Clock

open class MigrateMihonLibraryToCanonical internal constructor(
    private val gateway: MihonLibraryGateway,
    private val sourceTitleMappingRepository: SourceTitleMappingRepository,
    private val materializeCanonicalTitle: MaterializeCanonicalTitle,
    private val canonicalLibraryRepository: CanonicalLibraryRepository,
    private val migrationStateRepository: CanonicalLibraryMigrationStateRepository =
        AlwaysPendingCanonicalLibraryMigrationStateRepository,
    private val idFactory: () -> String,
    private val clock: () -> Long,
) {

    @Inject
    constructor(
        gateway: MihonLibraryGateway,
        sourceTitleMappingRepository: SourceTitleMappingRepository,
        materializeCanonicalTitle: MaterializeCanonicalTitle,
        canonicalLibraryRepository: CanonicalLibraryRepository,
        migrationStateRepository: CanonicalLibraryMigrationStateRepository,
    ) : this(
        gateway = gateway,
        sourceTitleMappingRepository = sourceTitleMappingRepository,
        materializeCanonicalTitle = materializeCanonicalTitle,
        canonicalLibraryRepository = canonicalLibraryRepository,
        migrationStateRepository = migrationStateRepository,
        idFactory = { UUID.randomUUID().toString() },
        clock = { Clock.System.now().toEpochMilliseconds() },
    )

    open suspend fun execute(): CanonicalMigrationReport {
        if (migrationStateRepository.isCompleted()) {
            return CanonicalMigrationReport(
                totalProcessed = 0,
                newlyImported = 0,
                alreadyMapped = 0,
            )
        }

        val snapshots = gateway.snapshot()
        var newlyImported = 0
        var alreadyMapped = 0

        for (snapshot in snapshots) {
            val existingMapping = sourceTitleMappingRepository.getBySource(
                sourceId = snapshot.sourceId,
                sourceUrl = snapshot.sourceUrl,
            )

            val now = clock()
            val canonicalTitleId = if (existingMapping != null) {
                alreadyMapped++
                existingMapping.canonicalTitleId
            } else {
                newlyImported++
                val canonicalTitle = materializeCanonicalTitle.fromSource(snapshot.title)
                val mapping = SourceTitleMapping(
                    id = idFactory(),
                    canonicalTitleId = canonicalTitle.id,
                    mihonMangaId = snapshot.mihonMangaId,
                    sourceId = snapshot.sourceId,
                    sourceUrl = snapshot.sourceUrl,
                    language = snapshot.sourceLanguage,
                    matchConfidence = 1.0,
                    verifiedByUser = false,
                    availability = if (snapshot.sourceAvailable) {
                        SourceMappingAvailability.AVAILABLE
                    } else {
                        SourceMappingAvailability.UNKNOWN
                    },
                    preferredOverride = false,
                    createdAt = now,
                    updatedAt = now,
                )
                sourceTitleMappingRepository.upsert(mapping)
                canonicalTitle.id
            }

            val existingEntry = canonicalLibraryRepository.get(canonicalTitleId)
            val status = when {
                existingEntry != null -> existingEntry.status
                snapshot.hasStarted -> LibraryStatus.READING
                else -> LibraryStatus.PLANNING
            }
            val addedAt = existingEntry?.addedAt ?: if (snapshot.dateAdded > 0L) snapshot.dateAdded else now
            val entry = CanonicalLibraryEntry(
                canonicalTitleId = canonicalTitleId,
                status = status,
                favorite = true,
                addedAt = addedAt,
                updatedAt = existingEntry?.updatedAt ?: now,
            )
            canonicalLibraryRepository.upsert(entry)
        }

        migrationStateRepository.markCompleted()

        return CanonicalMigrationReport(
            totalProcessed = snapshots.size,
            newlyImported = newlyImported,
            alreadyMapped = alreadyMapped,
        )
    }
}


private object AlwaysPendingCanonicalLibraryMigrationStateRepository :
    CanonicalLibraryMigrationStateRepository {

    override suspend fun isCompleted(): Boolean = false

    override suspend fun markCompleted() = Unit
}
