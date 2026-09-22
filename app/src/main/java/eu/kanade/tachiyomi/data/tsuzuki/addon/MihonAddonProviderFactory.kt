package eu.kanade.tachiyomi.data.tsuzuki.addon

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.tsuzuki.MihonChapterInventoryGateway
import kotlinx.coroutines.CancellationException
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceRepository
import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterLabel
import tachiyomi.domain.tsuzuki.chapter.model.SourceChapterSnapshot
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.content.ContentBinding
import tachiyomi.domain.tsuzuki.content.ContentDelivery
import tachiyomi.domain.tsuzuki.content.repository.ContentBindingRepository

@Inject
@SingleIn(AppScope::class)
class MihonAddonProviderFactory(
    private val contentBindingRepository: ContentBindingRepository,
    private val canonicalChapterRepository: CanonicalChapterRepository,
    private val chapterEvidenceRepository: ChapterEvidenceRepository,
    private val parser: ParseCanonicalChapterLabel,
    private val chapterInventoryGateway: MihonChapterInventoryGateway,
) {

    fun contentProvider(addonId: AddonId) = MihonContentProvider(
        addonId = addonId,
        contentBindingRepository = contentBindingRepository,
        canonicalChapterRepository = canonicalChapterRepository,
        parser = parser,
        fetchInventory = { binding -> chapterInventoryGateway.fetch(binding) },
        chapterEvidenceRepository = chapterEvidenceRepository,
        materializeDelivery = ::materializeDelivery,
    )

    fun chapterProbeProvider(addonId: AddonId) = MihonChapterProbeProvider(
        addonId = addonId,
        contentBindingRepository = contentBindingRepository,
        parser = parser,
        fetchInventory = { binding -> chapterInventoryGateway.fetch(binding) },
    )

    private suspend fun materializeDelivery(
        binding: ContentBinding,
        snapshot: SourceChapterSnapshot,
    ): Result<ContentDelivery.Mihon> {
        return try {
            val payload = MihonContentBindingPayloadCodec.decode(binding.runtimePayload)
            chapterInventoryGateway.materializeOperationalChapter(snapshot).map { chapterId ->
                ContentDelivery.Mihon(
                    sourceId = snapshot.sourceId,
                    mangaId = snapshot.mihonMangaId ?: payload.mihonMangaId,
                    chapterId = chapterId,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }
}
