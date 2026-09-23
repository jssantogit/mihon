package tachiyomi.domain.tsuzuki.reader.interactor

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import tachiyomi.domain.tsuzuki.chapter.interactor.RefreshCanonicalChapters
import tachiyomi.domain.tsuzuki.chapter.interactor.RepairZeroPlaceholderChapterSemantics
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.model.SourceMappingAvailability
import tachiyomi.domain.tsuzuki.reader.model.CanonicalReadingStart
import tachiyomi.domain.tsuzuki.reader.service.CanonicalReadingStartResolver
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository
import tachiyomi.domain.tsuzuki.source.interactor.ResolveReadingSource
import tachiyomi.domain.tsuzuki.source.model.SourceResolutionResult

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ResolveCanonicalReadingStart(
    private val canonicalChapterRepository: CanonicalChapterRepository,
    private val sourceTitleMappingRepository: SourceTitleMappingRepository,
    private val refreshCanonicalChapters: RefreshCanonicalChapters,
    private val repairZeroPlaceholderChapterSemantics: RepairZeroPlaceholderChapterSemantics,
    private val resolveReadingSource: ResolveReadingSource,
    private val importLegacyCanonicalProgress: ImportLegacyCanonicalProgress,
    private val getCanonicalReadingStart: GetCanonicalReadingStart,
) : CanonicalReadingStartResolver {

    override suspend fun execute(canonicalTitleId: String): CanonicalReadingStart {
        return try {
            ensureInventory(canonicalTitleId)
            repairPersistedChapterSemantics(canonicalTitleId)
            importLegacyCanonicalProgress.execute(canonicalTitleId)
            getCanonicalReadingStart.execute(canonicalTitleId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            CanonicalReadingStart.Unavailable(canonicalTitleId, error)
        }
    }

    private suspend fun repairPersistedChapterSemantics(canonicalTitleId: String) {
        try {
            repairZeroPlaceholderChapterSemantics.execute(canonicalTitleId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // Repair is best-effort and must never make a previously readable
            // canonical title unavailable.
        }
    }

    private suspend fun ensureInventory(canonicalTitleId: String) {
        var persisted = sourceTitleMappingRepository.getByCanonicalTitleId(canonicalTitleId)
        val requested = persisted.firstOrNull { it.preferredOverride } ?: persisted.firstOrNull()

        if (requested != null && !isEligible(requested)) {
            try {
                val repaired = resolveReadingSource.execute(
                    canonicalTitleId = canonicalTitleId,
                    language = requested.language,
                )
                if (repaired is SourceResolutionResult.Resolved) {
                    persisted = sourceTitleMappingRepository.getByCanonicalTitleId(canonicalTitleId)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // A secondary materialized mapping may still be usable below.
            }
        }

        val mappings = persisted.filter(::isEligible)
        if (mappings.isEmpty()) {
            throw IllegalStateException("No materialized reading source is available")
        }

        val preferred = mappings.firstOrNull { it.preferredOverride } ?: mappings.first()
        val chapters = canonicalChapterRepository.getByCanonicalTitleId(canonicalTitleId)

        if (chapters.isEmpty()) {
            refreshCanonicalChapters.execute(
                canonicalTitleId = canonicalTitleId,
                mappingId = preferred.id,
            ).getOrThrow()
        }

        // Enrich fallback coverage only for mappings not inventoried yet.
        // Secondary source failure must never block the preferred reading path.
        for (mapping in mappings) {
            if (mapping.id == preferred.id && chapters.isEmpty()) continue
            if (canonicalChapterRepository.getVariantsBySourceMappingId(mapping.id).isNotEmpty()) continue

            try {
                refreshCanonicalChapters.execute(
                    canonicalTitleId = canonicalTitleId,
                    mappingId = mapping.id,
                ).getOrThrow()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // Best-effort fallback inventory enrichment.
            }
        }
    }

    private fun isEligible(mapping: tachiyomi.domain.tsuzuki.model.SourceTitleMapping): Boolean {
        return mapping.mihonMangaId != null &&
            mapping.availability != SourceMappingAvailability.UNAVAILABLE
    }
}
