package tachiyomi.data.tsuzuki.sync

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.Json
import tachiyomi.data.Database
import tachiyomi.domain.tsuzuki.sync.model.SupabaseMutationBatch
import tachiyomi.domain.tsuzuki.sync.model.SupabasePendingMutation
import tachiyomi.domain.tsuzuki.sync.model.SupabaseSyncCursor
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.service.CanonicalIdentitySyncRepository
import tachiyomi.domain.tsuzuki.sync.service.SupabaseSyncStateStore
import tachiyomi.domain.tsuzuki.sync.service.VerifiedCanonicalIdentity

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class SupabaseSyncStateRepository(
    private val database: Database,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) : SupabaseSyncStateStore {

    override suspend fun getCursor(documentKind: SyncDocumentKind): SupabaseSyncCursor? {
        return database.tsuzuki_supabase_syncQueries
            .getTsuzukiSupabaseSyncCursor(documentKind.name)
            .awaitAsOneOrNull()
            ?.let { row ->
                SupabaseSyncCursor(
                    documentKind = SyncDocumentKind.valueOf(row.domain),
                    eventCursor = row.event_cursor,
                    lastSuccessfulSyncAtEpochMillis = row.last_successful_sync_at,
                )
            }
    }

    override suspend fun putCursor(
        documentKind: SyncDocumentKind,
        eventCursor: Long,
        lastSuccessfulSyncAtEpochMillis: Long?,
    ) {
        database.tsuzuki_supabase_syncQueries.upsertTsuzukiSupabaseSyncCursor(
            domain = documentKind.name,
            eventCursor = eventCursor,
            lastSuccessfulSyncAt = lastSuccessfulSyncAtEpochMillis,
        )
    }

    override suspend fun getPending(
        documentKind: SyncDocumentKind,
    ): SupabasePendingMutation? {
        return database.tsuzuki_supabase_syncQueries
            .getTsuzukiSupabasePendingMutationByDomain(documentKind.name)
            .awaitAsOneOrNull()
            ?.let { row ->
                val batch = json.decodeFromString(
                    SupabaseMutationBatch.serializer(),
                    row.payload_json,
                )
                require(batch.mutationId == row.mutation_id) {
                    "Persisted Supabase mutation ID does not match its payload"
                }
                require(batch.domain == row.domain) {
                    "Persisted Supabase mutation domain does not match its payload"
                }
                require(batch.baseCursor == row.base_cursor) {
                    "Persisted Supabase mutation cursor does not match its payload"
                }
                SupabasePendingMutation(
                    batch = batch,
                    createdAtEpochMillis = row.created_at,
                    attemptCount = row.attempt_count.toInt(),
                    nextAttemptAtEpochMillis = row.next_attempt_at,
                )
            }
    }

    override suspend fun putPending(mutation: SupabasePendingMutation) {
        persistPending(mutation)
    }

    override suspend fun recordPendingFailure(
        mutation: SupabasePendingMutation,
        nextAttemptAtEpochMillis: Long?,
    ) {
        persistPending(
            mutation.copy(
                attemptCount = mutation.attemptCount + 1,
                nextAttemptAtEpochMillis = nextAttemptAtEpochMillis,
            ),
        )
    }

    override suspend fun deletePending(mutationId: String) {
        database.tsuzuki_supabase_syncQueries
            .deleteTsuzukiSupabasePendingMutation(mutationId)
    }

    private suspend fun persistPending(mutation: SupabasePendingMutation) {
        val batch = mutation.batch
        database.tsuzuki_supabase_syncQueries.upsertTsuzukiSupabasePendingMutation(
            mutationId = batch.mutationId,
            domain = batch.domain,
            baseCursor = batch.baseCursor,
            payloadJson = json.encodeToString(
                SupabaseMutationBatch.serializer(),
                batch,
            ),
            createdAt = mutation.createdAtEpochMillis,
            attemptCount = mutation.attemptCount.toLong(),
            nextAttemptAt = mutation.nextAttemptAtEpochMillis,
        )
    }
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class SqlDelightCanonicalIdentitySyncRepository(
    private val database: Database,
) : CanonicalIdentitySyncRepository {

    override suspend fun getVerifiedIdentities(): List<VerifiedCanonicalIdentity> {
        return database.tsuzuki_external_identitiesQueries
            .getVerifiedTsuzukiExternalIdentities { canonicalTitleId, provider, externalId ->
                VerifiedCanonicalIdentity(
                    canonicalTitleId = canonicalTitleId,
                    provider = provider,
                    externalId = externalId,
                )
            }
            .awaitAsList()
    }
}
