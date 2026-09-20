package tachiyomi.domain.tsuzuki.sync.model

import kotlinx.serialization.Serializable

@Serializable
enum class SyncDocumentKind(
    val fileName: String,
) {
    MANIFEST("manifest.json"),
    LIBRARY("library.json"),
    COLLECTIONS("collections.json"),
    SETTINGS("settings.json"),
    SOURCE_MAPPINGS("source-mappings.json"),
    CHAPTER_OVERRIDES("chapter-overrides.json"),
    FALLBACK_PROGRESS("fallback-progress.json"),
}
