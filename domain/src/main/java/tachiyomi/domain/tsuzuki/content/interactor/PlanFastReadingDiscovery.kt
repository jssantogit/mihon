package tachiyomi.domain.tsuzuki.content.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.addon.model.InstalledAddon
import tachiyomi.domain.tsuzuki.addon.repository.AddonSourceEligibility
import java.util.Locale

/**
 * Selects a small, language-diverse first discovery batch. Only loaded, enabled
 * internal IDs returned by the current eligibility snapshot can be planned.
 * It never installs extensions, guesses an edition, or changes title preferences.
 */
data class PlannedAddonSearch(
    val addonId: AddonId,
    val allowedSourceIds: Set<Long>,
    val batchSize: Int,
)

@Inject
class PlanFastReadingDiscovery {

    fun execute(
        installed: List<InstalledAddon>,
        eligibility: Map<AddonId, List<AddonSourceEligibility>>,
        preferredAddonId: AddonId?,
        preferredLanguages: List<String>,
        maxAddons: Int = MAX_INITIAL_ADDONS,
        maxQueries: Int = MAX_INITIAL_QUERIES,
    ): List<PlannedAddonSearch> {
        require(maxAddons in 1..MAX_INITIAL_ADDONS)
        require(maxQueries in 1..MAX_INITIAL_QUERIES)

        val families = languageFamilies(preferredLanguages)
        if (families.isEmpty()) return emptyList()

        val candidates = installed.mapNotNull { addon ->
            if (!addon.enabled || addon.mihonSourceIds.isEmpty()) return@mapNotNull null
            val enabledIds = addon.mihonSourceIds.toSet()
            val sources = eligibility[addon.id].orEmpty().filter { source ->
                source.enabled && source.sourceId in enabledIds &&
                    families.any { family -> family.containsLanguage(source.language) }
            }
            if (sources.isEmpty()) null else EligibleAddon(addon, sources)
        }
        if (candidates.isEmpty()) return emptyList()

        // A previously chosen Add-on has priority if it is still enabled and
        // supports one of the requested languages. A package's size otherwise
        // breaks ties: small packages avoid an expensive multilingual sweep.
        val comparator = compareBy<EligibleAddon> { it.addon.mihonSourceIds.size }
            .thenBy { it.addon.id.value }
        val chosen = linkedMapOf<AddonId, EligibleAddon>()
        candidates.firstOrNull { it.addon.id == preferredAddonId }?.let {
            chosen[it.addon.id] = it
        }
        for (family in families) {
            if (chosen.size >= maxAddons) break
            if (chosen.values.any { item -> item.sources.any { family.containsLanguage(it.language) } }) {
                continue
            }
            candidates.filter { item ->
                item.addon.id !in chosen &&
                    item.sources.any { family.containsLanguage(it.language) }
            }.minWithOrNull(comparator)?.let { chosen[it.addon.id] = it }
        }
        candidates.sortedWith(comparator).forEach { item ->
            if (chosen.size < maxAddons) chosen.putIfAbsent(item.addon.id, item)
        }

        val perAddonLimit = if (chosen.size == 1) MAX_INITIAL_SOURCES_PER_ADDON else 2
        var remaining = maxQueries
        return chosen.values.mapNotNull { item ->
            val ordered = orderedSourceIds(item.sources, families)
            val allotted = minOf(perAddonLimit, ordered.size, remaining)
            remaining -= allotted
            if (allotted == 0) {
                null
            } else {
                PlannedAddonSearch(
                    addonId = item.addon.id,
                    allowedSourceIds = ordered.take(allotted).toSet(),
                    batchSize = allotted,
                )
            }
        }
    }

    private fun orderedSourceIds(
        sources: List<AddonSourceEligibility>,
        families: List<List<String>>,
    ): List<Long> {
        val grouped = families.map { family ->
            family.flatMap { language ->
                sources.filter { it.language.equals(language, ignoreCase = true) }
                    .map(AddonSourceEligibility::sourceId)
            }.distinct()
        }
        val result = linkedSetOf<Long>()
        val depth = grouped.maxOfOrNull(List<Long>::size) ?: 0
        for (index in 0 until depth) {
            grouped.forEach { group -> group.getOrNull(index)?.let(result::add) }
        }
        return result.toList()
    }

    private fun languageFamilies(languages: List<String>): List<List<String>> {
        val families = linkedMapOf<String, MutableList<String>>()
        languages.map(String::trim).filter(String::isNotEmpty).forEach { language ->
            val base = language.substringBefore('-').lowercase(Locale.ROOT)
            families.getOrPut(base) { mutableListOf() }.add(language)
        }
        return families.values.map { group -> group.distinctBy { it.lowercase(Locale.ROOT) } }
    }

    private fun List<String>.containsLanguage(language: String): Boolean =
        any { it.equals(language, ignoreCase = true) }

    private data class EligibleAddon(
        val addon: InstalledAddon,
        val sources: List<AddonSourceEligibility>,
    )

    companion object {
        const val MAX_INITIAL_ADDONS = 2
        const val MAX_INITIAL_QUERIES = 4
        const val MAX_INITIAL_SOURCES_PER_ADDON = 3
    }
}
