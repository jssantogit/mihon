package tachiyomi.domain.tsuzuki.collections.model

import tachiyomi.domain.tsuzuki.catalog.model.CatalogSort

sealed interface CollectionSortKey {
    val stableId: String

    enum class Standard(
        override val stableId: String,
    ) : CollectionSortKey {
        POPULARITY("standard.popularity"),
        RATING("standard.rating"),
        UPDATED("standard.updated"),
        RELEVANCE("standard.relevance"),
    }

    data class Provider(
        val providerId: String,
        val nativeId: String,
    ) : CollectionSortKey {
        init {
            require(providerId.isNotBlank()) { "Collection sort providerId cannot be blank" }
            require(nativeId.isNotBlank()) { "Collection sort nativeId cannot be blank" }
            require('.' !in providerId) { "Collection sort providerId cannot contain dots" }
        }

        override val stableId: String = "provider.$providerId.$nativeId"
    }

    companion object {
        fun fromStableId(stableId: String): CollectionSortKey {
            Standard.entries.firstOrNull { it.stableId == stableId }?.let { return it }

            val parts = stableId.split('.', limit = 3)
            require(parts.size == 3 && parts[0] == "provider") {
                "Unsupported Collection sort key: $stableId"
            }
            return Provider(
                providerId = parts[1],
                nativeId = parts[2],
            )
        }
    }
}

enum class CollectionSortDirection {
    ASC,
    DESC,
}

data class CollectionSortSelection(
    val key: CollectionSortKey,
    val direction: CollectionSortDirection? = null,
) {
    val stableKey: String
        get() = key.stableId

    val cacheKey: String
        get() = buildString {
            append(key.stableId)
            append('|')
            append(direction?.name ?: "FIXED")
        }

    companion object {
        val DEFAULT: CollectionSortSelection = CollectionSortSelection(
            key = CollectionSortKey.Standard.POPULARITY,
            direction = CollectionSortDirection.DESC,
        )

        fun fromLegacy(sort: CatalogSort): CollectionSortSelection = when (sort) {
            CatalogSort.POPULARITY_DESC -> CollectionSortSelection(
                CollectionSortKey.Standard.POPULARITY,
                CollectionSortDirection.DESC,
            )
            CatalogSort.POPULARITY_ASC -> CollectionSortSelection(
                CollectionSortKey.Standard.POPULARITY,
                CollectionSortDirection.ASC,
            )
            CatalogSort.RATING_DESC -> CollectionSortSelection(
                CollectionSortKey.Standard.RATING,
                CollectionSortDirection.DESC,
            )
            CatalogSort.RATING_ASC -> CollectionSortSelection(
                CollectionSortKey.Standard.RATING,
                CollectionSortDirection.ASC,
            )
            CatalogSort.UPDATED_DESC -> CollectionSortSelection(
                CollectionSortKey.Standard.UPDATED,
                CollectionSortDirection.DESC,
            )
            CatalogSort.RELEVANCE -> CollectionSortSelection(
                CollectionSortKey.Standard.RELEVANCE,
                direction = null,
            )
        }

        fun fromStorage(
            stableKey: String,
            direction: String?,
        ): CollectionSortSelection {
            val legacy = runCatching { CatalogSort.valueOf(stableKey) }.getOrNull()
            if (legacy != null) return fromLegacy(legacy)

            return CollectionSortSelection(
                key = CollectionSortKey.fromStableId(stableKey),
                direction = direction?.let(CollectionSortDirection::valueOf),
            )
        }
    }
}

fun CollectionSortSelection.toLegacyCatalogSortOrNull(): CatalogSort? = when (key) {
    CollectionSortKey.Standard.POPULARITY -> when (direction) {
        CollectionSortDirection.ASC -> CatalogSort.POPULARITY_ASC
        CollectionSortDirection.DESC -> CatalogSort.POPULARITY_DESC
        null -> null
    }
    CollectionSortKey.Standard.RATING -> when (direction) {
        CollectionSortDirection.ASC -> CatalogSort.RATING_ASC
        CollectionSortDirection.DESC -> CatalogSort.RATING_DESC
        null -> null
    }
    CollectionSortKey.Standard.UPDATED -> when (direction) {
        CollectionSortDirection.DESC -> CatalogSort.UPDATED_DESC
        CollectionSortDirection.ASC,
        null,
        -> null
    }
    CollectionSortKey.Standard.RELEVANCE -> if (direction == null) CatalogSort.RELEVANCE else null
    is CollectionSortKey.Provider -> null
}

enum class SortDirectionMode {
    ASC_DESC,
    ASC_ONLY,
    DESC_ONLY,
    FIXED_NATIVE,
}
