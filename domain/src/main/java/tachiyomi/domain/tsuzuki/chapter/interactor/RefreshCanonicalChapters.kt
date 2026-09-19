package tachiyomi.domain.tsuzuki.chapter.interactor

import kotlinx.coroutines.CancellationException
import tachiyomi.domain.tsuzuki.chapter.model.ChapterReconciliationReport
import tachiyomi.domain.tsuzuki.chapter.service.ChapterInventoryGateway
import tachiyomi.domain.tsuzuki.model.SourceTitleMapping
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository

/** Fetches an explicit source subset and reconciles it without source-wide scans. */
class RefreshCanonicalChapters(
    private val sourceTitleMappingRepository: SourceTitleMappingRepository,
    private val chapterInventoryGateway: ChapterInventoryGateway,
    private val reconcileChapterInventory: ReconcileChapterInventory,
) {

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
            // This keeps a failed source/network call non-destructive.
            val inventories = selected.map { mapping ->
                val result = chapterInventoryGateway.fetch(mapping)
                result.getOrElse { error ->
                    if (error is CancellationException) throw error
                    throw error
                }.also { inventory ->
                    require(inventory.canonicalTitleId.isBlank() || inventory.canonicalTitleId == canonicalTitleId) {
                        "Inventory title ${inventory.canonicalTitleId} does not match $canonicalTitleId"
                    }
                    require(inventory.sourceMappingId.isBlank() || inventory.sourceMappingId == mapping.id) {
                        "Inventory mapping ${inventory.sourceMappingId} does not match ${mapping.id}"
                    }
                }
            }

            val reports = selected.zip(inventories).map { (mapping, inventory) ->
                reconcileChapterInventory.execute(
                    inventory.copy(
                        canonicalTitleId = inventory.canonicalTitleId.ifBlank { canonicalTitleId },
                        sourceMappingId = inventory.sourceMappingId.ifBlank { mapping.id },
                    ),
                )
            }
            Result.success(mergeReports(canonicalTitleId, reports))
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

        val eligible = persisted.filter { it.mihonMangaId != null }
        fun validate(candidate: SourceTitleMapping?): Result<List<SourceTitleMapping>> {
            if (candidate == null) return Result.failure(IllegalArgumentException("Source mapping not found"))
            if (candidate.canonicalTitleId != canonicalTitleId || candidate.mihonMangaId == null) {
                return Result.failure(IllegalArgumentException("Source mapping is not materialized for this title"))
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
            if (materialized.any { it.canonicalTitleId != canonicalTitleId || it.mihonMangaId == null }) {
                return Result.failure(IllegalArgumentException("Source mapping is not materialized for this title"))
            }
            return Result.success(materialized)
        }

        val selected = eligible.firstOrNull { it.preferredOverride } ?: eligible.firstOrNull()
        return validate(selected)
    }

    private fun mergeReports(
        canonicalTitleId: String,
        reports: List<ChapterReconciliationReport>,
    ): ChapterReconciliationReport {
        val chapters = reports.flatMap { it.canonicalChapters }.distinctBy { it.id }
        val variants = reports
            .flatMap { it.variants }
            .distinctBy { it.sourceId to it.sourceChapterId }
        return ChapterReconciliationReport(
            canonicalTitleId = canonicalTitleId,
            canonicalChapters = chapters,
            variants = variants,
            sourceMappingIds = reports.flatMap { it.sourceMappingIds }.toSet(),
            createdCanonicalChapterIds = reports.flatMap { it.createdCanonicalChapterIds }.toSet(),
        )
    }
}
