package eu.kanade.tachiyomi.ui.tsuzuki.search

import dev.zacsweers.metro.Inject
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

@Inject
class TsuzukiSearchPreferences(
    preferenceStore: PreferenceStore,
) {

    private val recentSearches = preferenceStore.getString(
        Preference.appStateKey("tsuzuki_recent_searches"),
        "[]",
    )

    fun getRecentSearches(): List<String> {
        return runCatching {
            Json.decodeFromString<List<String>>(recentSearches.get())
        }.getOrDefault(emptyList())
    }

    fun recordSearch(query: String) {
        val normalized = query.trim()
        if (normalized.isEmpty()) return

        val updated = buildList {
            add(normalized)
            addAll(
                getRecentSearches().filterNot {
                    it.equals(normalized, ignoreCase = true)
                },
            )
        }.take(MAX_RECENT_SEARCHES)

        recentSearches.set(Json.encodeToString(updated))
    }

    fun clear() {
        recentSearches.set("[]")
    }

    private companion object {
        const val MAX_RECENT_SEARCHES = 20
    }
}
