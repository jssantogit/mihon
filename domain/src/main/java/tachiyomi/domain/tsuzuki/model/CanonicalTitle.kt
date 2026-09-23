package tachiyomi.domain.tsuzuki.model

data class CanonicalTitle(
    val id: String,
    val displayTitle: String,
    val identityState: CanonicalIdentityState,
    val createdAt: Long,
    val updatedAt: Long,
)
