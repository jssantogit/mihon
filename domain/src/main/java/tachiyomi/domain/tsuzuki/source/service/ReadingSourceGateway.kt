package tachiyomi.domain.tsuzuki.source.service

import tachiyomi.domain.tsuzuki.source.model.MaterializedReadingSource
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceCandidate
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceDescriptor

interface ReadingSourceGateway {
    suspend fun getAvailableSources(language: String): List<ReadingSourceDescriptor>
    suspend fun searchSource(sourceId: Long, query: String): Result<List<ReadingSourceCandidate>>
    suspend fun materializeSource(sourceId: Long, sourceUrl: String, title: String): Result<MaterializedReadingSource>
}
