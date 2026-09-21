package tachiyomi.domain.tsuzuki.sync.service

import tachiyomi.domain.tsuzuki.sync.model.SyncTransportResult

data class VerifiedCanonicalIdentity(
    val canonicalTitleId: String,
    val provider: String,
    val externalId: String,
) {
    init {
        require(canonicalTitleId.isNotBlank()) { "Canonical title ID must not be blank" }
        require(provider.isNotBlank()) { "Identity provider must not be blank" }
        require(externalId.isNotBlank()) { "External identity ID must not be blank" }
    }
}

interface CanonicalIdentityClaimTransport {
    suspend fun claim(
        provider: String,
        externalId: String,
        proposedCanonicalTitleId: String,
    ): SyncTransportResult<String>
}

interface CanonicalIdentitySyncRepository {
    suspend fun getVerifiedIdentities(): List<VerifiedCanonicalIdentity>
}

fun interface CanonicalTitleMergePort {
    suspend fun merge(
        targetId: String,
        localId: String,
    ): Result<Unit>
}
