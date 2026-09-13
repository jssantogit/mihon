package tachiyomi.domain.tsuzuki.model

data class ExternalIdentity(
    val canonicalTitleId: String,
    val provider: String,
    val externalId: String,
    val verified: Boolean,
    val createdAt: Long,
)
