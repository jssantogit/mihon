package tachiyomi.domain.tsuzuki.reader.interactor

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import tachiyomi.domain.tsuzuki.chapter.interactor.RefreshCanonicalChapters
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.model.SourceMappingAvailability
import tachiyomi.domain.tsuzuki.reader.model.CanonicalReadingStart
import tachiyomi.domain.tsuzuki.reader.service.CanonicalReadingStartResolver
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ResolveCanonicalReadingStart(
    private val canonicalChapterRepository: CanonicalChapterRepository,
    private val sourceTitleMappingRepository: SourceTitleMappingRepository,
    private val refreshCanonicalChapters: RefreshCanonicalChapters,
    private val getCanonicalReadingStart: GetCanonicalReadingStart,
) : CanonicalReadingStartResolver {

    override suspend fun execute(canonicalTitleId: String): CanonicalReadingStart {
        return try {
            ensureInventory(canonicalTitleId)
            getCanonicalReadingStart.execute(canonicalTitleId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            CanonicalReadingStart.Unavailable(canonicalTitleId, error)
        }
    }

    private suspend fun ensureInventory(canonicalTitleId: String) {
        val mappings = sourceTitleMappingRepository
            .getByCanonicalTitleId(canonicalTitleId)
            .filter {
                it.mihonMangaId != null &&
                    it.availability != SourceMappingAvailability.UNAVAILABLE
            }

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
}
