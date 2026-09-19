package tachiyomi.domain.tsuzuki.chapter.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariantSelection
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.model.SourceMappingAvailability
import tachiyomi.domain.tsuzuki.model.SourceTitleMapping
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository
import tachiyomi.domain.tsuzuki.source.interactor.GetPreferredReadingSources
import java.util.Locale

@Inject
class SelectChapterVariant(
    private val canonicalChapterRepository: CanonicalChapterRepository,
    private val sourceTitleMappingRepository: SourceTitleMappingRepository,
    private val getPreferredReadingSources: GetPreferredReadingSources,
) {

    suspend fun execute(
        canonicalChapterId: String,
        preferredLanguage: String? = null,
        excludedVariantIds: Set<String> = emptySet(),
        excludedSourceMappingIds: Set<String> = emptySet(),
    ): ChapterVariantSelection {
        val chapter = canonicalChapterRepository.getById(canonicalChapterId)
            ?: return emptySelection(canonicalChapterId, preferredLanguage)
        val mappings = sourceTitleMappingRepository
            .getByCanonicalTitleId(chapter.canonicalTitleId)
            .associateBy { it.id }

        val candidates = canonicalChapterRepository
            .getVariantsByCanonicalChapterId(canonicalChapterId)
            .filterNot { it.id in excludedVariantIds || it.sourceMappingId in excludedSourceMappingIds }
            .mapNotNull { variant ->
                val mapping = mappings[variant.sourceMappingId] ?: return@mapNotNull null
                if (mapping.availability == SourceMappingAvailability.UNAVAILABLE) return@mapNotNull null
                RankedCandidate(
                    variant = variant,
                    mapping = mapping,
                    effectiveLanguage = effectiveLanguage(variant, mapping),
                )
            }

        if (candidates.isEmpty()) {
            return emptySelection(canonicalChapterId, preferredLanguage)
        }

        val preferenceRanks = loadPreferenceRanks(candidates)
        val normalizedPreferredLanguage = normalizeLanguage(preferredLanguage)
        val ordered = candidates.sortedWith(
            compareBy<RankedCandidate> {
                if (
                    normalizedPreferredLanguage != null &&
                    normalizeLanguage(it.effectiveLanguage) == normalizedPreferredLanguage
                ) {
                    0
                } else {
                    1
                }
            }
                .thenBy { if (it.mapping.preferredOverride) 0 else 1 }
                .thenBy {
                    preferenceRanks[normalizeLanguage(it.effectiveLanguage)]
                        ?.get(it.variant.sourceId)
                        ?: Int.MAX_VALUE
                }
                .thenBy { if (it.mapping.verifiedByUser) 0 else 1 }
                .thenByDescending { it.variant.releaseDate ?: Long.MIN_VALUE }
                .thenBy { it.variant.id },
        )

        val selected = ordered.first()
        return ChapterVariantSelection(
            canonicalChapterId = canonicalChapterId,
            preferredLanguage = preferredLanguage,
            selected = selected.variant,
            candidates = ordered.map { it.variant },
            usedPreferredLanguage = normalizedPreferredLanguage != null &&
                normalizeLanguage(selected.effectiveLanguage) == normalizedPreferredLanguage,
            usedPreferredMapping = selected.mapping.preferredOverride,
        )
    }

    suspend operator fun invoke(
        canonicalChapterId: String,
        preferredLanguage: String? = null,
    ): ChapterVariantSelection = execute(canonicalChapterId, preferredLanguage)

    private suspend fun loadPreferenceRanks(
        candidates: List<RankedCandidate>,
    ): Map<String?, Map<Long, Int>> {
        val languages = candidates
            .map { it.effectiveLanguage }
            .filter { it.isNotBlank() }
            .distinctBy(::normalizeLanguage)

        return languages.associate { language ->
            normalizeLanguage(language) to getPreferredReadingSources.await(language)
                .associate { it.sourceId to it.position }
        }
    }

    private fun effectiveLanguage(
        variant: ChapterVariant,
        mapping: SourceTitleMapping,
    ): String = variant.language.ifBlank { mapping.language }

    private fun emptySelection(
        canonicalChapterId: String,
        preferredLanguage: String?,
    ) = ChapterVariantSelection(
        canonicalChapterId = canonicalChapterId,
        preferredLanguage = preferredLanguage,
        selected = null,
        candidates = emptyList(),
    )

    private fun normalizeLanguage(language: String?): String? {
        return language
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.lowercase(Locale.ROOT)
    }

    private data class RankedCandidate(
        val variant: ChapterVariant,
        val mapping: SourceTitleMapping,
        val effectiveLanguage: String,
    )
}
