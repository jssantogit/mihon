package tachiyomi.domain.tsuzuki.source.service

import tachiyomi.domain.tsuzuki.source.model.MaterializedReadingSource
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceCandidate
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceDescriptor

interface ReadingSourceGateway {
    suspend fun listInstalled(language: String): List<ReadingSourceDescriptor>
    suspend fun search(sourceId: Long, query: String): Result<List<ReadingSourceCandidate>>
    suspend fun materialize(candidate: ReadingSourceCandidate): Result<MaterializedReadingSource>
}
