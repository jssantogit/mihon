package eu.kanade.tachiyomi.data.tsuzuki.addon

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.addon.ChapterProbeProvider
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceAuthority
import tachiyomi.domain.tsuzuki.chapter.evidence.ProducerKind
import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterLabel
import tachiyomi.domain.tsuzuki.chapter.model.SourceChapterInventory
import tachiyomi.domain.tsuzuki.content.ContentBinding
import tachiyomi.domain.tsuzuki.content.ContentBindingAvailability
import tachiyomi.domain.tsuzuki.content.repository.ContentBindingRepository
import java.util.UUID
import kotlin.time.Clock

class MihonChapterProbeProvider internal constructor(
    override val addonId: AddonId,
    private val contentBindingRepository: ContentBindingRepository,
    private val parser: ParseCanonicalChapterLabel,
    private val fetchInventory: suspend (ContentBinding) -> Result<SourceChapterInventory>,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : ChapterProbeProvider {

    override suspend fun probe(canonicalTitleId: String): Result<List<ChapterEvidence>> {
        return try {
            val bindings = contentBindingRepository.getByTitle(canonicalTitleId)
                .filter { it.addonId == addonId && it.availability == ContentBindingAvailability.AVAILABLE }
            if (bindings.isEmpty()) return Result.success(emptyList())

            val fetchGate = Semaphore(MAX_CONCURRENT_INVENTORY_FETCHES)
            val inventoryResults = coroutineScope {
                bindings.map { binding ->
                    async {
                        binding to fetchGate.withPermit {
                            try {
                                fetchInventory(binding)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Throwable) {
                                Result.failure(error)
                            }
                        }
                    }
                }.awaitAll()
            }

            val evidence = mutableListOf<ChapterEvidence>()
            var firstFailure: Throwable? = null
            var successfulInventoryCount = 0

            for ((_, inventoryResult) in inventoryResults) {
                val inventory = inventoryResult.getOrElse { error ->
                    if (error is CancellationException) throw error
                    firstFailure = firstFailure ?: error
                    continue
                }
                successfulInventoryCount++
                val observedAt = clock()

                for (snapshot in inventory.chapters) {
                    val externalKey = snapshot.sourceChapterId
                        .takeIf(String::isNotBlank)
                        ?: snapshot.sourceChapterUrl.takeIf(String::isNotBlank)
                        ?: continue
                    val parsed = parser.execute(snapshot.rawName, snapshot.rawNumberHint)
                    evidence += ChapterEvidence(
                        id = evidenceId(canonicalTitleId, snapshot.sourceId, externalKey),
                        canonicalTitleId = canonicalTitleId,
                        producerKind = ProducerKind.ADDON,
                        producerId = addonId.value,
                        externalChapterKey = snapshot.sourceId.toString() + ":" + externalKey,
                        rawLabel = snapshot.rawName,
                        rawNumber = snapshot.rawNumberHint,
                        volume = null,
                        title = null,
                        observedAt = observedAt,
                        confidence = parsed.confidence,
                        authority = ChapterEvidenceAuthority.ADDON_PROVISIONAL,
                    )
                }
            }

            if (evidence.isEmpty() && successfulInventoryCount == 0 && firstFailure != null) {
                Result.failure(firstFailure)
            } else {
                Result.success(evidence.distinctBy(ChapterEvidence::id))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private fun evidenceId(
        canonicalTitleId: String,
        sourceId: Long,
        externalKey: String,
    ): String {
        val stableKey = addonId.value + "|" + canonicalTitleId + "|" + sourceId + "|" + externalKey
        return UUID.nameUUIDFromBytes(stableKey.encodeToByteArray()).toString()
    }

    private companion object {
        const val MAX_CONCURRENT_INVENTORY_FETCHES = 4
    }
}
