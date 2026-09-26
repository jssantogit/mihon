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
    ): CanonicalChapterCandidateResolution {
        if (candidates.isEmpty()) return CanonicalChapterCandidateResolution.NoMatch

        if (observedVolume == null) {
            if (hasExplicitVolumePrefix || candidates.any { it.volume != null }) {
                return CanonicalChapterCandidateResolution.Ambiguous
            }
            return candidates.singleOrNull()
                ?.let(CanonicalChapterCandidateResolution::UniqueMatch)
                ?: CanonicalChapterCandidateResolution.Ambiguous
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
