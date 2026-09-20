package tachiyomi.domain.tsuzuki.chapter.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariantSelection
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.download.service.CanonicalDownloadGateway
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
    private val canonicalDownloadGateway: CanonicalDownloadGateway = NoCanonicalDownloads,
) {

    suspend fun execute(
        canonicalChapterId: String,
        preferredLanguage: String? = null,
        excludedVariantIds: Set<String> = emptySet(),
        excludedSourceMappingIds: Set<String> = emptySet(),
    ): ChapterVariantSelection {
        val chapter = canonicalChapterRepository.getById(canonicalChapterId)
            ?: return emptySelection(canonicalChapterId, preferredLanguage)
        val mappingList = sourceTitleMappingRepository
            .getByCanonicalTitleId(chapter.canonicalTitleId)
        val mappings = mappingList.associateBy { it.id }

        val candidates = buildList {
            canonicalChapterRepository
                .getVariantsByCanonicalChapterId(canonicalChapterId)
                .forEach { variant ->
                    if (variant.id in excludedVariantIds || variant.sourceMappingId in excludedSourceMappingIds) {
                        return@forEach
                    }
                    val mapping = mappings[variant.sourceMappingId] ?: return@forEach
                    if (
                        mapping.availability == SourceMappingAvailability.UNAVAILABLE &&
                        !canonicalDownloadGateway.isDownloaded(variant)
                    ) {
                        return@forEach
                    }
                    add(
                        RankedCandidate(
                            variant = variant,
                            mapping = mapping,
                            effectiveLanguage = effectiveLanguage(variant, mapping),
                        ),
                    )
                }
        }

        val normalizedPreferredLanguage = normalizeLanguage(preferredLanguage)
        val relevantLanguages = buildSet {
            preferredLanguage?.takeIf(String::isNotBlank)?.let(::add)
            mappingList.mapTo(this) { it.language }
            candidates.mapTo(this) { it.effectiveLanguage }
        }
        val preferenceRanks = loadPreferenceRanks(relevantLanguages)
        val preferredSourceMappingId = determinePreferredMapping(
            mappings = mappingList,
            normalizedPreferredLanguage = normalizedPreferredLanguage,
            preferenceRanks = preferenceRanks,
            locallyReadableMappingIds = candidates
                .filter { it.mapping.availability == SourceMappingAvailability.UNAVAILABLE }
                .mapTo(mutableSetOf()) { it.mapping.id },
        )

        if (candidates.isEmpty()) {
            return emptySelection(
                canonicalChapterId = canonicalChapterId,
                preferredLanguage = preferredLanguage,
                preferredSourceMappingId = preferredSourceMappingId,
            )
        }

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
            preferredSourceMappingId = preferredSourceMappingId,
            requiresFallback = preferredSourceMappingId != null &&
                selected.mapping.id != preferredSourceMappingId,
        )
    }

    suspend operator fun invoke(
        canonicalChapterId: String,
        preferredLanguage: String? = null,
    ): ChapterVariantSelection = execute(canonicalChapterId, preferredLanguage)

    private suspend fun loadPreferenceRanks(
        languages: Set<String>,
    ): Map<String?, Map<Long, Int>> {
        return languages
            .filter(String::isNotBlank)
            .distinctBy(::normalizeLanguage)
            .associate { language ->
                normalizeLanguage(language) to getPreferredReadingSources.await(language)
                    .associate { it.sourceId to it.position }
            }
    }

    private fun determinePreferredMapping(
        mappings: List<SourceTitleMapping>,
        normalizedPreferredLanguage: String?,
        preferenceRanks: Map<String?, Map<Long, Int>>,
        locallyReadableMappingIds: Set<String>,
    ): String? {
        val eligible = mappings.filter {
            it.availability != SourceMappingAvailability.UNAVAILABLE ||
                it.id in locallyReadableMappingIds
        }
        if (eligible.isEmpty()) return null

        val languageMatches = normalizedPreferredLanguage?.let { preferred ->
            eligible.filter { normalizeLanguage(it.language) == preferred }
        }.orEmpty()
        val scope = languageMatches.ifEmpty { eligible }

        scope.firstOrNull { it.preferredOverride }?.let { return it.id }

        val ranks = normalizedPreferredLanguage
            ?.let(preferenceRanks::get)
            .orEmpty()
        return scope
            .mapNotNull { mapping ->
                ranks[mapping.sourceId]?.let { rank -> mapping to rank }
            }
            .minWithOrNull(
                compareBy<Pair<SourceTitleMapping, Int>> { it.second }
                    .thenBy { if (it.first.verifiedByUser) 0 else 1 }
                    .thenBy { it.first.id },
            )
            ?.first
            ?.id
    }

    private fun effectiveLanguage(
        variant: ChapterVariant,
        mapping: SourceTitleMapping,
    ): String = variant.language.ifBlank { mapping.language }

    private fun emptySelection(
        canonicalChapterId: String,
        preferredLanguage: String?,
        preferredSourceMappingId: String? = null,
    ) = ChapterVariantSelection(
        canonicalChapterId = canonicalChapterId,
        preferredLanguage = preferredLanguage,
        selected = null,
        candidates = emptyList(),
        preferredSourceMappingId = preferredSourceMappingId,
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


private object NoCanonicalDownloads : CanonicalDownloadGateway {
    override suspend fun isDownloaded(variant: ChapterVariant): Boolean = false
}
