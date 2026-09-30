package tachiyomi.domain.tsuzuki.library.model

import tachiyomi.domain.tsuzuki.model.LibraryStatus

data class ExternalLibraryMembership(
    val canonicalTitleId: String,
    val provider: String,
    val externalId: String,
    val listKey: String,
    val status: LibraryStatus?,
    val remoteStatus: String?,
    val progress: Double?,
    val score: Double?,
    val listedAt: Long?,
    val syncedAt: Long,
) {
    init {
        require(canonicalTitleId.isNotBlank()) { "Canonical title id cannot be blank" }
        require(provider.isNotBlank()) { "External library provider cannot be blank" }
        require(externalId.isNotBlank()) { "External library id cannot be blank" }
        require(listKey.isNotBlank()) { "External library list key cannot be blank" }
    }
}
