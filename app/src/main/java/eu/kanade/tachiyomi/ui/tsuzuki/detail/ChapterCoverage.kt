package eu.kanade.tachiyomi.ui.tsuzuki.detail

import tachiyomi.domain.tsuzuki.chapter.evidence.PersistedChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.ProducerKind
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter

/**
 * Summarizes only observed chapter identities. Provider-reported counts are metadata,
 * and cannot be used to infer or fabricate missing chapter rows.
 */
internal fun firstDiscoveredChapterNumber(chapters: List<CanonicalChapter>): Int? =
    chapters.mapNotNull { it.baseNumber }
        .filter { it >= 0 }
        .minOrNull()

/** Persisted observations; the Add-on may no longer offer these chapters today. */
data class ObservedAddonCoverage(
    val addonId: String,
    val displayName: String,
    val observedChapterCount: Int,
    val firstKnownNumber: Int?,
    val lastKnownNumber: Int?,
)

internal fun observedAddonCoverage(
    chapters: List<CanonicalChapter>,
    evidence: List<PersistedChapterEvidence>,
    addonNames: Map<String, String>,
): List<ObservedAddonCoverage> {
    val addonMappedChapterIds = evidence.asSequence()
        .filter { it.evidence.producerKind == ProducerKind.ADDON }
        .mapNotNull { persisted ->
            persisted.mappedCanonicalChapterId?.let { chapterId ->
                persisted.evidence.producerId to chapterId
            }
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, chapterIds) -> chapterIds.toSet() }
    return observedAddonCoverage(chapters, addonMappedChapterIds, addonNames)
}

internal fun observedAddonCoverage(
    chapters: List<CanonicalChapter>,
    addonMappedChapterIds: Map<String, Set<String>>,
    addonNames: Map<String, String>,
): List<ObservedAddonCoverage> {
    val byId = chapters.associateBy(CanonicalChapter::id)
    return addonMappedChapterIds.mapNotNull { (addonId, chapterIds) ->
        val observed = chapterIds.mapNotNull(byId::get)
        if (observed.isEmpty()) return@mapNotNull null
        val numbered = observed.mapNotNull(CanonicalChapter::baseNumber)
            .filter { it >= 0 }
        ObservedAddonCoverage(
            addonId = addonId,
            displayName = addonNames[addonId] ?: addonId,
            observedChapterCount = observed.size,
            firstKnownNumber = numbered.minOrNull(),
            lastKnownNumber = numbered.maxOrNull(),
        )
    }.sortedWith(compareBy(ObservedAddonCoverage::displayName, ObservedAddonCoverage::addonId))
}
