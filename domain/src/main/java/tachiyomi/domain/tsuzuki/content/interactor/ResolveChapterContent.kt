package tachiyomi.domain.tsuzuki.content.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.addon.AddonRegistry
import tachiyomi.domain.tsuzuki.addon.ContentProvider
import tachiyomi.domain.tsuzuki.addon.TargetedContentProvider
import tachiyomi.domain.tsuzuki.addon.repository.AddonRepository
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticEvent
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticFailures
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticOutcome
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticReason
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticStage
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnostics
import tachiyomi.domain.tsuzuki.chapter.diagnostics.NoOpChapterInventoryDiagnostics
import tachiyomi.domain.tsuzuki.chapter.diagnostics.recordIfEnabled
import tachiyomi.domain.tsuzuki.content.ContentBinding
import tachiyomi.domain.tsuzuki.content.ContentBindingAvailability
import tachiyomi.domain.tsuzuki.content.ContentDelivery
import tachiyomi.domain.tsuzuki.content.ContentOption
import tachiyomi.domain.tsuzuki.content.cache.ContentOptionCache
import tachiyomi.domain.tsuzuki.content.cache.ContentOptionCacheKey
import tachiyomi.domain.tsuzuki.content.cache.InFlightContentResolution
import tachiyomi.domain.tsuzuki.content.model.ContentResolution
import tachiyomi.domain.tsuzuki.content.repository.ContentPreferenceRepository
import tachiyomi.domain.tsuzuki.reader.model.CanonicalReaderPreferences

/** Keeps provider failures separate from a genuine no-chapters result. */
data class ContentOptionLookup(
    val options: List<ContentOption>,
    val failedProviders: List<AddonId>,
    val queriedProviderCount: Int,
)

class ResolveChapterContent(
    private val addonRegistry: AddonRegistry,
    private val contentPreferenceRepository: ContentPreferenceRepository,
    private val readerPreferences: CanonicalReaderPreferences,
    private val rankContentOptions: RankContentOptions,
    private val contentOptionCache: ContentOptionCache,
    private val inFlightContentResolution: InFlightContentResolution,
    private val addonRepository: AddonRepository? = null,
    private val diagnostics: ChapterInventoryDiagnostics = NoOpChapterInventoryDiagnostics,
    @Suppress("UNUSED_PARAMETER") constructorMarker: Unit = Unit,
) {

    @Inject
    constructor(
        addonRegistry: AddonRegistry,
        contentPreferenceRepository: ContentPreferenceRepository,
        readerPreferences: CanonicalReaderPreferences,
        rankContentOptions: RankContentOptions,
        contentOptionCache: ContentOptionCache,
        inFlightContentResolution: InFlightContentResolution,
        addonRepository: AddonRepository,
        diagnostics: ChapterInventoryDiagnostics,
    ) : this(
        addonRegistry = addonRegistry,
        contentPreferenceRepository = contentPreferenceRepository,
        readerPreferences = readerPreferences,
        rankContentOptions = rankContentOptions,
        contentOptionCache = contentOptionCache,
        inFlightContentResolution = inFlightContentResolution,
        addonRepository = addonRepository,
        diagnostics = diagnostics,
        constructorMarker = Unit,
    )

    suspend fun execute(
        canonicalTitleId: String,
        canonicalChapterId: String,
    ): ContentResolution {
        addonRegistry.awaitReady()
        val titlePreference = contentPreferenceRepository.get(canonicalTitleId)
        val preferredAddonId = titlePreference?.preferredAddonId
        val preferredLanguages = titlePreferredLanguages(
            titlePreference?.preferredLanguage,
            readerPreferences.preferredLanguages.get(),
        )
        val providers = addonRegistry.contentProviders()
        recordProviderRegistration(canonicalTitleId, providers)
        if (providers.isEmpty()) return ContentResolution.Unavailable

        val preferredProvider = preferredAddonId?.let { preferredId ->
            providers.firstOrNull { it.addonId == preferredId }
        }
        val resolved = linkedMapOf<AddonId, List<ContentOption>>()

        if (preferredProvider != null) {
            val preferredOptions = resolveProvider(
                provider = preferredProvider,
                canonicalTitleId = canonicalTitleId,
                canonicalChapterId = canonicalChapterId,
            ).optionsOrEmpty()
            resolved[preferredProvider.addonId] = preferredOptions

            if (preferredOptions.isNotEmpty()) {
                val ranked = rankContentOptions.execute(
                    options = preferredOptions,
                    preferredAddonId = preferredAddonId,
                    preferredLanguages = preferredLanguages,
                )
                return ContentResolution.Direct(
                    option = ranked.first(),
                    usedFallback = false,
                )
            }
        }

        val unresolvedProviders = providers.filter { it.addonId !in resolved }
        val additional = coroutineScope {
            val gate = Semaphore(MAX_CONCURRENT_PROVIDER_RESOLUTIONS)
            unresolvedProviders.map { provider ->
                async {
                    gate.withPermit {
                        provider.addonId to resolveProvider(
                            provider = provider,
                            canonicalTitleId = canonicalTitleId,
                            canonicalChapterId = canonicalChapterId,
                        ).optionsOrEmpty()
                    }
                }
            }.awaitAll()
        }
        additional.forEach { (addonId, options) -> resolved[addonId] = options }

        val ranked = rankContentOptions.execute(
            options = resolved.values.flatten(),
            preferredAddonId = preferredAddonId,
            preferredLanguages = preferredLanguages,
        )
        if (ranked.isEmpty()) return ContentResolution.Unavailable

        if (preferredAddonId == null) {
            return ContentResolution.NeedsSelection(
                options = ranked,
                preferredAddonId = null,
                preferredUnavailable = false,
            )
        }

        if (readerPreferences.automaticFallback.get()) {
            return ContentResolution.Direct(
                option = ranked.first(),
                usedFallback = true,
            )
        }

        return ContentResolution.NeedsSelection(
            options = ranked,
            preferredAddonId = preferredAddonId,
            preferredUnavailable = true,
        )
    }

    suspend fun resolveOptions(
        canonicalTitleId: String,
        canonicalChapterId: String,
        refresh: Boolean = false,
    ): List<ContentOption> = lookupOptions(
        canonicalTitleId = canonicalTitleId,
        canonicalChapterId = canonicalChapterId,
        refresh = refresh,
    ).options

    suspend fun lookupOptions(
        canonicalTitleId: String,
        canonicalChapterId: String,
        refresh: Boolean = false,
    ): ContentOptionLookup {
        addonRegistry.awaitReady()
        if (refresh) {
            inFlightContentResolution.invalidateChapter(canonicalTitleId, canonicalChapterId)
            contentOptionCache.invalidateChapter(
                canonicalTitleId = canonicalTitleId,
                canonicalChapterId = canonicalChapterId,
            )
        }

        val providers = addonRegistry.contentProviders()
        recordProviderRegistration(canonicalTitleId, providers)
        if (providers.isEmpty()) return ContentOptionLookup(emptyList(), emptyList(), 0)

        val titlePreference = contentPreferenceRepository.get(canonicalTitleId)
        val preferredAddonId = titlePreference?.preferredAddonId
        val preferredLanguages = titlePreferredLanguages(
            titlePreference?.preferredLanguage,
            readerPreferences.preferredLanguages.get(),
        )
        val resolved = coroutineScope {
            val gate = Semaphore(MAX_CONCURRENT_PROVIDER_RESOLUTIONS)
            providers.map { provider ->
                async {
                    gate.withPermit {
                        provider.addonId to resolveProvider(
                            provider = provider,
                            canonicalTitleId = canonicalTitleId,
                            canonicalChapterId = canonicalChapterId,
                            callerWaitMillis = PROVIDER_OPTION_LOOKUP_WAIT_MILLIS,
                        )
                    }
                }
            }.awaitAll()
        }

        val failed = resolved.mapNotNull { (addonId, result) ->
            result.exceptionOrNull()?.let { error ->
                if (error is CancellationException) throw error
                addonId
            }
        }
        return ContentOptionLookup(
            options = rankContentOptions.execute(
                options = resolved.flatMap { (_, result) -> result.getOrNull().orEmpty() },
                preferredAddonId = preferredAddonId,
                preferredLanguages = preferredLanguages,
            ),
            failedProviders = failed,
            queriedProviderCount = providers.size,
        )
    }

    /** Post-discovery lookup for one persisted edition; never fetch every sibling language. */
    suspend fun lookupBindingOptions(
        binding: ContentBinding,
        canonicalChapterId: String,
    ): ContentOptionLookup {
        if (binding.availability != ContentBindingAvailability.AVAILABLE) {
            return ContentOptionLookup(emptyList(), listOf(binding.addonId), 0)
        }
        addonRegistry.awaitReady()
        val provider = addonRegistry.contentProviders()
            .firstOrNull { it.addonId == binding.addonId } as? TargetedContentProvider
            ?: return ContentOptionLookup(emptyList(), listOf(binding.addonId), 0)
        val cacheKey = ContentOptionCacheKey(
            canonicalTitleId = binding.canonicalTitleId,
            canonicalChapterId = canonicalChapterId,
            addonId = binding.addonId,
        )
        inFlightContentResolution.invalidate(cacheKey)
        contentOptionCache.invalidate(cacheKey)
        val result = try {
            provider.resolveBinding(binding, canonicalChapterId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
        val options = result.getOrNull()?.let { received ->
            eligibleOptions(binding.addonId, received).filter {
                it.canonicalChapterId == canonicalChapterId && it.addonId == binding.addonId
            }
        }.orEmpty()
        val titlePreference = contentPreferenceRepository.get(binding.canonicalTitleId)
        val languages = titlePreferredLanguages(
            titlePreference?.preferredLanguage,
            readerPreferences.preferredLanguages.get(),
        )
        return ContentOptionLookup(
            options = rankContentOptions.execute(options, titlePreference?.preferredAddonId, languages),
            failedProviders = if (result.isFailure) listOf(binding.addonId) else emptyList(),
            queriedProviderCount = 1,
        )
    }

    suspend fun invalidateAddon(addonId: AddonId) {
        inFlightContentResolution.invalidateAddon(addonId)
        contentOptionCache.invalidateAddon(addonId)
    }

    /** Check again at Reader preparation, since an on-screen option may have become stale. */
    suspend fun isOptionEnabled(option: ContentOption): Boolean {
        val delivery = option.delivery as? ContentDelivery.Mihon ?: return true
        val repository = addonRepository ?: return true
        return repository.snapshot().any { addon ->
            addon.id == option.addonId && addon.enabled && delivery.sourceId in addon.mihonSourceIds
        }
    }

    private suspend fun eligibleOptions(addonId: AddonId, options: List<ContentOption>): List<ContentOption> {
        if (options.none { it.delivery is ContentDelivery.Mihon }) return options
        val repository = addonRepository ?: return options
        val enabled = repository.snapshot()
            .firstOrNull { it.id == addonId && it.enabled }
            ?.mihonSourceIds
            ?.toSet()
            .orEmpty()
        return options.filter { option ->
            val delivery = option.delivery
            delivery !is ContentDelivery.Mihon || delivery.sourceId in enabled
        }
    }

    private fun titlePreferredLanguages(
        titleLanguage: String?,
        globalLanguages: List<String>,
    ): List<String> {
        val selected = titleLanguage?.trim()?.takeIf(String::isNotEmpty)
            ?: return globalLanguages
        return listOf(selected) + globalLanguages.filterNot {
            it.equals(selected, ignoreCase = true)
        }
    }

    private suspend fun resolveProvider(
        provider: ContentProvider,
        canonicalTitleId: String,
        canonicalChapterId: String,
        callerWaitMillis: Long? = null,
    ): Result<List<ContentOption>> {
        val key = ContentOptionCacheKey(
            canonicalTitleId = canonicalTitleId,
            canonicalChapterId = canonicalChapterId,
            addonId = provider.addonId,
        )
        contentOptionCache.get(key)?.let { cached ->
            if (eligibleOptions(provider.addonId, cached).size != cached.size) {
                inFlightContentResolution.invalidateAddon(provider.addonId)
                contentOptionCache.invalidateAddon(provider.addonId)
            } else {
                recordSelector(
                    canonicalTitleId,
                    provider.addonId,
                    if (cached.isEmpty()) {
                        ChapterInventoryDiagnosticOutcome.EMPTY
                    } else {
                        ChapterInventoryDiagnosticOutcome.SUCCESS
                    },
                    cached.size,
                    ChapterInventoryDiagnosticReason.CACHED_OPTIONS,
                )
                return Result.success(cached)
            }
        }

        val cacheToken = contentOptionCache.beginLookup(key)
        try {
            val resolution: suspend () -> Result<List<ContentOption>> = {
                inFlightContentResolution.execute(key) {
                    try {
                        provider.resolve(canonicalTitleId, canonicalChapterId)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        Result.failure(error)
                    }
                }
            }
            val result = if (callerWaitMillis == null) {
                resolution()
            } else {
                withTimeoutOrNull(callerWaitMillis) { resolution() }
                    ?: Result.failure(ProviderOptionWaitTimeoutException())
            }

            val received = result.getOrNull()
            if (received == null) {
                val (outcome, reason) = ChapterInventoryDiagnosticFailures.classify(result.exceptionOrNull()!!)
                recordSelector(
                    canonicalTitleId,
                    provider.addonId,
                    outcome,
                    0,
                    reason,
                    availabilityBlocked = true,
                    affectedSourceCount = 1,
                )
                return result
            }

            val options = eligibleOptions(provider.addonId, received).filter { option ->
                option.canonicalChapterId == canonicalChapterId && option.addonId == provider.addonId
            }
            if (!contentOptionCache.putIfCurrent(cacheToken, options)) {
                return Result.failure(ProviderOptionInvalidatedException())
            }
            val filtered = received.size - options.size
            val reason = when {
                filtered > 0 -> ChapterInventoryDiagnosticReason.FILTERED_FROM_UI
                options.isEmpty() -> ChapterInventoryDiagnosticReason.NO_CHAPTER_VARIANT
                else -> null
            }
            recordSelector(
                canonicalTitleId,
                provider.addonId,
                when {
                    filtered > 0 -> ChapterInventoryDiagnosticOutcome.PARTIAL
                    options.isEmpty() -> ChapterInventoryDiagnosticOutcome.EMPTY
                    else -> ChapterInventoryDiagnosticOutcome.SUCCESS
                },
                options.size,
                reason,
                received = received.size,
                discarded = filtered,
                availabilityBlocked = options.isEmpty(),
                affectedSourceCount = if (options.isEmpty()) 1 else 0,
            )
            return Result.success(options)
        } finally {
            contentOptionCache.finishLookup(cacheToken)
        }
    }

    /** Detect providers absent from the selector even when their extension is installed. */
    private suspend fun recordProviderRegistration(
        canonicalTitleId: String,
        providers: List<ContentProvider>,
    ) {
        val repository = addonRepository ?: return
        if (!runCatching { diagnostics.isRecording(canonicalTitleId) }.getOrDefault(false)) return
        val installed = try {
            repository.snapshot()
        } catch (_: Exception) {
            return // Observational only.
        }
        val registered = providers.map { it.addonId }.toSet()
        installed.forEach { addon ->
            when {
                !addon.enabled -> recordSelector(
                    canonicalTitleId,
                    addon.id,
                    ChapterInventoryDiagnosticOutcome.DISABLED,
                    0,
                    ChapterInventoryDiagnosticReason.ALL_SOURCES_DISABLED,
                    availabilityBlocked = true,
                )
                addon.id !in registered -> recordSelector(
                    canonicalTitleId,
                    addon.id,
                    ChapterInventoryDiagnosticOutcome.NO_BINDING,
                    0,
                    ChapterInventoryDiagnosticReason.PROVIDER_NOT_REGISTERED,
                    availabilityBlocked = true,
                    affectedSourceCount = addon.mihonSourceIds.size,
                )
                else -> Unit
            }
        }
    }

    private fun recordSelector(
        canonicalTitleId: String,
        addonId: AddonId,
        outcome: ChapterInventoryDiagnosticOutcome,
        optionCount: Int,
        reason: ChapterInventoryDiagnosticReason? = null,
        received: Int? = null,
        discarded: Int? = null,
        availabilityBlocked: Boolean = false,
        affectedSourceCount: Int? = null,
    ) {
        diagnostics.recordIfEnabled(
            canonicalTitleId,
            ChapterInventoryDiagnosticEvent(
                stage = ChapterInventoryDiagnosticStage.CONTENT_SELECTOR,
                outcome = outcome,
                addonId = addonId.value,
                received = received,
                accepted = optionCount,
                discarded = discarded,
                availabilityBlocked = availabilityBlocked,
                affectedSourceCount = affectedSourceCount,
                reasons = reason?.let { mapOf(it to 1) }.orEmpty(),
            ),
        )
    }

    private companion object {
        const val MAX_CONCURRENT_PROVIDER_RESOLUTIONS = 4
        const val PROVIDER_OPTION_LOOKUP_WAIT_MILLIS = 2_000L
    }

    private class ProviderOptionWaitTimeoutException :
        java.net.SocketTimeoutException("Provider option lookup timed out")

    private class ProviderOptionInvalidatedException : IllegalStateException("Provider options were invalidated")

    private fun Result<List<ContentOption>>.optionsOrEmpty(): List<ContentOption> {
        return getOrElse { error ->
            if (error is CancellationException) throw error
            emptyList()
        }
    }
}
