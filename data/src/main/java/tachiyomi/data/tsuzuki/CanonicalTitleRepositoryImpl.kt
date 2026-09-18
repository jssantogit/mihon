package tachiyomi.data.tsuzuki

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import tachiyomi.data.Database
import tachiyomi.data.subscribeToOneOrNull
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class CanonicalTitleRepositoryImpl(
    private val database: Database,
) : CanonicalTitleRepository {

    override suspend fun getById(id: String): CanonicalTitle? {
        return database.tsuzuki_titlesQueries
            .getTsuzukiTitleById(id, ::mapTitle)
            .awaitAsOneOrNull()
    }

    override fun getByIdAsFlow(id: String): Flow<CanonicalTitle?> {
        return database.tsuzuki_titlesQueries
            .getTsuzukiTitleById(id, ::mapTitle)
            .subscribeToOneOrNull()
    }

    override suspend fun getByExternalIdentity(provider: String, externalId: String): CanonicalTitle? {
        val titleId = database.tsuzuki_external_identitiesQueries
            .getTsuzukiTitleIdByExternalIdentity(provider, externalId)
            .awaitAsOneOrNull()
            ?: return null
        return getById(titleId)
    }

    override suspend fun getOrCreateByExternalIdentity(
        title: CanonicalTitle,
        identity: ExternalIdentity,
    ): CanonicalTitle {
        require(identity.canonicalTitleId == title.id) {
            "External identity must reference the candidate canonical title"
        }

        return try {
            database.transactionWithResult {
                val existingTitleId = database.tsuzuki_external_identitiesQueries
                    .getTsuzukiTitleIdByExternalIdentity(identity.provider, identity.externalId)
                    .awaitAsOneOrNull()

                if (existingTitleId != null) {
                    database.tsuzuki_titlesQueries
                        .getTsuzukiTitleById(existingTitleId, ::mapTitle)
                        .awaitAsOneOrNull()
                        ?: error("External identity points to a missing canonical title")
                } else {
                    database.tsuzuki_titlesQueries.insertTsuzukiTitle(
                        id = title.id,
                        displayTitle = title.displayTitle,
                        identityState = title.identityState.name,
                        createdAt = title.createdAt,
                        updatedAt = title.updatedAt,
                    )
                    database.tsuzuki_external_identitiesQueries.insertTsuzukiExternalIdentity(
                        canonicalTitleId = identity.canonicalTitleId,
                        provider = identity.provider,
                        externalId = identity.externalId,
                        verified = identity.verified,
                        createdAt = identity.createdAt,
                    )
                    title
                }
            }
        } catch (e: Exception) {
            getByExternalIdentity(identity.provider, identity.externalId)?.let { return it }
            throw e
        }
    }

    override suspend fun insert(title: CanonicalTitle) {
        database.tsuzuki_titlesQueries.insertTsuzukiTitle(
            id = title.id,
            displayTitle = title.displayTitle,
            identityState = title.identityState.name,
            createdAt = title.createdAt,
            updatedAt = title.updatedAt,
        )
    }

    override suspend fun addExternalIdentity(identity: ExternalIdentity) {
        database.tsuzuki_external_identitiesQueries.insertTsuzukiExternalIdentity(
            canonicalTitleId = identity.canonicalTitleId,
            provider = identity.provider,
            externalId = identity.externalId,
            verified = identity.verified,
            createdAt = identity.createdAt,
        )
    }

    private fun mapTitle(
        id: String,
        displayTitle: String,
        identityState: String,
        createdAt: Long,
        updatedAt: Long,
    ) = CanonicalTitle(
        id = id,
        displayTitle = displayTitle,
        identityState = CanonicalIdentityState.valueOf(identityState),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
