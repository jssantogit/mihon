package tachiyomi.data.tsuzuki

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.domain.tsuzuki.model.SourceMappingAvailability
import tachiyomi.domain.tsuzuki.model.SourceTitleMapping
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class SourceTitleMappingRepositoryImpl(
    private val database: Database,
) : SourceTitleMappingRepository {

    override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<SourceTitleMapping> {
        return database.tsuzuki_source_mappingsQueries
            .getTsuzukiSourceMappingsByTitle(canonicalTitleId, ::mapMapping)
            .awaitAsList()
    }

    override fun getByCanonicalTitleIdAsFlow(canonicalTitleId: String): Flow<List<SourceTitleMapping>> {
        return database.tsuzuki_source_mappingsQueries
            .getTsuzukiSourceMappingsByTitle(canonicalTitleId, ::mapMapping)
            .subscribeToList()
    }

    override suspend fun getBySource(sourceId: Long, sourceUrl: String): SourceTitleMapping? {
        return database.tsuzuki_source_mappingsQueries
            .getTsuzukiSourceMapping(sourceId, sourceUrl, ::mapMapping)
            .awaitAsOneOrNull()
    }

    override suspend fun upsert(mapping: SourceTitleMapping) {
        database.tsuzuki_source_mappingsQueries.upsertTsuzukiSourceMapping(
            id = mapping.id,
            canonicalTitleId = mapping.canonicalTitleId,
            mihonMangaId = mapping.mihonMangaId,
            sourceId = mapping.sourceId,
            sourceUrl = mapping.sourceUrl,
            language = mapping.language,
            matchConfidence = mapping.matchConfidence,
            verifiedByUser = mapping.verifiedByUser,
            availability = mapping.availability.name,
            preferredOverride = mapping.preferredOverride,
            createdAt = mapping.createdAt,
            updatedAt = mapping.updatedAt,
        )
    }

    override suspend fun setPreferredForTitle(canonicalTitleId: String, mappingId: String?, updatedAt: Long) {
        database.transaction {
            if (mappingId != null) {
                val mapping = database.tsuzuki_source_mappingsQueries
                    .getTsuzukiSourceMappingById(mappingId, ::mapMapping)
                    .awaitAsOneOrNull()
                    ?: throw IllegalArgumentException("Mapping $mappingId not found")
                require(mapping.canonicalTitleId == canonicalTitleId) {
                    "Mapping $mappingId does not belong to canonical title $canonicalTitleId"
                }
                database.tsuzuki_source_mappingsQueries.clearOtherPreferredForTitle(
                    canonicalTitleId = canonicalTitleId,
                    excludeId = mappingId,
                    updatedAt = updatedAt,
                )
                database.tsuzuki_source_mappingsQueries.setPreferredMapping(
                    id = mappingId,
                    canonicalTitleId = canonicalTitleId,
                    updatedAt = updatedAt,
                )
            } else {
                database.tsuzuki_source_mappingsQueries.clearPreferredForTitle(
                    canonicalTitleId = canonicalTitleId,
                    updatedAt = updatedAt,
                )
            }
        }
    }

    private fun mapMapping(
        id: String,
        canonicalTitleId: String,
        mihonMangaId: Long?,
        sourceId: Long,
        sourceUrl: String,
        language: String,
        matchConfidence: Double?,
        verifiedByUser: Boolean,
        availability: String,
        preferredOverride: Boolean,
        createdAt: Long,
        updatedAt: Long,
    ) = SourceTitleMapping(
        id = id,
        canonicalTitleId = canonicalTitleId,
        mihonMangaId = mihonMangaId,
        sourceId = sourceId,
        sourceUrl = sourceUrl,
        language = language,
        matchConfidence = matchConfidence,
        verifiedByUser = verifiedByUser,
        availability = SourceMappingAvailability.valueOf(availability),
        preferredOverride = preferredOverride,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
