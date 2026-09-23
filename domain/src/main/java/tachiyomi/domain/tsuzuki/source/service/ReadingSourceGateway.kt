package tachiyomi.domain.tsuzuki.source.service

import tachiyomi.domain.tsuzuki.source.model.MaterializedReadingSource
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceCandidate
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceDescriptor

interface ReadingSourceGateway {
    suspend fun listInstalled(language: String): List<ReadingSourceDescriptor>
    suspend fun search(sourceId: Long, query: String): Result<List<ReadingSourceCandidate>>
    suspend fun materialize(candidate: ReadingSourceCandidate): Result<MaterializedReadingSource>
}

/** Structured, safe failure category for an installed reading source. */
enum class ReadingSourceSearchFailure {
    SOURCE_DISABLED,
    SOURCE_NOT_INSTALLED,
    CHALLENGE_REQUIRED,
    NETWORK_ERROR,
    EXTENSION_ERROR,
}

/** The provider exception is a cause only; export diagnostics must retain only [kind]. */
class ReadingSourceSearchException(
    val kind: ReadingSourceSearchFailure,
    cause: Throwable? = null,
) : IllegalStateException("Reading source search failed: ${kind.name}", cause)
