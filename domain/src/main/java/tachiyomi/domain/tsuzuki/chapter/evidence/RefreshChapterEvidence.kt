package tachiyomi.domain.tsuzuki.chapter.evidence

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import tachiyomi.domain.tsuzuki.addon.AddonRegistry
import tachiyomi.domain.tsuzuki.content.interactor.ResolveContentBinding
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry

class RefreshChapterEvidence private constructor(
    private val registry: IntegrationRegistry,
    private val reconcileChapterEvidence: ReconcileChapterEvidence,
    private val addonRegistry: AddonRegistry?,
    private val resolveContentBinding: ResolveContentBinding?,
    @Suppress("UNUSED_PARAMETER") constructorMarker: Unit,
) {

    @Inject
    constructor(
        registry: IntegrationRegistry,
        reconcileChapterEvidence: ReconcileChapterEvidence,
        addonRegistry: AddonRegistry,
        resolveContentBinding: ResolveContentBinding,
    ) : this(
        registry = registry,
        reconcileChapterEvidence = reconcileChapterEvidence,
        addonRegistry = addonRegistry,
        resolveContentBinding = resolveContentBinding,
        constructorMarker = Unit,
    )

    constructor(
        registry: IntegrationRegistry,
        reconcileChapterEvidence: ReconcileChapterEvidence,
    ) : this(
        registry = registry,
        reconcileChapterEvidence = reconcileChapterEvidence,
        addonRegistry = null,
        resolveContentBinding = null,
        constructorMarker = Unit,
    )

    suspend fun execute(canonicalTitleId: String): Result<Unit> {
        return try {
            val integrationEvidence = collectIntegrationEvidence(canonicalTitleId)
            val addonEvidence = collectAddonEvidence(canonicalTitleId)
            reconcileChapterEvidence.execute(
                canonicalTitleId,
                (integrationEvidence + addonEvidence).distinctBy(ChapterEvidence::id),
            )
            Result.success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private suspend fun collectIntegrationEvidence(
        canonicalTitleId: String,
    ): List<ChapterEvidence> = coroutineScope {
        registry.chapterEvidenceProviders()
            .map { provider ->
                async {
                    try {
                        provider.evidenceFor(canonicalTitleId)
                            .fold(
                                onSuccess = { observations ->
                                    observations.takeIf {
                                        it.all { observation ->
                                            observation.canonicalTitleId == canonicalTitleId
                                        }
                                    }.orEmpty()
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

    private suspend fun collectAddonEvidence(
        canonicalTitleId: String,
    ): List<ChapterEvidence> {
        val addonRegistry = addonRegistry ?: return emptyList()
        val resolver = resolveContentBinding ?: return emptyList()
        addonRegistry.awaitReady()

        return coroutineScope {
            addonRegistry.chapterProbeProviders()
                .map { provider ->
                    async {
                        try {
                            val bindings = resolver
                                .executeAll(canonicalTitleId, provider.addonId)
                                .getOrElse { error ->
                                    if (error is CancellationException) throw error
                                    return@async emptyList()
                                }
                            if (bindings.isEmpty()) return@async emptyList()

                            provider.probe(canonicalTitleId)
                                .fold(
                                    onSuccess = { observations ->
                                        observations.takeIf {
                                            it.all { observation ->
                                                observation.canonicalTitleId == canonicalTitleId
                                            }
                                        }.orEmpty()
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
