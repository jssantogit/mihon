package tachiyomi.data.tsuzuki.integration

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.data.Database
import tachiyomi.domain.tsuzuki.integration.repository.CanonicalMetadataSnapshot
import tachiyomi.domain.tsuzuki.integration.repository.CanonicalMetadataSnapshotRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class CanonicalMetadataSnapshotRepositoryImpl(
    private val database: Database,
) : CanonicalMetadataSnapshotRepository {

    override suspend fun get(canonicalTitleId: String): CanonicalMetadataSnapshot? {
        val row = database.tsuzuki_metadata_snapshotQueries
            .getTsuzukiMetadataSnapshot(canonicalTitleId) {
                    titleId,
                    configurationFingerprint,
                    payloadJson,
                    refreshedAt,
                ->
                SnapshotRow(titleId, configurationFingerprint, payloadJson, refreshedAt)
            }
            .awaitAsOneOrNull()
            ?: return null
        return try {
            CanonicalMetadataSnapshot(
                canonicalTitleId = row.canonicalTitleId,
                configurationFingerprint = row.configurationFingerprint,
                metadata = CanonicalMetadataSnapshotJsonCodec.decode(row.payloadJson),
                refreshedAt = row.refreshedAt,
            )
        } catch (_: Exception) {
            invalidateTitle(canonicalTitleId)
            null
        }
    }

    override suspend fun upsertIfNewer(snapshot: CanonicalMetadataSnapshot): CanonicalMetadataSnapshot =
        database.transactionWithResult {
            val existing = get(snapshot.canonicalTitleId)
            if (existing != null && existing.refreshedAt > snapshot.refreshedAt) {
                return@transactionWithResult existing
            }
            database.tsuzuki_metadata_snapshotQueries.upsertTsuzukiMetadataSnapshot(
                canonicalTitleId = snapshot.canonicalTitleId,
                configurationFingerprint = snapshot.configurationFingerprint,
                payloadJson = CanonicalMetadataSnapshotJsonCodec.encode(snapshot.metadata),
                refreshedAt = snapshot.refreshedAt,
            )
            snapshot
        }

    override suspend fun invalidateTitle(canonicalTitleId: String) {
        database.tsuzuki_metadata_snapshotQueries.deleteTsuzukiMetadataSnapshot(canonicalTitleId)
    }

    private data class SnapshotRow(
        val canonicalTitleId: String,
        val configurationFingerprint: String,
        val payloadJson: String,
        val refreshedAt: Long,
    )
}
