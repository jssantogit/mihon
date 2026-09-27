package tachiyomi.domain.tsuzuki.chapter.evidence

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterVolume
import tachiyomi.domain.tsuzuki.chapter.model.SourceChapterInventory
import tachiyomi.domain.tsuzuki.chapter.model.SourceChapterSnapshot
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Read-only compatibility adapter from materialized Mihon inventory to source-scoped
 * provisional evidence. No ContentBinding is required for a legacy Reader mapping.
 *
 * This adapter does NOT publish ChapterVariants or replace the legacy writer until
 * evidence and operational variants can be committed together transactionally.
 */
@Inject
class LegacyInventoryEvidenceAdapter(
    private val volumeParser: ParseCanonicalChapterVolume,
) {
    fun adapt(inventory: SourceChapterInventory, observedAt: Long): List<ChapterEvidence> {
        require(inventory.canonicalTitleId.isNotBlank()) { "Canonical title is required" }
        require(inventory.sourceMappingId.isNotBlank()) { "Legacy source mapping is required" }
        require(observedAt >= 0L) { "Observation timestamp must not be negative" }
        // Fall back only for older inventories without original fetch provenance.
        // A later cache replay must not become a newer provider observation.
        val originalFetchAt = inventory.fetchStartedAtMillis ?: observedAt
        require(originalFetchAt >= 0L) { "Provider fetch timestamp must not be negative" }

        // The provider can repeat identical URLs; a single inventory assigning
        // two different labels or number hints to the same URL is unsafe.
        val uniqueBySourceKey = linkedMapOf<String, SourceChapterSnapshot>()
        for (snapshot in inventory.chapters) {
            require(snapshot.sourceId == inventory.sourceId) { "Source identity mismatch" }
            require(snapshot.sourceMappingId == inventory.sourceMappingId) { "Source mapping mismatch" }
            val key = snapshot.sourceChapterId.ifBlank { snapshot.sourceChapterUrl }
            require(key.isNotBlank()) { "A stable source chapter key is required" }
            val previous = uniqueBySourceKey.putIfAbsent(key, snapshot)
            require(
                previous == null ||
                    (
                        previous.rawName.trim() == snapshot.rawName.trim() &&
                            previous.rawNumberHint == snapshot.rawNumberHint
                        ),
            ) { "Conflicting chapter observations reuse one source key" }
        }

        // Isolate legacy sources from real Add-on producer identities and from
        // the same relative chapter URL on a different canonical title.
        val producerId = "mihon-legacy:" + inventory.canonicalTitleId + ":" + inventory.sourceId
        return uniqueBySourceKey.map { (sourceKey, snapshot) ->
            // Stable across refresh order, labels, languages and timestamps.
            // Length-prefixing avoids collisions between concatenated fields.
            val stableKey = producerId.length.toString() + ":" + producerId +
                ":" + sourceKey.length + ":" + sourceKey
            val stableId = UUID.nameUUIDFromBytes(
                stableKey.toByteArray(StandardCharsets.UTF_8),
            ).toString()
            ChapterEvidence(
                id = stableId,
                canonicalTitleId = inventory.canonicalTitleId,
                producerKind = ProducerKind.ADDON,
                producerId = producerId,
                externalChapterKey = sourceKey,
                rawLabel = snapshot.rawName,
                // The Mihon number is a hint, not a verified chapter identity.
                rawNumber = snapshot.rawNumberHint?.takeIf { it.isFinite() },
                volume = volumeParser.execute(snapshot.rawName),
                title = null,
                observedAt = originalFetchAt,
                confidence = 1.0,
                authority = ChapterEvidenceAuthority.ADDON_PROVISIONAL,
            )
        }
    }
}
