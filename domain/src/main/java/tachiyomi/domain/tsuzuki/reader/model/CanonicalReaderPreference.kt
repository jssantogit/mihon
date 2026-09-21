package tachiyomi.domain.tsuzuki.reader.model

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

@Inject
@SingleIn(AppScope::class)
class CanonicalReaderPreferences(
    preferenceStore: PreferenceStore,
) {
    val automaticFallback: Preference<Boolean> = preferenceStore.getBoolean(
        "tsuzuki_content_automatic_fallback",
        false,
    )

    val preferredLanguages: Preference<List<String>> = preferenceStore.getObjectFromString(
        key = "tsuzuki_content_preferred_languages",
        defaultValue = emptyList(),
        serializer = { languages -> languages.joinToString(LANGUAGE_SEPARATOR) },
        deserializer = { raw ->
            raw.split(LANGUAGE_SEPARATOR)
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
        },
    )

    private companion object {
        const val LANGUAGE_SEPARATOR = "\u001F"
    }
}

/**
 * Transitional legacy model for the pre-runtime-v2 title-scoped reader preference table.
 * Runtime-v2 content fallback uses [CanonicalReaderPreferences] instead.
 */
data class CanonicalReaderPreference(
    val canonicalTitleId: String,
    val automaticFallback: Boolean = false,
    val updatedAt: Long = 0L,
)
