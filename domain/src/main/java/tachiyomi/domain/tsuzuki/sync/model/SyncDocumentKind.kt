package tachiyomi.domain.tsuzuki.sync.model

import kotlinx.serialization.Serializable

@Serializable
enum class SyncDocumentKind(
    val fileName: String,
) {
    MANIFEST("manifest.json"),
    TITLES("titles.json"),
    LIBRARY("library.json"),
    READING_PROGRESS("reading-progress.json"),
    CHAPTER_UPDATE_STATE("chapter-update-state.json"),
    CONTINUE_READING_STATE("continue-reading-state.json"),
    COLLECTIONS("collections.json"),
    CHAPTER_OVERRIDES("chapter-overrides.json"),
    INTEGRATION_SETTINGS("integration-settings.json"),
    CONTENT_PREFERENCES("content-preferences.json"),
    ADDON_STATE("addon-state.json"),

    // Legacy Drive/runtime kinds retained only while old local diff code is
    // being removed during Runtime V2 migration.
    SETTINGS("settings.json"),
    SOURCE_MAPPINGS("source-mappings.json"),
    FALLBACK_PROGRESS("fallback-progress.json"),
    ;

    companion object {
        val supabaseDurableKinds = listOf(
            TITLES,
            LIBRARY,
            READING_PROGRESS,
            CHAPTER_UPDATE_STATE,
            CONTINUE_READING_STATE,
            COLLECTIONS,
            CHAPTER_OVERRIDES,
            INTEGRATION_SETTINGS,
            CONTENT_PREFERENCES,
            ADDON_STATE,
        )
    }
}
