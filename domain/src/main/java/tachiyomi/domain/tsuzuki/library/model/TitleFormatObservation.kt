package tachiyomi.domain.tsuzuki.library.model

import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat

data class TitleFormatObservation(
    val canonicalTitleId: String,
    val provider: String,
    val format: CatalogItemFormat,
    val updatedAt: Long,
) {
    init {
        require(canonicalTitleId.isNotBlank()) { "Canonical title id cannot be blank" }
        require(provider.isNotBlank()) { "Format provider cannot be blank" }
    }
}
