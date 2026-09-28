package tachiyomi.domain.tsuzuki.chapter.interactor

import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter

internal sealed interface CanonicalChapterCandidateResolution {
    data class UniqueMatch(val chapter: CanonicalChapter) : CanonicalChapterCandidateResolution

    data object NoMatch : CanonicalChapterCandidateResolution

    data object Ambiguous : CanonicalChapterCandidateResolution
}

/** Applies the shared identity-and-volume policy to existing canonical candidates. */
internal object CanonicalChapterCandidateResolver {

    fun resolve(
        candidates: List<CanonicalChapter>,
        observedVolume: Int?,
        hasExplicitVolumePrefix: Boolean = false,
        allowUnqualifiedCandidateCreation: Boolean = false,
    ): CanonicalChapterCandidateResolution {
        if (candidates.isEmpty()) return CanonicalChapterCandidateResolution.NoMatch

        if (observedVolume == null) {
            if (hasExplicitVolumePrefix) {
                return CanonicalChapterCandidateResolution.Ambiguous
            }

            val unqualifiedCandidates = candidates.filter { it.volume == null }
            if (unqualifiedCandidates.size > 1) return CanonicalChapterCandidateResolution.Ambiguous
            if (unqualifiedCandidates.size == 1) {
                if (unqualifiedCandidates.size != candidates.size && !allowUnqualifiedCandidateCreation) {
                    return CanonicalChapterCandidateResolution.Ambiguous
                }
                return CanonicalChapterCandidateResolution.UniqueMatch(unqualifiedCandidates.single())
            }
            // Reliable Add-on inventory may omit volume metadata even when another
            // source supplied it. Reuse a sole candidate instead of duplicating the
            // same numbered chapter. Editorial/unqualified evidence remains fail-closed,
            // and multiple volume candidates are always ambiguous.
            return if (allowUnqualifiedCandidateCreation) {
                candidates.singleOrNull()
                    ?.let(CanonicalChapterCandidateResolution::UniqueMatch)
                    ?: CanonicalChapterCandidateResolution.Ambiguous
            } else {
                CanonicalChapterCandidateResolution.Ambiguous
            }
        }

        val matchingVolume = candidates.filter { it.volume == observedVolume }
        if (matchingVolume.size == 1) {
            return CanonicalChapterCandidateResolution.UniqueMatch(matchingVolume.single())
        }
        if (matchingVolume.size > 1) return CanonicalChapterCandidateResolution.Ambiguous

        if (candidates.any { it.volume != null }) return CanonicalChapterCandidateResolution.NoMatch
        return candidates.singleOrNull()
            ?.let(CanonicalChapterCandidateResolution::UniqueMatch)
            ?: CanonicalChapterCandidateResolution.Ambiguous
    }
}
