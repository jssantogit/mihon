package tachiyomi.domain.tsuzuki.content.interactor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.addon.model.InstalledAddon
import tachiyomi.domain.tsuzuki.addon.repository.AddonSourceEligibility
import tachiyomi.domain.tsuzuki.content.ContentBinding
import tachiyomi.domain.tsuzuki.content.ContentBindingAvailability
import tachiyomi.domain.tsuzuki.content.ContentOption
import tachiyomi.domain.tsuzuki.content.ContentPreference
import tachiyomi.domain.tsuzuki.source.model.ScoredSourceCandidate
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

data class InitialDiscoveryBudget(
    val totalMillis: Long = 10_000L,
    val existingLookupMillis: Long = 2_000L,
    val sourceTimeoutMillis: Long = 4_000L,
    val maxAddons: Int = PlanFastReadingDiscovery.MAX_INITIAL_ADDONS,
    val maxQueries: Int = PlanFastReadingDiscovery.MAX_INITIAL_QUERIES,
) {
    init {
        require(totalMillis in 1L..60_000L)
        require(existingLookupMillis in 1L..totalMillis)
        require(sourceTimeoutMillis in 1L..ContentBindingSearchRequest.MAX_SOURCE_TIMEOUT_MILLIS)
        require(maxAddons in 1..PlanFastReadingDiscovery.MAX_INITIAL_ADDONS)
        require(maxQueries in 1..PlanFastReadingDiscovery.MAX_INITIAL_QUERIES)
    }
}

enum class FastDiscoveryFailureStage {
    INITIAL_LOOKUP,
    ELIGIBILITY,
    SEARCH,
    EVIDENCE_REFRESH,
    CHAPTER_LOOKUP,
}

enum class FastDiscoveryCompletion {
    FOUND,
    EXHAUSTED,
    CONFIRMATION_REQUIRED,
    NO_ELIGIBLE_SOURCES,
    TIME_BUDGET,
}

sealed interface FastReadingDiscoveryEvent {
    data class Ready(
        val options: List<ContentOption>,
        val alreadyAvailable: Boolean,
    ) : FastReadingDiscoveryEvent

    data class Searching(
        val targets: List<PlannedAddonSearch>,
    ) : FastReadingDiscoveryEvent

    data class ConfirmationRequired(
        val addonId: AddonId,
        val candidates: List<ScoredSourceCandidate>,
    ) : FastReadingDiscoveryEvent

    data class SourceFailed(
        val addonId: AddonId?,
        val sourceId: Long?,
        val stage: FastDiscoveryFailureStage,
        val searchFailure: ContentBindingSearchFailure? = null,
    ) : FastReadingDiscoveryEvent

    data class Completed(
        val reason: FastDiscoveryCompletion,
        val queriedSourceIds: Map<AddonId, Set<Long>>,
    ) : FastReadingDiscoveryEvent
}

/**
 * Cold, user-relevant chapter discovery. Not connected to the UI until a targeted
 * per-binding refresh implementation is available: the injected refresh callback
 * MUST NOT refresh every stored binding in a multilingual extension.
 *
 * All provider work is off the caller thread. Timeouts are cooperative; a
 * non-interruptible third-party Java invocation still needs executor isolation.
 */
class DiscoverReadableChapter internal constructor(
    private val lookupExisting: suspend (String, String) -> ContentOptionLookup,
    private val lookupAfterBinding: suspend (String, String, AddonId) -> ContentOptionLookup,
    private val installedAddons: suspend () -> List<InstalledAddon>,
    private val sourceEligibility: suspend (AddonId) -> List<AddonSourceEligibility>,
    private val contentPreference: suspend (String) -> ContentPreference?,
    private val globalLanguages: () -> List<String>,
    private val deviceLocale: () -> Locale,
    private val sourceSearch: (ContentBindingSearchRequest) -> Flow<ContentBindingSearchProgress>,
    private val refreshBinding: suspend (ContentBinding) -> Result<Unit>,
    private val planner: PlanFastReadingDiscovery = PlanFastReadingDiscovery(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    @OptIn(ExperimentalCoroutinesApi::class)
    fun discover(
        canonicalTitleId: String,
        canonicalChapterId: String,
        budget: InitialDiscoveryBudget = InitialDiscoveryBudget(),
    ): Flow<FastReadingDiscoveryEvent> = channelFlow {
        require(canonicalTitleId.isNotBlank() && canonicalChapterId.isNotBlank())
        val found = AtomicBoolean(false)
        val refreshGate = Mutex()
        val queried = linkedMapOf<AddonId, Set<Long>>()
        val queriedGate = Mutex()
        val hasUnconfirmedCandidate = AtomicBoolean(false)

        val finishedWithinBudget = withTimeoutOrNull(budget.totalMillis) {
            val initial = try {
                withTimeoutOrNull(budget.existingLookupMillis) {
                    lookupExisting(canonicalTitleId, canonicalChapterId)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                null
            }
            if (initial == null) {
                send(
                    FastReadingDiscoveryEvent.SourceFailed(
                        null,
                        null,
                        FastDiscoveryFailureStage.INITIAL_LOOKUP,
                    ),
                )
            } else {
                initial.failedProviders.forEach { addonId ->
                    send(
                        FastReadingDiscoveryEvent.SourceFailed(
                            addonId,
                            null,
                            FastDiscoveryFailureStage.INITIAL_LOOKUP,
                        ),
                    )
                }
                if (initial.options.isNotEmpty()) {
                    send(FastReadingDiscoveryEvent.Ready(initial.options, alreadyAvailable = true))
                    send(FastReadingDiscoveryEvent.Completed(FastDiscoveryCompletion.FOUND, emptyMap()))
                    return@withTimeoutOrNull true
                }
            }

            val preferred = contentPreference(canonicalTitleId)
            val configured = listOfNotNull(preferred?.preferredLanguage) + globalLanguages()
            val preferredLanguages = (configured.takeIf(List<String>::isNotEmpty) ?: listOf(
                deviceLocale().toLanguageTag(),
                deviceLocale().language,
                "en",
            )).map(String::trim)
                .filter { it.isNotEmpty() && !it.equals("und", ignoreCase = true) }
                .distinctBy { it.lowercase(Locale.ROOT) }

            val eligibleAddons = installedAddons().filter { it.enabled && it.mihonSourceIds.isNotEmpty() }
            val eligibility = eligibleAddons.associate { addon ->
                val current = try {
                    sourceEligibility(addon.id)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    send(
                        FastReadingDiscoveryEvent.SourceFailed(
                            addon.id,
                            null,
                            FastDiscoveryFailureStage.ELIGIBILITY,
                        ),
                    )
                    emptyList()
                }
                addon.id to current
            }
            val targets = planner.execute(
                installed = eligibleAddons,
                eligibility = eligibility,
                preferredAddonId = preferred?.preferredAddonId,
                preferredLanguages = preferredLanguages,
                maxAddons = budget.maxAddons,
                maxQueries = budget.maxQueries,
            )
            if (targets.isEmpty()) {
                send(
                    FastReadingDiscoveryEvent.Completed(
                        FastDiscoveryCompletion.NO_ELIGIBLE_SOURCES,
                        emptyMap(),
                    ),
                )
                return@withTimeoutOrNull true
            }
            send(FastReadingDiscoveryEvent.Searching(targets))

            coroutineScope {
                targets.map { target ->
                    launch {
                        val request = ContentBindingSearchRequest(
                            canonicalTitleId = canonicalTitleId,
                            addonId = target.addonId,
                            preferredLanguages = preferredLanguages,
                            allowedSourceIds = target.allowedSourceIds,
                            batchSize = target.batchSize,
                            sourceTimeoutMillis = budget.sourceTimeoutMillis,
                        )
                        try {
                            sourceSearch(request).collect { event ->
                                when (event) {
                                    is ContentBindingSearchProgress.ExistingBindingsObserved -> Unit
                                    is ContentBindingSearchProgress.Completed -> {
                                        queriedGate.withLock {
                                            queried[target.addonId] =
                                                queried[target.addonId].orEmpty() + event.queriedSourceIds
                                        }
                                    }
                                    is ContentBindingSearchProgress.SourceCompleted -> {
                                        when (event.outcome) {
                                            ContentBindingSourceOutcome.CONFIRMATION_REQUIRED -> {
                                                if (event.candidates.isNotEmpty()) {
                                                    hasUnconfirmedCandidate.set(true)
                                                    send(
                                                        FastReadingDiscoveryEvent.ConfirmationRequired(
                                                            target.addonId,
                                                            event.candidates,
                                                        ),
                                                    )
                                                }
                                            }
                                            ContentBindingSourceOutcome.FAILURE -> send(
                                                FastReadingDiscoveryEvent.SourceFailed(
                                                    target.addonId,
                                                    event.sourceId,
                                                    FastDiscoveryFailureStage.SEARCH,
                                                    event.failure,
                                                ),
                                            )
                                            ContentBindingSourceOutcome.BOUND -> {
                                                for (binding in event.bindings) {
                                                    if (binding.canonicalTitleId != canonicalTitleId ||
                                                        binding.addonId != target.addonId ||
                                                        binding.availability != ContentBindingAvailability.AVAILABLE
                                                    ) {
                                                        continue
                                                    }
                                                    refreshGate.withLock {
                                                        if (found.get()) return@withLock
                                                        val refreshed = try {
                                                            refreshBinding(binding)
                                                        } catch (error: CancellationException) {
                                                            throw error
                                                        } catch (_: Throwable) {
                                                            Result.failure(UnitRefreshFailedException())
                                                        }
                                                        if (refreshed.isFailure) {
                                                            send(
                                                                FastReadingDiscoveryEvent.SourceFailed(
                                                                    target.addonId,
                                                                    event.sourceId,
                                                                    FastDiscoveryFailureStage.EVIDENCE_REFRESH,
                                                                ),
                                                            )
                                                            return@withLock
                                                        }
                                                        val available = try {
                                                            lookupAfterBinding(
                                                                canonicalTitleId,
                                                                canonicalChapterId,
                                                                target.addonId,
                                                            )
                                                        } catch (error: CancellationException) {
                                                            throw error
                                                        } catch (_: Throwable) {
                                                            send(
                                                                FastReadingDiscoveryEvent.SourceFailed(
                                                                    target.addonId,
                                                                    event.sourceId,
                                                                    FastDiscoveryFailureStage.CHAPTER_LOOKUP,
                                                                ),
                                                            )
                                                            return@withLock
                                                        }
                                                        val matching = available.options.filter { option ->
                                                            option.canonicalChapterId == canonicalChapterId &&
                                                                option.addonId == target.addonId
                                                        }
                                                        if (matching.isNotEmpty() && found.compareAndSet(false, true)) {
                                                            send(
                                                                FastReadingDiscoveryEvent.Ready(
                                                                    matching,
                                                                    alreadyAvailable = false,
                                                                ),
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                            ContentBindingSourceOutcome.EMPTY,
                                            ContentBindingSourceOutcome.NO_MATCH,
                                            -> Unit
                                        }
                                    }
                                }
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Throwable) {
                            send(
                                FastReadingDiscoveryEvent.SourceFailed(
                                    target.addonId,
                                    null,
                                    FastDiscoveryFailureStage.SEARCH,
                                ),
                            )
                        }
                    }
                }.joinAll()
            }

            send(
                FastReadingDiscoveryEvent.Completed(
                    when {
                        found.get() -> FastDiscoveryCompletion.FOUND
                        hasUnconfirmedCandidate.get() -> FastDiscoveryCompletion.CONFIRMATION_REQUIRED
                        else -> FastDiscoveryCompletion.EXHAUSTED
                    },
                    queried.toMap(),
                ),
            )
            true
        }

        if (finishedWithinBudget == null) {
            send(
                FastReadingDiscoveryEvent.Completed(
                    if (found.get()) FastDiscoveryCompletion.FOUND else FastDiscoveryCompletion.TIME_BUDGET,
                    queriedGate.withLock { queried.toMap() },
                ),
            )
        }
    }.flowOn(dispatcher)

    private class UnitRefreshFailedException : IllegalStateException("Chapter refresh failed")
}
