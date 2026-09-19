package tachiyomi.domain.tsuzuki.source.model

data class ScoredSourceCandidate(
    val candidate: ReadingSourceCandidate,
    val confidence: Double,
    val sourcePreferenceRank: Int,
)
