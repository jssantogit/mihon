package eu.kanade.tachiyomi.data.tsuzuki.addon

import eu.kanade.tachiyomi.data.tsuzuki.diagnosticHttpStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.addon.ContentProvider
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticEvent
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticFailures
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticOutcome
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticReason
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticStage
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnostics
import tachiyomi.domain.tsuzuki.chapter.diagnostics.NoOpChapterInventoryDiagnostics
import tachiyomi.domain.tsuzuki.chapter.diagnostics.recordIfEnabled
import tachiyomi.domain.tsuzuki.chapter.evidence.CanonicalChapterConfirmation
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceRepository
import tachiyomi.domain.tsuzuki.chapter.evidence.ProducerKind
import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterLabel
import tachiyomi.domain.tsuzuki.chapter.interactor.isInferredChapter
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
    private val parser: ParseCanonicalChapterLabel,
    private val fetchInventory: suspend (ContentBinding) -> Result<SourceChapterInventory>,
    private val materializeDelivery: suspend (
        ContentBinding,
        SourceChapterSnapshot,
    ) -> Result<ContentDelivery.Mihon>,
    private val chapterEvidenceRepository: ChapterEvidenceRepository? = null,
    private val diagnostics: ChapterInventoryDiagnostics = NoOpChapterInventoryDiagnostics,
) : ContentProvider {

    override suspend fun resolve(
        canonicalTitleId: String,
        canonicalChapterId: String,
    ): Result<List<ContentOption>> {
        return try {
            val bindings = contentBindingRepository.getByTitle(canonicalTitleId)
                .filter { it.addonId == addonId && it.availability == ContentBindingAvailability.AVAILABLE }
            if (bindings.isEmpty()) {
                recordProvider(
                    canonicalTitleId,
                    ChapterInventoryDiagnosticOutcome.NO_BINDING,
                    ChapterInventoryDiagnosticReason.NO_BINDING,
                )
                return Result.success(emptyList())
            }

            val canonicalChapter = canonicalChapterRepository.getById(canonicalChapterId)
                ?.takeIf { it.canonicalTitleId == canonicalTitleId }
                ?: return Result.success(emptyList<ContentOption>()).also {
                    recordProvider(
                        canonicalTitleId,
                        ChapterInventoryDiagnosticOutcome.NO_MATCH,
                        ChapterInventoryDiagnosticReason.NO_CHAPTER_VARIANT,
                    )
                }

            val variantIdentities = canonicalChapterRepository
                .getVariantsByCanonicalChapterId(canonicalChapterId)
                .mapNotNull(::sourceIdentity)
            val evidenceIdentities = chapterEvidenceRepository
                ?.getByCanonicalTitleId(canonicalTitleId)
                .orEmpty()
                .asSequence()
                .filter {
                    it.mappedCanonicalChapterId == canonicalChapterId &&
                        it.evidence.producerKind == ProducerKind.ADDON &&
                        it.evidence.producerId == addonId.value
                }
                .mapNotNull { persisted ->
                    persisted.evidence.externalChapterKey?.let(::sourceIdentity)
                }
                .toList()
            val sourceIdentities = (variantIdentities + evidenceIdentities).toSet()
            // A newly linked alternative Add-on may not have a persisted chapter
            // mapping yet. Its verified/high-confidence title binding is sufficient
            // to try an exact, high-confidence chapter identity match on demand.
            // Never infer a title binding from matching chapter numbers alone.
            val allowIdentityFallback = canonicalChapter.identity.isSpecific &&
                canonicalChapter.confirmation != CanonicalChapterConfirmation.CONFLICTED &&
                (
                    canonicalChapter.confidence >= MIN_TRUSTED_CHAPTER_CONFIDENCE ||
                        isInferredChapter(canonicalChapter)
                )
            if (sourceIdentities.isEmpty() &&
                (!allowIdentityFallback || bindings.none(::trustedBinding))
            ) {
                recordProvider(
                    canonicalTitleId,
                    ChapterInventoryDiagnosticOutcome.NO_MATCH,
                    ChapterInventoryDiagnosticReason.NO_CHAPTER_VARIANT,
                )
                return Result.success(emptyList())
            }

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

            val options = mutableListOf<ContentOption>()
            var firstFailure: Throwable? = null

            for ((binding, inventoryResult) in inventoryResults) {
                val inventory = inventoryResult.getOrElse { error ->
                    if (error is CancellationException) throw error
                    firstFailure = firstFailure ?: error
                    continue
                }

                if (inventory.canonicalTitleId != canonicalTitleId ||
                    inventory.sourceMappingId != binding.id
                ) {
                    firstFailure = firstFailure ?: IllegalStateException("Inventory binding mismatch")
                    continue
                }
                for (snapshot in inventory.chapters) {
                    if (snapshot.sourceMappingId != binding.id ||
                        snapshot.sourceId != inventory.sourceId
                    ) {
                        continue
                    }
                    val identity = sourceIdentity(snapshot) ?: continue

                    // Stable provider evidence is preferred. For a newly linked
                    // alternative, permit only an exact, confident chapter identity
                    // from a trusted binding. Never offer chapter 126 as chapter 4
                    // even if an old provider URL was reused.
                    val parsed = parser.execute(snapshot.rawName, snapshot.rawNumberHint)
                    val mapped = identity in sourceIdentities
                    val exactFallback = allowIdentityFallback &&
                        trustedBinding(binding) &&
                        parsed.confidence >= MIN_TRUSTED_CHAPTER_CONFIDENCE &&
                        parsed.identity.isSpecific &&
                        parsed.identity == canonicalChapter.identity
                    if (!mapped && !exactFallback) continue
                    if (canonicalChapter.identity.isSpecific &&
                        (!parsed.identity.isSpecific || parsed.identity != canonicalChapter.identity)
                    ) {
                        continue
                    }

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

            if (options.isEmpty() && firstFailure != null) {
                val (outcome, reason) = ChapterInventoryDiagnosticFailures.classify(firstFailure)
                recordProvider(
                    canonicalTitleId,
                    outcome,
                    reason,
                    httpStatus = firstFailure.diagnosticHttpStatus(),
                )
                Result.failure(firstFailure)
            } else {
                val distinct = options.distinctBy(ContentOption::key)
                recordProvider(
                    canonicalTitleId,
                    when {
                        distinct.isNotEmpty() && firstFailure == null -> ChapterInventoryDiagnosticOutcome.SUCCESS
                        distinct.isNotEmpty() -> ChapterInventoryDiagnosticOutcome.PARTIAL
                        else -> ChapterInventoryDiagnosticOutcome.NO_MATCH
                    },
                    when {
                        firstFailure != null -> ChapterInventoryDiagnosticReason.PROVIDER_FAILED
                        distinct.isEmpty() -> ChapterInventoryDiagnosticReason.NO_CHAPTER_VARIANT
                        else -> null
                    },
                    accepted = distinct.size,
                )
                Result.success(distinct)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val (outcome, reason) = ChapterInventoryDiagnosticFailures.classify(error)
            recordProvider(
                canonicalTitleId,
                outcome,
                reason,
                httpStatus = error.diagnosticHttpStatus(),
            )
            Result.failure(error)
        }
    }

    private fun recordProvider(
        canonicalTitleId: String,
        outcome: ChapterInventoryDiagnosticOutcome,
        reason: ChapterInventoryDiagnosticReason? = null,
        accepted: Int = 0,
        httpStatus: Int? = null,
    ) {
        diagnostics.recordIfEnabled(
            canonicalTitleId,
            ChapterInventoryDiagnosticEvent(
                stage = ChapterInventoryDiagnosticStage.CONTENT_PROVIDER,
                outcome = outcome,
                addonId = addonId.value,
                httpStatus = httpStatus,
                accepted = accepted,
                availabilityBlocked = accepted == 0,
                affectedSourceCount = if (accepted == 0) 1 else 0,
                reasons = reason?.let { mapOf(it to 1) }.orEmpty(),
            ),
        )
    }

    private fun trustedBinding(binding: ContentBinding): Boolean =
        binding.verifiedByUser || binding.matchConfidence >= MIN_TRUSTED_BINDING_CONFIDENCE

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

    private fun sourceIdentity(externalChapterKey: String): Pair<Long, String>? {
        val separator = externalChapterKey.indexOf(':')
        if (separator <= 0 || separator == externalChapterKey.lastIndex) return null
        val sourceId = externalChapterKey.substring(0, separator).toLongOrNull() ?: return null
        val chapterKey = externalChapterKey.substring(separator + 1)
        return sourceId to chapterKey
    }

    private fun contentKey(snapshot: SourceChapterSnapshot): String {
        val chapterKey = snapshot.sourceChapterId.ifBlank { snapshot.sourceChapterUrl }
        return addonId.value + ":" + snapshot.sourceId + ":" + chapterKey
    }

    private companion object {
        const val MAX_CONCURRENT_INVENTORY_FETCHES = 4
        const val MIN_TRUSTED_BINDING_CONFIDENCE = 0.97
        const val MIN_TRUSTED_CHAPTER_CONFIDENCE = 0.95
    }
}
