package tachiyomi.domain.tsuzuki.catalog.model

import tachiyomi.domain.tsuzuki.integration.model.TsuzukiRating

data class CatalogItem(
    val provider: String,
    val providerId: String,
    val title: String,
    val titles: Map<String, String> = emptyMap(),
    val synopsis: String? = null,
    val coverUrl: String? = null,
    val bannerUrl: String? = null,
    val status: CatalogItemStatus = CatalogItemStatus.UNKNOWN,
    val format: CatalogItemFormat = CatalogItemFormat.UNKNOWN,
    val score: CatalogScore? = null,
    /**
     * Provider-published identity mappings only. Callers must never populate this from title
     * similarity or fuzzy matching.
     */
    val externalIds: Map<String, String> = emptyMap(),
    val scores: List<CatalogScore> = score?.let { listOf(it) }.orEmpty(),
    val tsuzukiRating: TsuzukiRating? = null,
    val authors: List<String> = emptyList(),
    val artists: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val publishers: List<String> = emptyList(),
    val magazines: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val demographics: List<String> = emptyList(),
    val country: String? = null,
    val popularity: Long? = null,
    val favorites: Long? = null,
    val rank: Double? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val chapterCount: Int? = null,
    val volumeCount: Int? = null,
)

/**
 * Collapses catalog rows only when they share an exact provider/external-id identity.
 *
 * A cross-provider collapse therefore requires a provider-published mapping in [CatalogItem.externalIds].
 * Equal titles alone are intentionally never enough.
 */
fun mergeCatalogItemsByVerifiedIdentity(items: List<CatalogItem>): List<CatalogItem> {
    val groups = mutableListOf<MutableList<CatalogItem>>()

    items.forEach { item ->
        val itemKeys = item.identityKeys()
        val matching = groups.withIndex()
            .filter { (_, group) ->
                group.any { existing -> existing.identityKeys().any(itemKeys::contains) }
            }
            .map { it.index }

        if (matching.isEmpty()) {
            groups += mutableListOf(item)
        } else {
            val targetIndex = matching.first()
            val target = groups[targetIndex]
            target += item
            matching.drop(1).sortedDescending().forEach { index ->
                target += groups[index]
                groups.removeAt(index)
            }
        }
    }

    return groups.map { group ->
        val representative = group.first()
        val scores = group
            .flatMap { candidate ->
                candidate.scores.ifEmpty { listOfNotNull(candidate.score) }
            }
            .distinctBy(CatalogScore::provider)
        val externalIds = buildMap {
            group.forEach { candidate ->
                put(candidate.provider, candidate.providerId)
                putAll(candidate.externalIds)
            }
        }

        representative.copy(
            score = scores.firstOrNull(),
            externalIds = externalIds,
            scores = scores,
        )
    }
}

private fun CatalogItem.identityKeys(): Set<Pair<String, String>> = buildSet {
    add(provider to providerId)
    externalIds.forEach { (providerId, externalId) ->
        if (providerId.isNotBlank() && externalId.isNotBlank()) {
            add(providerId to externalId)
        }
    }
}
