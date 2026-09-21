package tachiyomi.domain.tsuzuki.repository

import tachiyomi.domain.tsuzuki.model.LibraryStatus

interface CanonicalTitleMergeRepository {
    suspend fun convergeTo(targetId: String, localId: String)
}

sealed class CanonicalTitleMergeConflict(
    message: String,
) : IllegalStateException(message) {

    class LibraryStatusConflict(
        val targetStatus: LibraryStatus,
        val localStatus: LibraryStatus,
    ) : CanonicalTitleMergeConflict(
        "Cannot merge canonical titles with incompatible library statuses: target=$targetStatus, local=$localStatus",
    )

    class PreferredAddonConflict(
        val targetAddonId: String,
        val localAddonId: String,
    ) : CanonicalTitleMergeConflict(
        "Cannot merge canonical titles with different preferred Add-ons: target=$targetAddonId, local=$localAddonId",
    )

    class PreferredSourceConflict(
        val targetMappingId: String,
        val localMappingId: String,
    ) : CanonicalTitleMergeConflict(
        "Cannot merge canonical titles with different preferred source mappings: target=$targetMappingId, local=$localMappingId",
    )

    class ChapterIdentityConflict(
        val targetChapterId: String,
        val localChapterId: String,
        val chapterKey: String,
    ) : CanonicalTitleMergeConflict(
        "Cannot silently merge canonical chapters with the same structured identity: " +
            "target=$targetChapterId, local=$localChapterId, key=$chapterKey",
    )

    class ContinueReadingConflict : CanonicalTitleMergeConflict(
        "Cannot merge canonical titles with different Continue Reading suppression state",
    )

    class ReaderPreferenceConflict : CanonicalTitleMergeConflict(
        "Cannot merge canonical titles with different reader fallback preferences",
    )
}
