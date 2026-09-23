package tachiyomi.domain.tsuzuki.sync.adapter

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.chapter.update.model.CanonicalChapterUpdateState
import tachiyomi.domain.tsuzuki.chapter.update.repository.ChapterUpdateStateRepository
import tachiyomi.domain.tsuzuki.sync.model.CanonicalChapterSyncKey
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentEnvelope
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.model.SyncRecordEnvelope
import tachiyomi.domain.tsuzuki.sync.service.ChapterSyncEvidenceRepository
import tachiyomi.domain.tsuzuki.sync.service.SyncClock
import tachiyomi.domain.tsuzuki.sync.service.SyncDocumentAdapter
import tachiyomi.domain.tsuzuki.sync.service.SyncRevisionSource

class ChapterUpdateStateSyncAdapter(
    private val stateRepository: ChapterUpdateStateRepository,
    private val chapterRepository: CanonicalChapterRepository,
    private val evidenceRepository: ChapterSyncEvidenceRepository,
    private val revisionSource: SyncRevisionSource,
    private val clock: SyncClock,
) : SyncDocumentAdapter {

    override val documentKind = SyncDocumentKind.CHAPTER_UPDATE_STATE

    override suspend fun exportDocument(): SyncDocumentEnvelope {
        val records = linkedMapOf<String, SyncRecordEnvelope>()

        stateRepository.getAll()
            .sortedWith(
                compareBy(
                    CanonicalChapterUpdateState::canonicalTitleId,
                    CanonicalChapterUpdateState::canonicalChapterId,
                ),
            )
            .forEach { state ->
                val chapter = chapterRepository.getById(state.canonicalChapterId)
                    ?: return@forEach
                val key = portableKey(chapter) ?: return@forEach
                records[key.recordId] = SyncRecordEnvelope(
                    id = key.recordId,
                    revision = revisionSource.nextRevision(),
                    updatedAtEpochMillis = state.acknowledgedAt ?: state.firstSeenAt,
                    fields = buildJsonObject {
                        put("canonicalTitleId", key.canonicalTitleId)
                        put("chapterKey", key.chapterKey)
                        if (state.acknowledgedAt != null) {
                            put("acknowledged", true)
                        }
                    },
                )
            }

        return SyncDocumentEnvelope(
            schemaVersion = SCHEMA_VERSION,
            kind = documentKind,
            revision = revisionSource.nextRevision(),
            generatedAtEpochMillis = clock.nowEpochMillis(),
            records = records,
        )
    }

    override suspend fun applyDocument(document: SyncDocumentEnvelope) {
        require(document.kind == documentKind) {
            "Chapter update state adapter cannot apply ${document.kind}"
        }

        document.records.values
            .sortedBy(SyncRecordEnvelope::id)
            .forEach { record ->
                val chapter = resolveLocalChapter(
                    canonicalTitleId = record.fields.requiredString("canonicalTitleId"),
                    chapterKey = record.fields.requiredString("chapterKey"),
                )
                if (record.isTombstone) {
                    stateRepository.delete(chapter.id)
                    return@forEach
                }

                val existing = stateRepository.getByTitle(chapter.canonicalTitleId)
                    .find { it.canonicalChapterId == chapter.id }
                val acknowledged = record.fields["acknowledged"]
                    ?.jsonPrimitive
                    ?.booleanOrNull == true

                stateRepository.upsert(
                    CanonicalChapterUpdateState(
                        canonicalChapterId = chapter.id,
                        canonicalTitleId = chapter.canonicalTitleId,
                        firstSeenAt = existing?.firstSeenAt ?: record.updatedAtEpochMillis,
                        acknowledgedAt = existing?.acknowledgedAt
                            ?: record.updatedAtEpochMillis.takeIf { acknowledged },
                    ),
                )
            }
    }

    override suspend fun hasUnportableLocalState(): Boolean {
        return stateRepository.getAll().any { state ->
            val chapter = chapterRepository.getById(state.canonicalChapterId)
                ?: return@any true
            portableKey(chapter) == null
        }
    }

    private suspend fun portableKey(
        chapter: CanonicalChapter,
    ): CanonicalChapterSyncKey? {
        return CanonicalChapterSyncKey.from(
            chapter = chapter,
            stableEvidenceKey = evidenceRepository.getStableEvidenceKey(chapter.id),
        )
    }

    private suspend fun resolveLocalChapter(
        canonicalTitleId: String,
        chapterKey: String,
    ): CanonicalChapter {
        val matches = mutableListOf<CanonicalChapter>()
        for (chapter in chapterRepository.getByCanonicalTitleId(canonicalTitleId)) {
            if (portableKey(chapter)?.chapterKey == chapterKey) {
                matches += chapter
            }
        }
        return requireNotNull(matches.singleOrNull()) {
            "Chapter update sync key does not resolve to exactly one local chapter"
        }
    }

    private companion object {
        const val SCHEMA_VERSION = 1
    }
}

private fun JsonObject.requiredString(name: String): String =
    getValue(name).jsonPrimitive.content
