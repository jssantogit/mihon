package tachiyomi.domain.tsuzuki.catalog.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemStatus
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
import tachiyomi.domain.tsuzuki.catalog.model.CatalogSort
import tachiyomi.domain.tsuzuki.catalog.service.CatalogProvider
import java.text.Normalizer
import kotlin.coroutines.cancellation.CancellationException

@Inject
class SearchCatalog(
    private val catalogProvider: CatalogProvider,
) {

    suspend fun await(
        query: String? = null,
        sort: CatalogSort = CatalogSort.POPULARITY_DESC,
        offset: Int = 0,
        limit: Int = 20,
        genres: List<String> = emptyList(),
        status: CatalogItemStatus? = null,
    ): Result<CatalogPage> = try {
        catalogProvider.search(
            CatalogQuery(
                query = query,
                sort = sort,
                genres = genres,
                status = status,
                offset = offset,
                limit = limit,
            ),
        ).map { page ->
            page.filterByTitleRelevance(query)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

    suspend operator fun invoke(
        query: String? = null,
        sort: CatalogSort = CatalogSort.POPULARITY_DESC,
        offset: Int = 0,
        limit: Int = 20,
        genres: List<String> = emptyList(),
        status: CatalogItemStatus? = null,
    ): Result<CatalogPage> = await(
        query = query,
        sort = sort,
        offset = offset,
        limit = limit,
        genres = genres,
        status = status,
    )

    suspend fun execute(
        query: String? = null,
        offset: Int = 0,
        limit: Int = 20,
        sort: CatalogSort = CatalogSort.POPULARITY_DESC,
    ): Result<CatalogPage> = await(
        query = query,
        sort = sort,
        offset = offset,
        limit = limit,
    )

    private fun CatalogPage.filterByTitleRelevance(query: String?): CatalogPage {
        val normalizedQuery = normalizeSearchText(query.orEmpty())
        if (normalizedQuery.isBlank()) return this

        val filteredItems = items.filter { item ->
            item.matchesTitleQuery(normalizedQuery)
        }

        if (filteredItems.size == items.size) return this

        return copy(
            items = filteredItems,
            totalCount = null,
        )
    }

    private fun CatalogItem.matchesTitleQuery(normalizedQuery: String): Boolean {
        val candidates = buildList {
            add(title)
            addAll(titles.values)
        }

        return candidates.any { candidate ->
            isRelevantTitle(
                query = normalizedQuery,
                candidate = normalizeSearchText(candidate),
            )
        }
    }

    private fun isRelevantTitle(query: String, candidate: String): Boolean {
        if (candidate.isBlank()) return false
        if (candidate == query) return true

        if (query.length >= MIN_SUBSTRING_LENGTH && candidate.contains(query)) {
            return true
        }
        if (
            query.length >= MIN_SUBSTRING_LENGTH &&
            candidate.length >= MIN_SUBSTRING_LENGTH &&
            query.contains(candidate)
        ) {
            return true
        }

        val queryTokens = query.split(' ').filter(String::isNotBlank)
        val candidateTokens = candidate.split(' ').filter(String::isNotBlank)

        if (queryTokens.isEmpty() || candidateTokens.isEmpty()) return false

        return queryTokens.all { queryToken ->
            candidateTokens.any { candidateToken ->
                tokensMatch(queryToken, candidateToken)
            }
        }
    }

    private fun tokensMatch(queryToken: String, candidateToken: String): Boolean {
        if (queryToken == candidateToken) return true

        if (
            queryToken.length >= MIN_PREFIX_LENGTH &&
            candidateToken.length >= MIN_PREFIX_LENGTH &&
            (candidateToken.startsWith(queryToken) || queryToken.startsWith(candidateToken))
        ) {
            return true
        }

        val shortestLength = minOf(queryToken.length, candidateToken.length)
        if (shortestLength < MIN_TYPO_LENGTH) return false

        val longestLength = maxOf(queryToken.length, candidateToken.length)
        val threshold = if (longestLength <= SHORT_TOKEN_MAX_LENGTH) {
            SHORT_TOKEN_SIMILARITY
        } else {
            LONG_TOKEN_SIMILARITY
        }

        return similarity(queryToken, candidateToken) >= threshold
    }

    private fun similarity(left: String, right: String): Double {
        val longestLength = maxOf(left.length, right.length)
        if (longestLength == 0) return 1.0

        return 1.0 - levenshteinDistance(left, right).toDouble() / longestLength
    }

    private fun levenshteinDistance(left: String, right: String): Int {
        if (left == right) return 0
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length

        var previous = IntArray(right.length + 1) { it }

        for (leftIndex in 1..left.length) {
            val current = IntArray(right.length + 1)
            current[0] = leftIndex

            for (rightIndex in 1..right.length) {
                val substitutionCost = if (left[leftIndex - 1] == right[rightIndex - 1]) 0 else 1
                current[rightIndex] = minOf(
                    current[rightIndex - 1] + 1,
                    previous[rightIndex] + 1,
                    previous[rightIndex - 1] + substitutionCost,
                )
            }

            previous = current
        }

        return previous[right.length]
    }

    private fun normalizeSearchText(value: String): String {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS_REGEX, "")
            .lowercase()
            .replace(NON_ALPHANUMERIC_REGEX, " ")
            .trim()
            .replace(WHITESPACE_REGEX, " ")
    }

    private companion object {
        const val MIN_SUBSTRING_LENGTH = 4
        const val MIN_PREFIX_LENGTH = 3
        const val MIN_TYPO_LENGTH = 4
        const val SHORT_TOKEN_MAX_LENGTH = 6
        const val SHORT_TOKEN_SIMILARITY = 0.80
        const val LONG_TOKEN_SIMILARITY = 0.75

        val COMBINING_MARKS_REGEX = Regex("""\p{M}+""")
        val NON_ALPHANUMERIC_REGEX = Regex("""[^\p{L}\p{N}]+""")
        val WHITESPACE_REGEX = Regex("""\s+""")
    }
}
