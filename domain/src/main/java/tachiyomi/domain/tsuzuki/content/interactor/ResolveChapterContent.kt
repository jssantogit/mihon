package tachiyomi.domain.tsuzuki.content.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.addon.AddonRegistry
import tachiyomi.domain.tsuzuki.addon.ContentProvider
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

@Inject
class ResolveChapterContent(
    private val addonRegistry: AddonRegistry,
    private val contentPreferenceRepository: ContentPreferenceRepository,
    private val readerPreferences: CanonicalReaderPreferences,
    private val rankContentOptions: RankContentOptions,
    private val contentOptionCache: ContentOptionCache,
    private val inFlightContentResolution: InFlightContentResolution,
) {

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
            contentOptionCache.invalidateChapter(
                canonicalTitleId = canonicalTitleId,
                canonicalChapterId = canonicalChapterId,
            )
        }

        val providers = addonRegistry.contentProviders()
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

    suspend fun invalidateAddon(addonId: AddonId) {
        contentOptionCache.invalidateAddon(addonId)
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
    ): Result<List<ContentOption>> {
        val key = ContentOptionCacheKey(
            canonicalTitleId = canonicalTitleId,
            canonicalChapterId = canonicalChapterId,
            addonId = provider.addonId,
        )
        contentOptionCache.get(key)?.let { cached ->
            return Result.success(cached)
        }

        return inFlightContentResolution.execute(key) resolution@{
            contentOptionCache.get(key)?.let { cached ->
                return@resolution Result.success(cached)
            }
            val result = try {
                provider.resolve(canonicalTitleId, canonicalChapterId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Result.failure(error)
            }.map { options ->
                options.filter { option ->
                    option.canonicalChapterId == canonicalChapterId &&
                        option.addonId == provider.addonId
                }
            }
            result.getOrNull()?.let { options ->
                contentOptionCache.put(key, options)
            }
            result
        }
    }

    private companion object {
        const val MAX_CONCURRENT_PROVIDER_RESOLUTIONS = 4
    }

    private fun Result<List<ContentOption>>.optionsOrEmpty(): List<ContentOption> {
        return getOrElse { error ->
            if (error is CancellationException) throw error
            emptyList()
        }
    }
}
