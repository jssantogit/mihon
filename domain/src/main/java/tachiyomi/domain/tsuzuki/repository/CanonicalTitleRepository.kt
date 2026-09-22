package tachiyomi.domain.tsuzuki.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.ExternalIdentity

interface CanonicalTitleRepository {
    suspend fun getById(id: String): CanonicalTitle?
    fun getByIdAsFlow(id: String): Flow<CanonicalTitle?>
    suspend fun getByExternalIdentity(provider: String, externalId: String): CanonicalTitle?
    suspend fun getExternalIdentities(canonicalTitleId: String): List<ExternalIdentity> = emptyList()
    suspend fun getOrCreateByExternalIdentity(
        title: CanonicalTitle,
        identity: ExternalIdentity,
    ): CanonicalTitle
    suspend fun insert(title: CanonicalTitle)

    suspend fun upsert(title: CanonicalTitle) {
        val existing = getById(title.id)
        if (existing == null) {
            insert(title)
        } else {
            require(existing == title) {
                "Repository does not support updating an existing canonical title"
            }
        }
    }

    suspend fun addExternalIdentity(identity: ExternalIdentity)
}
