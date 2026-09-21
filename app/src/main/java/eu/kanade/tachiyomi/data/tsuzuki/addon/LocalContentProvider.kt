package eu.kanade.tachiyomi.data.tsuzuki.addon

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.addon.ContentProvider
import tachiyomi.domain.tsuzuki.content.ContentDelivery
import tachiyomi.domain.tsuzuki.content.ContentOption
import tachiyomi.domain.tsuzuki.download.repository.CanonicalDownloadRepository

@Inject
@SingleIn(AppScope::class)
class LocalContentProvider(
    private val canonicalDownloadRepository: CanonicalDownloadRepository,
) : ContentProvider {

    override val addonId: AddonId = ADDON_ID

    override suspend fun resolve(
        canonicalTitleId: String,
        canonicalChapterId: String,
    ): Result<List<ContentOption>> {
        val artifact = canonicalDownloadRepository.get(canonicalChapterId)
            ?: return Result.success(emptyList())
        val delivery = when (artifact.format.uppercase()) {
            "DIRECTORY" -> ContentDelivery.LocalDirectory(artifact.localUri)
            "CBZ", "ZIP", "ARCHIVE", "EPUB" -> ContentDelivery.LocalArchive(artifact.localUri)
            else -> return Result.failure(
                IllegalArgumentException("Unsupported canonical local artifact format: " + artifact.format),
            )
        }
        return Result.success(
            listOf(
                ContentOption(
                    key = "local:" + canonicalChapterId,
                    canonicalChapterId = canonicalChapterId,
                    addonId = ADDON_ID,
                    language = null,
                    scanlationGroup = null,
                    releaseDate = artifact.completedAt,
                    delivery = delivery,
                ),
            ),
        )
    }

    companion object {
        val ADDON_ID = AddonId("tsuzuki.local-content")
    }
}