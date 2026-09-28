package tachiyomi.domain.tsuzuki.content.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.addon.model.InstalledAddon
import tachiyomi.domain.tsuzuki.addon.repository.AddonRepository
import tachiyomi.domain.tsuzuki.addon.repository.AddonSourceEligibility
import tachiyomi.domain.tsuzuki.addon.repository.AddonSourceEligibilityRepository
import tachiyomi.domain.tsuzuki.content.ContentBinding
import tachiyomi.domain.tsuzuki.content.ContentBindingAvailability
import tachiyomi.domain.tsuzuki.content.repository.ContentBindingRepository
import tachiyomi.domain.tsuzuki.content.repository.ContentPreferenceRepository
import tachiyomi.domain.tsuzuki.reader.model.CanonicalReaderPreferences
import tachiyomi.domain.tsuzuki.source.interactor.GetPreferredReadingSources
import java.util.Locale

/**
 * Bounded title-level discovery used before a chapter inventory refresh.
 *
 * It exists to break the zero-binding/zero-chapter deadlock: a catalog title can
 * acquire safe automatic ContentBindings before there is a concrete chapter to tap.
 * Ambiguous candidates remain unbound and therefore still require the explicit
 * manual confirmation flow.
 */
class DiscoverReadableTitle internal constructor(
    private val existingBindings: suspend (String) -> List<ContentBinding>,
    private val installedAddons: suspend () -> List<InstalledAddon>,
    private val sourceEligibility: suspend (AddonId) -> List<AddonSourceEligibility>,
    private val preferredLanguages: suspend (String) -> List<String>,
    private val preferredSourceIds: suspend (List<String>) -> List<Long>,
    private val preferredAddonId: suspend (String) -> AddonId? = { null },
    private val sourceSearch: (ContentBindingSearchRequest) -> Flow<ContentBindingSearchProgress>,
    private val planner: PlanFastReadingDiscovery = PlanFastReadingDiscovery(),
) {

    @Inject
    constructor(
        contentBindingRepository: ContentBindingRepository,
        addonRepository: AddonRepository,
        eligibilityRepository: AddonSourceEligibilityRepository,
        contentPreferenceRepository: ContentPreferenceRepository,
        readerPreferences: CanonicalReaderPreferences,
        preferredReadingSources: GetPreferredReadingSources,
        sourceResolver: ResolveContentBinding,
        planner: PlanFastReadingDiscovery,
    ) : this(
        existingBindings = { titleId ->
            contentBindingRepository.getByTitle(titleId)
                .filter { it.availability != ContentBindingAvailability.UNAVAILABLE }
        },
        installedAddons = addonRepository::snapshot,
        sourceEligibility = eligibilityRepository::getByAddonId,
        preferredLanguages = { titleId ->
            val titleLanguage = contentPreferenceRepository.get(titleId)?.preferredLanguage
            val global = readerPreferences.preferredLanguages.get()
            val configured = preferredReadingSources.getConfiguredLanguages()
            val requested = listOfNotNull(titleLanguage) + global + configured
            val fallback = if (requested.isEmpty()) {
                val locale = Locale.getDefault()
                listOf(locale.toLanguageTag(), locale.language, "en")
            } else {
                requested
            }
            fallback.map(String::trim)
                .filter { it.isNotEmpty() && !it.equals("und", ignoreCase = true) }
                .distinctBy { it.lowercase(Locale.ROOT) }
        },
        preferredSourceIds = { languages ->
            val configuredLanguages = preferredReadingSources.getConfiguredLanguages()
            (languages + configuredLanguages)
                .distinctBy { it.lowercase(Locale.ROOT) }
                .flatMap { language ->
                    preferredReadingSources.await(language)
                        .sortedBy { it.position }
                        .map { it.sourceId }
                }
                .distinct()
        },
        preferredAddonId = { titleId -> contentPreferenceRepository.get(titleId)?.preferredAddonId },
        sourceSearch = sourceResolver::searchProgress,
        planner = planner,
    )

    suspend fun execute(canonicalTitleId: String): Result<List<ContentBinding>> {
        require(canonicalTitleId.isNotBlank())
        return try {
            val currentBindings = existingBindings(canonicalTitleId)
            if (currentBindings.isNotEmpty()) {
                return Result.success(currentBindings)
            }

            val installed = installedAddons()
                .filter { it.enabled && it.mihonSourceIds.isNotEmpty() }
            if (installed.isEmpty()) return Result.success(emptyList())

            val languages = preferredLanguages(canonicalTitleId)
            val configuredSourceIds = try {
                preferredSourceIds(languages)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                emptyList()
            }

            val baseEligibility = installed.associate { addon ->
                addon.id to try {
                    sourceEligibility(addon.id)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    emptyList()
                }
            }
            val attemptedSourceIds = mutableSetOf<Long>()
            val discovered = mutableListOf<ContentBinding>()

            for (wave in 0 until MAX_TITLE_DISCOVERY_WAVES) {
                val remainingEligibility = baseEligibility.mapValues { (_, sources) ->
                    sources.filterNot { it.sourceId in attemptedSourceIds }
                }
                val targets = planner.execute(
                    installed = installed,
                    eligibility = remainingEligibility,
                    preferredAddonId = preferredAddonId(canonicalTitleId),
                    preferredLanguages = languages,
                    preferredSourceIds = configuredSourceIds.filterNot { it in attemptedSourceIds },
                )
                if (targets.isEmpty()) break

                val waveResults = coroutineScope {
                    targets.map { target ->
                        async {
                            val queried = mutableSetOf<Long>()
                            val bindings = mutableListOf<ContentBinding>()
                            val request = ContentBindingSearchRequest(
                                canonicalTitleId = canonicalTitleId,
                                addonId = target.addonId,
                                preferredLanguages = languages,
                                allowedSourceIds = target.allowedSourceIds,
                                batchSize = target.batchSize,
                                sourceTimeoutMillis = TITLE_DISCOVERY_SOURCE_TIMEOUT_MILLIS,
                            )
                            sourceSearch(request).collect { event ->
                                when (event) {
                                    is ContentBindingSearchProgress.Completed ->
                                        queried += event.queriedSourceIds
                                    is ContentBindingSearchProgress.SourceCompleted -> {
                                        event.sourceId?.let(queried::add)
                                        if (event.outcome == ContentBindingSourceOutcome.BOUND) {
                                            bindings += event.bindings.filter { binding ->
                                                binding.canonicalTitleId == canonicalTitleId &&
                                                    binding.addonId == target.addonId &&
                                                    binding.availability == ContentBindingAvailability.AVAILABLE
                                            }
                                        }
                                    }
                                    is ContentBindingSearchProgress.ExistingBindingsObserved -> Unit
                                }
                            }
                            DiscoveryWaveResult(
                                queriedSourceIds = queried.ifEmpty { target.allowedSourceIds.toMutableSet() },
                                bindings = bindings,
                            )
                        }
                    }.awaitAll()
                }

                val before = attemptedSourceIds.size
                waveResults.forEach { result ->
                    attemptedSourceIds += result.queriedSourceIds
                    discovered += result.bindings
                }
                if (discovered.isNotEmpty()) break
                if (attemptedSourceIds.size == before) break
            }
            Result.success(discovered.distinctBy(ContentBinding::id))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private data class DiscoveryWaveResult(
        val queriedSourceIds: Set<Long>,
        val bindings: List<ContentBinding>,
    )

    private companion object {
        const val MAX_TITLE_DISCOVERY_WAVES = 3
        const val TITLE_DISCOVERY_SOURCE_TIMEOUT_MILLIS = 3_000L
    }
}
