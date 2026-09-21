package tachiyomi.domain.tsuzuki.sync.adapter

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReadingRepository
import tachiyomi.domain.tsuzuki.sync.model.CanonicalChapterSyncKey
import tachiyomi.domain.tsuzuki.sync.model.CanonicalVariantSyncKey
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentEnvelope
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.model.SyncRecordEnvelope
import tachiyomi.domain.tsuzuki.sync.service.CanonicalTitleSyncSource
import tachiyomi.domain.tsuzuki.sync.service.ChapterSyncEvidenceRepository
import tachiyomi.domain.tsuzuki.sync.service.SyncClock
import tachiyomi.domain.tsuzuki.sync.service.SyncDocumentAdapter
import tachiyomi.domain.tsuzuki.sync.service.SyncRevisionSource

class CanonicalReadingProgressSyncAdapter(
    private val titleSource: CanonicalTitleSyncSource,
    private val chapterRepository: CanonicalChapterRepository,
    private val readingRepository: CanonicalReadingRepository,
    private val evidenceRepository: ChapterSyncEvidenceRepository,
    private val revisionSource: SyncRevisionSource,
    private val clock: SyncClock,
) : SyncDocumentAdapter {

    override val documentKind = SyncDocumentKind.READING_PROGRESS

    override suspend fun exportDocument(): SyncDocumentEnvelope {
        val records = linkedMapOf<String, SyncRecordEnvelope>()

        titleSource.getAllTitles()
            .sortedBy { it.id }
            .forEach { title ->
                val chapters = chapterRepository
                    .getByCanonicalTitleId(title.id)
                    .associateBy(CanonicalChapter::id)

                readingRepository.getProgressByCanonicalTitleId(title.id)
                    .sortedBy(CanonicalChapterProgress::canonicalChapterId)
                    .forEach { progress ->
                        val chapter = chapters[progress.canonicalChapterId]
                            ?: return@forEach
                        val stableEvidence = evidenceRepository
                            .getStableEvidenceKey(chapter.id)
                        val syncKey = CanonicalChapterSyncKey.from(
                            chapter = chapter,
                            stableEvidenceKey = stableEvidence,
                        ) ?: return@forEach

                        val variantEvidenceKey = resolveVariantEvidenceKey(
                            progress = progress,
                        )

                        records[syncKey.recordId] = SyncRecordEnvelope(
                            id = syncKey.recordId,
                            revision = revisionSource.nextRevision(),
                            updatedAtEpochMillis = progress.updatedAt,
                            fields = buildJsonObject {
                                put("canonicalTitleId", syncKey.canonicalTitleId)
                                put("chapterKey", syncKey.chapterKey)
                                put("read", progress.read)
                                put("lastPageRead", progress.lastPageRead)
                                put(
                                    "lastVariantEvidenceKey",
                                    variantEvidenceKey?.let(::JsonPrimitive) ?: JsonNull,
                                )
                            },
                        )
                    }
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
            "Reading progress adapter cannot apply ${document.kind}"
        }

        document.records.values
            .sortedBy(SyncRecordEnvelope::id)
            .forEach { record ->
                require(!record.isTombstone) {
                    "Reading progress tombstones are not supported"
                }

                val canonicalTitleId = record.fields.requiredString("canonicalTitleId")
                val chapterKey = record.fields.requiredString("chapterKey")
                val localChapter = resolveLocalChapter(
                    canonicalTitleId = canonicalTitleId,
                    chapterKey = chapterKey,
                )
                val variantEvidenceKey = record.fields.optionalString(
                    "lastVariantEvidenceKey",
                )
                val localVariantId = variantEvidenceKey?.let { evidenceKey ->
                    chapterRepository
                        .getVariantsByCanonicalChapterId(localChapter.id)
                        .singleOrNull { variant ->
                            CanonicalVariantSyncKey.from(variant) == evidenceKey
                        }
                        ?.id
                }

                readingRepository.upsertProgress(
                    CanonicalChapterProgress(
                        canonicalChapterId = localChapter.id,
                        read = record.fields.requiredBoolean("read"),
                        lastPageRead = record.fields.requiredLong("lastPageRead"),
                        lastVariantId = localVariantId,
                        updatedAt = record.updatedAtEpochMillis,
                    ),
                )
            }
    }

    override suspend fun hasUnportableLocalState(): Boolean {
        for (title in titleSource.getAllTitles()) {
            val chapters = chapterRepository
                .getByCanonicalTitleId(title.id)
                .associateBy(CanonicalChapter::id)
            for (progress in readingRepository.getProgressByCanonicalTitleId(title.id)) {
                val chapter = chapters[progress.canonicalChapterId] ?: return true
                val stableEvidence = evidenceRepository.getStableEvidenceKey(chapter.id)
                if (
                    CanonicalChapterSyncKey.from(
                        chapter = chapter,
                        stableEvidenceKey = stableEvidence,
                    ) == null
                ) {
                    return true
                }
            }
        }
        return false
    }

    private suspend fun resolveVariantEvidenceKey(
        progress: CanonicalChapterProgress,
    ): String? {
        val variantId = progress.lastVariantId ?: return null
        return chapterRepository
            .getVariantsByCanonicalChapterId(progress.canonicalChapterId)
            .singleOrNull { it.id == variantId }
            ?.let(CanonicalVariantSyncKey::from)
    }

    private suspend fun resolveLocalChapter(
        canonicalTitleId: String,
        chapterKey: String,
    ): CanonicalChapter {
        val matches = mutableListOf<CanonicalChapter>()
        for (chapter in chapterRepository.getByCanonicalTitleId(canonicalTitleId)) {
            val stableEvidence = evidenceRepository.getStableEvidenceKey(chapter.id)
            val localKey = CanonicalChapterSyncKey.from(
                chapter = chapter,
                stableEvidenceKey = stableEvidence,
            )
            if (localKey?.chapterKey == chapterKey) {
                matches += chapter
            }
        }

        return requireNotNull(matches.singleOrNull()) {
            "Portable chapter key does not resolve to exactly one local chapter"
        }
    }

    private companion object {
        const val SCHEMA_VERSION = 1
    }
}

private fun JsonObject.requiredString(name: String): String =
    getValue(name).jsonPrimitive.content

private fun JsonObject.requiredLong(name: String): Long =
    getValue(name).jsonPrimitive.long

private fun JsonObject.requiredBoolean(name: String): Boolean =
    getValue(name).jsonPrimitive.boolean

private fun JsonObject.optionalString(name: String): String? {
    val value = getValue(name)
    return if (value is JsonNull) null else value.jsonPrimitive.content
}
