package eu.kanade.tachiyomi.data.tsuzuki.addon

import kotlinx.coroutines.CancellationException
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.addon.ContentProvider
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.chapter.model.SourceChapterInventory
import tachiyomi.domain.tsuzuki.chapter.model.SourceChapterSnapshot
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.content.ContentBinding
import tachiyomi.domain.tsuzuki.content.ContentBindingAvailability
import tachiyomi.domain.tsuzuki.content.ContentDelivery
import tachiyomi.domain.tsuzuki.content.ContentOption
import tachiyomi.domain.tsuzuki.content.repository.ContentBindingRepository

class MihonContentProvider internal constructor(
    override val addonId: AddonId,
    private val contentBindingRepository: ContentBindingRepository,
    private val canonicalChapterRepository: CanonicalChapterRepository,
    private val fetchInventory: suspend (ContentBinding) -> Result<SourceChapterInventory>,
    private val materializeDelivery: suspend (
        ContentBinding,
        SourceChapterSnapshot,
    ) -> Result<ContentDelivery.Mihon>,
) : ContentProvider {

    override suspend fun resolve(
        canonicalTitleId: String,
        canonicalChapterId: String,
    ): Result<List<ContentOption>> {
        return try {
            val bindings = contentBindingRepository.getByTitle(canonicalTitleId)
                .filter { it.addonId == addonId && it.availability == ContentBindingAvailability.AVAILABLE }
            if (bindings.isEmpty()) return Result.success(emptyList())

            val variants = canonicalChapterRepository.getVariantsByCanonicalChapterId(canonicalChapterId)
            val sourceIdentities = variants.mapNotNull(::sourceIdentity).toSet()
            if (sourceIdentities.isEmpty()) return Result.success(emptyList())

            val options = mutableListOf<ContentOption>()
            var firstFailure: Throwable? = null
            var successfulInventoryCount = 0

            for (binding in bindings) {
                val inventory = fetchInventory(binding).getOrElse { error ->
                    if (error is CancellationException) throw error
                    firstFailure = firstFailure ?: error
                    continue
                }
                successfulInventoryCount++

                for (snapshot in inventory.chapters) {
                    val identity = sourceIdentity(snapshot) ?: continue
                    if (identity !in sourceIdentities) continue

                    val delivery = materializeDelivery(binding, snapshot).getOrElse { error ->
                        if (error is CancellationException) throw error
                        firstFailure = firstFailure ?: error
                        continue
                    }
                    options += ContentOption(
                        key = contentKey(snapshot),
                        canonicalChapterId = canonicalChapterId,
                        addonId = addonId,
                        language = snapshot.language.takeIf(String::isNotBlank),
                        scanlationGroup = snapshot.scanlationGroup,
                        releaseDate = snapshot.releaseDate?.takeIf { it > 0L },
                        delivery = delivery,
                    )
                }
            }

            if (options.isEmpty() && successfulInventoryCount == 0 && firstFailure != null) {
                Result.failure(firstFailure)
            } else {
                Result.success(options.distinctBy(ContentOption::key))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private fun sourceIdentity(variant: ChapterVariant): Pair<Long, String>? {
        val chapterKey = variant.sourceChapterId
            .takeIf(String::isNotBlank)
            ?: variant.sourceChapterUrl?.takeIf(String::isNotBlank)
            ?: return null
        return variant.sourceId to chapterKey
    }

    private fun sourceIdentity(snapshot: SourceChapterSnapshot): Pair<Long, String>? {
        val chapterKey = snapshot.sourceChapterId
            .takeIf(String::isNotBlank)
            ?: snapshot.sourceChapterUrl.takeIf(String::isNotBlank)
            ?: return null
        return snapshot.sourceId to chapterKey
    }

    private fun contentKey(snapshot: SourceChapterSnapshot): String {
        val chapterKey = snapshot.sourceChapterId.ifBlank { snapshot.sourceChapterUrl }
        return addonId.value + ":" + snapshot.sourceId + ":" + chapterKey
    }
}
