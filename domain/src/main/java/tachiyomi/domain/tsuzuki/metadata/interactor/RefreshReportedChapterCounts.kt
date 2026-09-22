package tachiyomi.domain.tsuzuki.metadata.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.metadata.ReportedChapterCount
import tachiyomi.domain.tsuzuki.metadata.repository.ReportedChapterCountRepository
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import kotlin.time.Clock

@Inject
class RefreshReportedChapterCounts(
    private val canonicalTitleRepository: CanonicalTitleRepository,
    private val registry: IntegrationRegistry,
    private val repository: ReportedChapterCountRepository,
) {

    suspend fun execute(canonicalTitleId: String): Result<Unit> {
        return try {
            registry.awaitReady()
            val providers = registry.metadataProviders().associateBy { it.integrationId.value }
            val identities = canonicalTitleRepository.getExternalIdentities(canonicalTitleId)

            coroutineScope {
                identities.mapNotNull { identity ->
                    val provider = providers[identity.provider] ?: return@mapNotNull null
                    async {
                        val item = provider.getDetails(identity.externalId).getOrElse { error ->
                            if (error is CancellationException) throw error
                            return@async
                        }
                        repository.upsert(
                            ReportedChapterCount(
                                canonicalTitleId = canonicalTitleId,
                                provider = identity.provider,
                                chapterCount = item.chapterCount,
                                updatedAt = Clock.System.now().toEpochMilliseconds(),
                            ),
                        )
                    }
                }.awaitAll()
            }
            Result.success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }
}
