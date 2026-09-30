package tachiyomi.data.tsuzuki

import app.cash.sqldelight.async.coroutines.awaitAsList
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.domain.tsuzuki.library.model.ExternalLibraryMembership
import tachiyomi.domain.tsuzuki.model.LibraryStatus
import tachiyomi.domain.tsuzuki.repository.ExternalLibraryRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ExternalLibraryRepositoryImpl(
    private val database: Database,
) : ExternalLibraryRepository {

    override fun observeAll(): Flow<List<ExternalLibraryMembership>> =
        database.tsuzuki_external_library_membershipsQueries
            .getAllTsuzukiExternalLibraryMemberships(::mapMembership)
            .subscribeToList()

    override suspend fun replaceProvider(
        provider: String,
        memberships: List<ExternalLibraryMembership>,
    ) {
        require(memberships.all { it.provider == provider }) {
            "Every external membership must belong to provider $provider"
        }

        database.transactionWithResult {
            database.tsuzuki_external_library_membershipsQueries
                .deleteTsuzukiExternalLibraryMembershipsByProvider(provider)
            memberships.forEach { membership ->
                database.tsuzuki_external_library_membershipsQueries
                    .upsertTsuzukiExternalLibraryMembership(
                        canonicalTitleId = membership.canonicalTitleId,
                        provider = membership.provider,
                        externalId = membership.externalId,
                        listKey = membership.listKey,
                        status = membership.status?.name,
                        remoteStatus = membership.remoteStatus,
                        progress = membership.progress,
                        score = membership.score,
                        syncedAt = membership.syncedAt,
                    )
            }
        }
    }

    override suspend fun clearProvider(provider: String) {
        database.tsuzuki_external_library_membershipsQueries
            .deleteTsuzukiExternalLibraryMembershipsByProvider(provider)
    }

    override suspend fun getProviderIds(): Set<String> =
        database.tsuzuki_external_library_membershipsQueries
            .getTsuzukiExternalLibraryProviderIds()
            .awaitAsList()
            .toSet()

    private fun mapMembership(
        canonicalTitleId: String,
        provider: String,
        externalId: String,
        listKey: String,
        status: String?,
        remoteStatus: String?,
        progress: Double?,
        score: Double?,
        syncedAt: Long,
    ) = ExternalLibraryMembership(
        canonicalTitleId = canonicalTitleId,
        provider = provider,
        externalId = externalId,
        listKey = listKey,
        status = status?.let(LibraryStatus::valueOf),
        remoteStatus = remoteStatus,
        progress = progress,
        score = score,
        syncedAt = syncedAt,
    )
}
