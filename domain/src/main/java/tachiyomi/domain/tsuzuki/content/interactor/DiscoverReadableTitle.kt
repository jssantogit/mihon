package tachiyomi.domain.tsuzuki.content.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.addon.model.InstalledAddon
import tachiyomi.domain.tsuzuki.addon.repository.AddonRepository
import tachiyomi.domain.tsuzuki.addon.repository.AddonSourceEligibility
import tachiyomi.domain.tsuzuki.addon.repository.AddonSourceEligibilityRepository
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceRepository
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
    private val hasObservedChapters: suspend (String) -> Boolean,
    private val existingBindings: suspend (String) -> List<ContentBinding>,
    private val installedAddons: suspend () -> List<InstalledAddon>,
    private val sourceEligibility: suspend (AddonId) -> List<AddonSourceEligibility>,
    private val preferredLanguages: suspend (String) -> List<String>,
    private val preferredSourceIds: suspend (List<String>) -> List<Long>,
    private val sourceSearch: (ContentBindingSearchRequest) -> Flow<ContentBindingSearchProgress>,
    private val planner: PlanFastReadingDiscovery = PlanFastReadingDiscovery(),
) {

    @Inject
    constructor(
        evidenceRepository: ChapterEvidenceRepository,
        contentBindingRepository: ContentBindingRepository,
        addonRepository: AddonRepository,
        eligibilityRepository: AddonSourceEligibilityRepository,
        contentPreferenceRepository: ContentPreferenceRepository,
        readerPreferences: CanonicalReaderPreferences,
        preferredReadingSources: GetPreferredReadingSources,
        sourceResolver: ResolveContentBinding,
        planner: PlanFastReadingDiscovery,
    ) : this(
        hasObservedChapters = { titleId ->
            evidenceRepository.getByCanonicalTitleId(titleId)
                .any { it.mappedCanonicalChapterId != null }
        },
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
        sourceSearch = sourceResolver::searchProgress,
        planner = planner,
    )

    suspend fun execute(canonicalTitleId: String): Result<List<ContentBinding>> {
        require(canonicalTitleId.isNotBlank())
        return try {
            if (hasObservedChapters(canonicalTitleId)) {
                return Result.success(emptyList())
            }

            val installed = installedAddons()
                .filter { it.enabled && it.mihonSourceIds.isNotEmpty() }
            if (installed.isEmpty()) return Result.success(emptyList())

            val alreadyBoundSourceIds = existingBindings(canonicalTitleId)
                .mapNotNull { it.providerTitleKey.substringBefore(':').toLongOrNull() }
                .toSet()
            val languages = preferredLanguages(canonicalTitleId)
            val configuredSourceIds = preferredSourceIds(languages)
                .filterNot { it in alreadyBoundSourceIds }

            val eligibility = installed.associate { addon ->
                addon.id to sourceEligibility(addon.id)
                    .filterNot { it.sourceId in alreadyBoundSourceIds }
            }
            val targets = planner.execute(
                installed = installed,
                eligibility = eligibility,
                preferredAddonId = null,
                preferredLanguages = languages,
                preferredSourceIds = configuredSourceIds,
            )
            if (targets.isEmpty()) return Result.success(emptyList())

            val discovered = mutableListOf<ContentBinding>()
            for (target in targets) {
                val request = ContentBindingSearchRequest(
                    canonicalTitleId = canonicalTitleId,
                    addonId = target.addonId,
                    preferredLanguages = languages,
                    allowedSourceIds = target.allowedSourceIds,
                    batchSize = target.batchSize,
                    sourceTimeoutMillis = TITLE_DISCOVERY_SOURCE_TIMEOUT_MILLIS,
                )
                sourceSearch(request).collect { event ->
                    if (event is ContentBindingSearchProgress.SourceCompleted &&
                        event.outcome == ContentBindingSourceOutcome.BOUND
                    ) {
                        discovered += event.bindings.filter { binding ->
                            binding.canonicalTitleId == canonicalTitleId &&
                                binding.addonId == target.addonId &&
                                binding.availability == ContentBindingAvailability.AVAILABLE
                        }
                    }
                }
            }
            Result.success(discovered.distinctBy(ContentBinding::id))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private companion object {
        const val TITLE_DISCOVERY_SOURCE_TIMEOUT_MILLIS = 4_000L
    }
}
