package tachiyomi.data.tsuzuki.download

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.data.Database
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.download.model.CanonicalDownloadArtifact
import tachiyomi.domain.tsuzuki.download.repository.CanonicalDownloadRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class CanonicalDownloadRepositoryImpl(
    private val database: Database,
) : CanonicalDownloadRepository {

    override suspend fun get(canonicalChapterId: String): CanonicalDownloadArtifact? {
        return database.tsuzuki_canonical_downloadsQueries
            .getTsuzukiCanonicalDownload(canonicalChapterId, ::mapArtifact)
            .awaitAsOneOrNull()
    }

    override suspend fun upsert(artifact: CanonicalDownloadArtifact) {
        database.tsuzuki_canonical_downloadsQueries.upsertTsuzukiCanonicalDownload(
            canonicalChapterId = artifact.canonicalChapterId,
            localUri = artifact.localUri,
            format = artifact.format,
            originatingAddonId = artifact.originatingAddonId?.value,
            originatingOptionKey = artifact.originatingOptionKey,
            completedAt = artifact.completedAt,
            checksum = artifact.checksum,
        )
    }

    override suspend fun delete(canonicalChapterId: String) {
        database.tsuzuki_canonical_downloadsQueries.deleteTsuzukiCanonicalDownload(canonicalChapterId)
    }

    override suspend fun deleteOriginMetadata(addonId: AddonId) {
        database.tsuzuki_canonical_downloadsQueries.clearTsuzukiCanonicalDownloadOrigin(addonId.value)
    }

    private fun mapArtifact(
        canonicalChapterId: String,
        localUri: String,
        format: String,
        originatingAddonId: String?,
        originatingOptionKey: String?,
        completedAt: Long,
        checksum: String?,
    ) = CanonicalDownloadArtifact(
        canonicalChapterId = canonicalChapterId,
        localUri = localUri,
        format = format,
        originatingAddonId = originatingAddonId?.let(::AddonId),
        originatingOptionKey = originatingOptionKey,
        completedAt = completedAt,
        checksum = checksum,
    )
}
