package tachiyomi.domain.tsuzuki.content.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.addon.model.InstalledAddon
import tachiyomi.domain.tsuzuki.addon.repository.AddonRepository
import tachiyomi.domain.tsuzuki.content.ContentBinding
import tachiyomi.domain.tsuzuki.content.ContentBindingAvailability
import tachiyomi.domain.tsuzuki.content.repository.ContentBindingRepository
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import tachiyomi.domain.tsuzuki.source.interactor.ScoreSourceTitleMatch
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceCandidate
import tachiyomi.domain.tsuzuki.source.model.ScoredSourceCandidate
import tachiyomi.domain.tsuzuki.source.service.ReadingSourceGateway
import java.util.UUID
import kotlin.time.Clock

class ResolveContentBinding internal constructor(
    private val contentBindingRepository: ContentBindingRepository,
    private val canonicalTitleRepository: CanonicalTitleRepository,
    private val addonRepository: AddonRepository,
    private val readingSourceGateway: ReadingSourceGateway,
    private val scoreSourceTitleMatch: ScoreSourceTitleMatch,
    private val idFactory: () -> String,
    private val clock: () -> Long,
) {

    @Inject
    constructor(
        contentBindingRepository: ContentBindingRepository,
        canonicalTitleRepository: CanonicalTitleRepository,
        addonRepository: AddonRepository,
        readingSourceGateway: ReadingSourceGateway,
        scoreSourceTitleMatch: ScoreSourceTitleMatch,
    ) : this(
        contentBindingRepository = contentBindingRepository,
        canonicalTitleRepository = canonicalTitleRepository,
        addonRepository = addonRepository,
        readingSourceGateway = readingSourceGateway,
        scoreSourceTitleMatch = scoreSourceTitleMatch,
        idFactory = { UUID.randomUUID().toString() },
        clock = { Clock.System.now().toEpochMilliseconds() },
    )

    suspend fun execute(
        canonicalTitleId: String,
        addonId: AddonId,
    ): Result<ContentBinding> {
        return executeAll(canonicalTitleId, addonId).mapCatching { bindings ->
            bindings.firstOrNull()
                ?: throw ContentBindingNotFoundException(
                    "No content binding found for " + addonId.value,
                )
        }
    }

    /**
     * Resolves every unambiguous internal Mihon Source exposed by one product Add-on.
     *
     * A multi-source extension (for example MangaDex with several languages) is one
     * Tsuzuki Add-on. Equal title matches from different internal Sources are not an
     * ambiguity; each Source may materialize its own provider-neutral ContentBinding.
     */
    suspend fun executeAll(
        canonicalTitleId: String,
        addonId: AddonId,
    ): Result<List<ContentBinding>> {
        return try {
            Result.success(resolveAll(canonicalTitleId, addonId))
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private suspend fun resolveAll(
        canonicalTitleId: String,
        addonId: AddonId,
    ): List<ContentBinding> {
        val addon = requireExecutableAddon(addonId)
        val existingBindings = contentBindingRepository
            .getByTitle(canonicalTitleId)
            .filter { it.addonId == addonId }
        val availableBindings = existingBindings
            .filter { it.availability != ContentBindingAvailability.UNAVAILABLE }
        if (availableBindings.isNotEmpty()) {
            return availableBindings.sortedWith(
                compareBy<ContentBinding>({ it.createdAt }, { it.id }),
            )
        }

        val canonicalTitle = canonicalTitleRepository.getById(canonicalTitleId)
            ?: throw ContentBindingNotFoundException(
                "Canonical title $canonicalTitleId does not exist",
            )

        val selected = mutableListOf<ScoredSourceCandidate>()
        val confirmationCandidates = mutableListOf<ScoredSourceCandidate>()
        val searchGate = Semaphore(MAX_CONCURRENT_SOURCE_SEARCHES)

        val candidatesBySource = coroutineScope {
            addon.mihonSourceIds
                .distinct()
                .mapIndexed { sourceRank, sourceId ->
                    async {
                        val (results, failure) = searchGate.withPermit {
                            searchSourceTitle(sourceId, canonicalTitle.displayTitle)
                        }
                        Triple(
                            sourceRank,
                            results.map { candidate ->
                                ScoredSourceCandidate(
                                    candidate = candidate,
                                    confidence = scoreSourceTitleMatch(
                                        targetTitle = canonicalTitle.displayTitle,
                                        candidateTitle = candidate.title,
                                    ),
                                    sourcePreferenceRank = sourceRank,
                                )
                            }.sortedByDescending { it.confidence },
                            failure,
                        )
                    }
                }
                .awaitAll()
        }
        var firstSearchFailure: Throwable? = null

        for ((_, candidates, failure) in candidatesBySource) {
            if (firstSearchFailure == null) firstSearchFailure = failure
            val best = candidates.firstOrNull() ?: continue
            val second = candidates.getOrNull(1)
            val highConfidence = best.confidence >= AUTO_MATCH_THRESHOLD
            val unambiguousWithinSource =
                second == null || best.confidence - second.confidence > AUTO_MATCH_MARGIN

            if (highConfidence && unambiguousWithinSource) {
                selected += best
            } else {
                confirmationCandidates += candidates
                    .filter { it.confidence >= CONFIRMATION_THRESHOLD }
                    .take(MAX_CONFIRMATION_CANDIDATES)
            }
        }

        if (selected.isEmpty()) {
            val candidates = confirmationCandidates
                .sortedWith(
                    compareBy<ScoredSourceCandidate> { it.sourcePreferenceRank }
                        .thenByDescending { it.confidence },
                )
                .take(MAX_CONFIRMATION_CANDIDATES)
            if (candidates.isNotEmpty()) {
                throw ContentBindingConfirmationRequiredException(candidates)
            }
            firstSearchFailure?.let { throw ContentBindingSourceSearchException(it) }
            throw ContentBindingNotFoundException(
                "No sufficiently confident title candidate found for " + addonId.value,
            )
        }

        val bindings = mutableListOf<ContentBinding>()
        for (scored in selected) {
            val materialized = readingSourceGateway.materialize(scored.candidate).getOrThrow()
            require(materialized.sourceId == scored.candidate.sourceId) {
                "Materialized source does not match selected candidate"
            }
            require(materialized.sourceUrl == scored.candidate.sourceUrl) {
                "Materialized URL does not match selected candidate"
            }
            require(materialized.runtimePayload.isNotEmpty()) {
                "Materialized binding must include provider runtime payload"
            }

            val now = clock()
            val existing = existingBindings.firstOrNull {
                it.providerTitleKey == materialized.providerTitleKey
            } ?: existingBindings.singleOrNull()
                ?.takeIf { selected.size == 1 }
            val binding = ContentBinding(
                id = existing?.id ?: idFactory(),
                canonicalTitleId = canonicalTitleId,
                addonId = addonId,
                providerTitleKey = materialized.providerTitleKey,
                matchConfidence = scored.confidence,
                verifiedByUser = existing?.verifiedByUser ?: false,
                availability = ContentBindingAvailability.AVAILABLE,
                runtimePayload = materialized.runtimePayload,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            )
            contentBindingRepository.upsert(binding)
            bindings += binding
        }

        return bindings
            .distinctBy(ContentBinding::providerTitleKey)
            .sortedWith(compareBy({ it.createdAt }, { it.id }))
    }

    /**
     * Retry spelling/punctuation variants only when the first lookup has no safe,
     * unambiguous match. All candidates still pass the original confidence and
     * ambiguity gates; a punctuation change never establishes canonical identity.
     */
    private suspend fun searchSourceTitle(
        sourceId: Long,
        title: String,
    ): Pair<List<ReadingSourceCandidate>, Throwable?> {
        val collected = mutableListOf<ReadingSourceCandidate>()
        for (query in titleSearchQueries(title)) {
            val response = try {
                readingSourceGateway.search(sourceId, query)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Result.failure(error)
            }
            val matches = response.getOrElse { error ->
                if (error is CancellationException) throw error
                return collected.distinctBy { it.sourceId to it.sourceUrl } to error
            }
            collected += matches
            val ranked = collected.distinctBy { it.sourceId to it.sourceUrl }
                .sortedByDescending { scoreSourceTitleMatch(title, it.title) }
            val best = ranked.firstOrNull()
            val runnerUp = ranked.getOrNull(1)
            if (best != null) {
                val bestScore = scoreSourceTitleMatch(title, best.title)
                val unambiguous = runnerUp == null ||
                    bestScore - scoreSourceTitleMatch(title, runnerUp.title) > AUTO_MATCH_MARGIN
                if (bestScore >= AUTO_MATCH_THRESHOLD && unambiguous) {
                    break
                }
            }
        }
        return collected.distinctBy { it.sourceId to it.sourceUrl } to null
    }

    private fun titleSearchQueries(title: String): List<String> = buildList {
        add(title)
        if ('-' in title) {
            val spaced = title.replace(Regex("\\s*-\\s*"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (spaced.isNotBlank()) {
                add(spaced)
                val words = spaced.split(" ").filter(String::isNotBlank)
                if (words.size in 2..5) {
                    for (index in 1 until words.size) {
                        add(
                            words.take(index).joinToString(" ") + "-" +
                                words.drop(index).joinToString(" "),
                        )
                    }
                }
            }
        }
    }.distinct().take(MAX_TITLE_SEARCH_QUERIES)

    private suspend fun requireExecutableAddon(addonId: AddonId): InstalledAddon {
        return addonRepository.snapshot()
            .firstOrNull { it.id == addonId && it.enabled }
            ?: throw ContentBindingNotFoundException(
                "Add-on " + addonId.value + " is not installed and enabled",
            )
    }

    private companion object {
        const val AUTO_MATCH_THRESHOLD = 0.97
        const val AUTO_MATCH_MARGIN = 0.08
        const val CONFIRMATION_THRESHOLD = 0.70
        const val MAX_CONFIRMATION_CANDIDATES = 5
        const val MAX_TITLE_SEARCH_QUERIES = 3
        const val MAX_CONCURRENT_SOURCE_SEARCHES = 4
    }
}

class ContentBindingConfirmationRequiredException(
    val candidates: List<ScoredSourceCandidate>,
) : IllegalStateException("Content binding requires user confirmation")

class ContentBindingNotFoundException(
    message: String,
) : IllegalStateException(message)

/** A source lookup failed rather than returning a genuine empty title search. */
class ContentBindingSourceSearchException(cause: Throwable) :
    IllegalStateException("Content binding source search is unavailable", cause)
