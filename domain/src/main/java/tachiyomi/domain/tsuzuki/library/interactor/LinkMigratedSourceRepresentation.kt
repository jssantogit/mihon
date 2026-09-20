package tachiyomi.domain.tsuzuki.library.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.model.SourceMappingAvailability
import tachiyomi.domain.tsuzuki.model.SourceTitleMapping
import tachiyomi.domain.tsuzuki.repository.CanonicalLibraryRepository
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository
import java.util.UUID
import kotlin.time.Clock

class LinkMigratedSourceRepresentation internal constructor(
    private val sourceTitleMappingRepository: SourceTitleMappingRepository,
    private val canonicalLibraryRepository: CanonicalLibraryRepository,
    private val mappingIdFactory: () -> String,
    private val clock: () -> Long,
) {

    @Inject
    constructor(
        sourceTitleMappingRepository: SourceTitleMappingRepository,
        canonicalLibraryRepository: CanonicalLibraryRepository,
    ) : this(
        sourceTitleMappingRepository = sourceTitleMappingRepository,
        canonicalLibraryRepository = canonicalLibraryRepository,
        mappingIdFactory = { UUID.randomUUID().toString() },
        clock = { Clock.System.now().toEpochMilliseconds() },
    )

    suspend fun execute(
        originSourceId: Long,
        originSourceUrl: String,
        targetMihonMangaId: Long,
        targetSourceId: Long,
        targetSourceUrl: String,
        targetLanguage: String,
    ): String? {
        val origin = sourceTitleMappingRepository.getBySource(
            sourceId = originSourceId,
            sourceUrl = originSourceUrl,
        ) ?: return null

        val now = clock()
        val existingTarget = sourceTitleMappingRepository.getBySource(
            sourceId = targetSourceId,
            sourceUrl = targetSourceUrl,
        )
        val duplicateCanonicalTitleId = existingTarget
            ?.canonicalTitleId
            ?.takeIf { it != origin.canonicalTitleId }

        val targetMapping = if (existingTarget != null) {
            existingTarget.copy(
                canonicalTitleId = origin.canonicalTitleId,
                mihonMangaId = targetMihonMangaId,
                language = targetLanguage,
                matchConfidence = 1.0,
                verifiedByUser = true,
                availability = SourceMappingAvailability.AVAILABLE,
                updatedAt = now,
            )
        } else {
            SourceTitleMapping(
                id = mappingIdFactory(),
                canonicalTitleId = origin.canonicalTitleId,
                mihonMangaId = targetMihonMangaId,
                sourceId = targetSourceId,
                sourceUrl = targetSourceUrl,
                language = targetLanguage,
                matchConfidence = 1.0,
                verifiedByUser = true,
                availability = SourceMappingAvailability.AVAILABLE,
                preferredOverride = false,
                createdAt = now,
                updatedAt = now,
            )
        }

        sourceTitleMappingRepository.upsert(targetMapping)
        sourceTitleMappingRepository.setPreferredForTitle(
            canonicalTitleId = origin.canonicalTitleId,
            mappingId = targetMapping.id,
            updatedAt = now,
        )

        duplicateCanonicalTitleId?.let {
            canonicalLibraryRepository.remove(it)
        }

        return origin.canonicalTitleId
    }
}
