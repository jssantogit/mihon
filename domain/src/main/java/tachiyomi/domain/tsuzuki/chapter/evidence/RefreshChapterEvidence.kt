package tachiyomi.domain.tsuzuki.chapter.evidence

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry

@Inject
class RefreshChapterEvidence(
    private val registry: IntegrationRegistry,
    private val reconcileChapterEvidence: ReconcileChapterEvidence,
) {

    suspend fun execute(canonicalTitleId: String): Result<Unit> {
        return try {
            val evidence = coroutineScope {
                registry.chapterEvidenceProviders()
                    .map { provider ->
                        async {
                            try {
                                provider.evidenceFor(canonicalTitleId)
                                    .fold(
                                        onSuccess = { observations ->
                                            if (observations.all { it.canonicalTitleId == canonicalTitleId }) {
                                                observations
                                            } else {
                                                emptyList()
                                            }
                                        },
                                        onFailure = { error ->
                                            if (error is CancellationException) throw error
                                            emptyList()
                                        },
                                    )
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Throwable) {
                                emptyList()
                            }
                        }
                    }
                    .awaitAll()
                    .flatten()
            }
            reconcileChapterEvidence.execute(canonicalTitleId, evidence)
            Result.success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    /**
     * Entry point for non-Integration producers, such as Add-ons, to submit
     * provisional observations without invoking Integration providers.
     */
    suspend fun submit(
        canonicalTitleId: String,
        evidence: List<ChapterEvidence>,
    ): Result<Unit> {
        return try {
            reconcileChapterEvidence.execute(canonicalTitleId, evidence)
            Result.success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }
}
