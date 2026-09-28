package tachiyomi.domain.tsuzuki.chapter.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import tachiyomi.domain.tsuzuki.chapter.evidence.ReconcileLegacyChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.ChapterReconciliationReport
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.chapter.service.ChapterInventoryGateway
import tachiyomi.domain.tsuzuki.model.SourceMappingAvailability
import tachiyomi.domain.tsuzuki.model.SourceTitleMapping
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository
import kotlin.time.Clock

/**
 * Compatibility entrypoint for Reader and targeted refresh flows backed by
 * materialized Mihon mappings. Canonical identity and operational variants are
 * committed together through the chapter evidence reconciler.
 */
class RefreshCanonicalChapters internal constructor(
    private val sourceTitleMappingRepository: SourceTitleMappingRepository,
    private val chapterInventoryGateway: ChapterInventoryGateway,
    private val reconcileLegacyChapterEvidence: ReconcileLegacyChapterEvidence,
    private val canonicalChapterRepository: CanonicalChapterRepository,
    private val clock: () -> Long,
) {

    @Inject
    constructor(
        sourceTitleMappingRepository: SourceTitleMappingRepository,
        chapterInventoryGateway: ChapterInventoryGateway,
        reconcileLegacyChapterEvidence: ReconcileLegacyChapterEvidence,
        canonicalChapterRepository: CanonicalChapterRepository,
    ) : this(
        sourceTitleMappingRepository = sourceTitleMappingRepository,
        chapterInventoryGateway = chapterInventoryGateway,
        reconcileLegacyChapterEvidence = reconcileLegacyChapterEvidence,
        canonicalChapterRepository = canonicalChapterRepository,
        clock = { Clock.System.now().toEpochMilliseconds() },
    )

    suspend fun execute(
        canonicalTitleId: String,
        mappingId: String? = null,
        mappingIds: List<String>? = null,
    ): Result<ChapterReconciliationReport> {
        return try {
            val persisted = sourceTitleMappingRepository.getByCanonicalTitleId(canonicalTitleId)
            val selected = selectMappings(
                canonicalTitleId = canonicalTitleId,
                persisted = persisted,
                mappingId = mappingId,
                mappingIds = mappingIds,
            ).getOrThrow()

            // Fetch every requested source before writing any canonical state.
            // The resulting inventories are then reconciled in one atomic batch.
            val inventories = selected.map { mapping ->
                val inventory = chapterInventoryGateway.fetch(mapping).getOrThrow()
                require(inventory.canonicalTitleId.isBlank() || inventory.canonicalTitleId == canonicalTitleId) {
                    "Inventory title ${inventory.canonicalTitleId} does not match $canonicalTitleId"
                }
                require(inventory.sourceMappingId.isBlank() || inventory.sourceMappingId == mapping.id) {
                    "Inventory mapping ${inventory.sourceMappingId} does not match ${mapping.id}"
                }
                inventory.copy(
                    canonicalTitleId = inventory.canonicalTitleId.ifBlank { canonicalTitleId },
                    sourceMappingId = inventory.sourceMappingId.ifBlank { mapping.id },
                )
            }

            val variants = reconcileLegacyChapterEvidence.execute(inventories, clock())
            val canonicalChapters = mutableListOf<CanonicalChapter>()
            for (canonicalChapterId in variants.map(ChapterVariant::canonicalChapterId).distinct()) {
                canonicalChapterRepository.getById(canonicalChapterId)?.let(canonicalChapters::add)
            }
            Result.success(
                ChapterReconciliationReport(
                    canonicalTitleId = canonicalTitleId,
                    canonicalChapters = canonicalChapters,
                    variants = variants,
                    sourceMappingIds = inventories.mapTo(linkedSetOf()) { it.sourceMappingId },
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    suspend operator fun invoke(
        canonicalTitleId: String,
        mappingId: String? = null,
        mappingIds: List<String>? = null,
    ): Result<ChapterReconciliationReport> = execute(canonicalTitleId, mappingId, mappingIds)

    private fun selectMappings(
        canonicalTitleId: String,
        persisted: List<SourceTitleMapping>,
        mappingId: String?,
        mappingIds: List<String>?,
    ): Result<List<SourceTitleMapping>> {
        if (mappingId != null && mappingIds != null) {
            return Result.failure(IllegalArgumentException("Specify mappingId or mappingIds, not both"))
        }

        val eligible = persisted.filter(::isEligible)
        fun validate(candidate: SourceTitleMapping?): Result<List<SourceTitleMapping>> {
            if (candidate == null) return Result.failure(IllegalArgumentException("Source mapping not found"))
            if (candidate.canonicalTitleId != canonicalTitleId || !isEligible(candidate)) {
                return Result.failure(
                    IllegalArgumentException("Source mapping is not available and materialized for this title"),
                )
            }
            return Result.success(listOf(candidate))
        }

        mappingId?.let { requested ->
            return validate(persisted.firstOrNull { it.id == requested })
        }

        mappingIds?.let { requested ->
            if (requested.isEmpty()) return Result.failure(IllegalArgumentException("Source mapping subset is empty"))
            val byId = persisted.associateBy { it.id }
            val selected = requested.distinct().map { id -> byId[id] }
            if (selected.any { it == null }) return Result.failure(IllegalArgumentException("Source mapping not found"))
            val materialized = selected.filterNotNull()
            if (materialized.any { it.canonicalTitleId != canonicalTitleId || !isEligible(it) }) {
                return Result.failure(
                    IllegalArgumentException("Source mapping is not available and materialized for this title"),
                )
            }
            return Result.success(materialized)
        }

        val selected = eligible.firstOrNull { it.preferredOverride } ?: eligible.firstOrNull()
        return validate(selected)
    }

    private fun isEligible(mapping: SourceTitleMapping): Boolean {
        return mapping.mihonMangaId != null &&
            mapping.availability != SourceMappingAvailability.UNAVAILABLE
    }
}
