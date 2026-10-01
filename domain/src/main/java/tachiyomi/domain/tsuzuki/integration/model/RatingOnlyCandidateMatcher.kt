package tachiyomi.domain.tsuzuki.integration.model

import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem

/**
 * Resolves an ephemeral rating-only candidate without promoting it to canonical identity.
 *
 * Title equality alone is deliberately insufficient. A candidate must share an exact normalized
 * title/alias and also be corroborated by either publication year or creator identity. Ambiguous
 * matches fail closed.
 */
fun matchRatingOnlyCandidate(
    item: CatalogItem,
    candidates: List<CatalogItem>,
): CatalogItem? {
    val sourceTitles = item.ratingMatchTitles()
    if (sourceTitles.isEmpty()) return null

    val sourceYear = item.startDate.ratingMatchYear()
    val sourceCreators = (item.authors + item.artists)
        .mapNotNull(String::ratingMatchText)
        .toSet()

    return candidates
        .asSequence()
        .filter { candidate ->
            val candidateTitles = candidate.ratingMatchTitles()
            if (sourceTitles.intersect(candidateTitles).isEmpty()) return@filter false

            val yearMatches = sourceYear != null &&
                sourceYear == candidate.startDate.ratingMatchYear()
            val creatorMatches = sourceCreators.isNotEmpty() &&
                candidate.ratingMatchCreators().any(sourceCreators::contains)

            yearMatches || creatorMatches
        }
        .distinctBy { candidate -> candidate.provider to candidate.providerId }
        .toList()
        .singleOrNull()
}

private fun CatalogItem.ratingMatchTitles(): Set<String> = buildSet {
    title.ratingMatchText()?.let(::add)
    titles.values.mapNotNullTo(this, String::ratingMatchText)
}

private fun CatalogItem.ratingMatchCreators(): Set<String> =
    (authors + artists).mapNotNullTo(mutableSetOf(), String::ratingMatchText)

private fun String?.ratingMatchYear(): Int? =
    this
        ?.trim()
        ?.takeIf { it.length >= 4 }
        ?.take(4)
        ?.toIntOrNull()
        ?.takeIf { it in 1800..2200 }

private fun String.ratingMatchText(): String? =
    lowercase()
        .replace(RATING_MATCH_PUNCTUATION, " ")
        .trim()
        .replace(RATING_MATCH_WHITESPACE, " ")
        .takeIf(String::isNotBlank)

private val RATING_MATCH_PUNCTUATION = Regex("""[^\p{L}\p{N}]+""")
private val RATING_MATCH_WHITESPACE = Regex("""\s+""")
