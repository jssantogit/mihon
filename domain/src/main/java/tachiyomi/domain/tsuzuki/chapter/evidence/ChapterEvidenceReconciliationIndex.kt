package tachiyomi.domain.tsuzuki.chapter.evidence

import tachiyomi.domain.tsuzuki.chapter.model.ParsedChapterLabel

internal class ChapterEvidenceReconciliationIndex(
    initial: Collection<PersistedChapterEvidence>,
) {
    private val byExternalKey = linkedMapOf<String, LinkedHashMap<String, PersistedChapterEvidence>>()
    private val byMappedChapter = linkedMapOf<String, LinkedHashMap<String, PersistedChapterEvidence>>()

    init {
        initial.forEach(::add)
    }

    fun externalKeyCandidates(externalChapterKey: String): Collection<PersistedChapterEvidence> =
        byExternalKey[externalChapterKey]?.values.orEmpty()

    fun mappedChapterCandidates(canonicalChapterId: String): Collection<PersistedChapterEvidence> =
        byMappedChapter[canonicalChapterId]?.values.orEmpty()

    fun replace(
        previous: PersistedChapterEvidence?,
        current: PersistedChapterEvidence,
    ) {
        previous?.let(::remove)
        add(current)
    }

    private fun add(persisted: PersistedChapterEvidence) {
        persisted.evidence.externalChapterKey?.let { key ->
            byExternalKey.getOrPut(key) { linkedMapOf() }[persisted.evidence.id] = persisted
        }
        persisted.mappedCanonicalChapterId?.let { chapterId ->
            byMappedChapter.getOrPut(chapterId) { linkedMapOf() }[persisted.evidence.id] = persisted
        }
    }

    private fun remove(persisted: PersistedChapterEvidence) {
        persisted.evidence.externalChapterKey?.let { key ->
            byExternalKey[key]?.let { bucket ->
                bucket.remove(persisted.evidence.id)
                if (bucket.isEmpty()) byExternalKey.remove(key)
            }
        }
        persisted.mappedCanonicalChapterId?.let { chapterId ->
            byMappedChapter[chapterId]?.let { bucket ->
                bucket.remove(persisted.evidence.id)
                if (bucket.isEmpty()) byMappedChapter.remove(chapterId)
            }
        }
    }
}

internal class ChapterEvidenceParseCache(
    private val parse: (String?, Number?) -> ParsedChapterLabel,
) {
    private data class Key(
        val rawLabel: String,
        val rawNumber: Double?,
    )

    private val values = mutableMapOf<Key, ParsedChapterLabel>()

    fun parse(evidence: ChapterEvidence): ParsedChapterLabel {
        val key = Key(evidence.rawLabel, evidence.rawNumber)
        return values.getOrPut(key) {
            parse(evidence.rawLabel, evidence.rawNumber)
        }
    }
}
