package tachiyomi.domain.tsuzuki.chapter.interactor

import tachiyomi.domain.tsuzuki.chapter.evidence.CanonicalChapterConfirmation
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterIdentity
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.metadata.ReportedChapterCount

/**
 * A count contributes display slots to the title outline, not upstream chapter
 * identities or fake content releases. Actual evidence always wins for the
 * same structured regular chapter identity.
 */
data class CanonicalChapterOutlineEntry(
    val chapter: CanonicalChapter,
    val inferredFromReportedCount: Boolean,
)

/** Stable UI/DB identity only for a user-selected count-derived numbered slot. */
fun inferredChapterId(canonicalTitleId: String, number: Int): String =
    "tsuzuki:count:$canonicalTitleId:$number"

fun isInferredChapter(chapter: CanonicalChapter): Boolean =
    chapter.type == CanonicalChapterType.REGULAR &&
        chapter.baseNumber?.let { it > 0 && chapter.id == inferredChapterId(chapter.canonicalTitleId, it) } == true &&
        chapter.part == null &&
        chapter.alphaSuffix == null

fun buildCanonicalChapterOutline(
    canonicalTitleId: String,
    observedChapters: List<CanonicalChapter>,
    reportedCounts: List<ReportedChapterCount>,
): List<CanonicalChapterOutlineEntry> {
    require(observedChapters.all { it.canonicalTitleId == canonicalTitleId })
    val expectedCount = reportedCounts.asSequence()
        .filter { it.canonicalTitleId == canonicalTitleId }
        .mapNotNull { it.chapterCount?.takeIf { count -> count > 0 } }
        .maxOrNull()
        ?.coerceAtMost(MAX_DISPLAY_CHAPTERS)
        ?: 0

    val observedRegular = observedChapters.asSequence()
        .map(CanonicalChapter::identity)
        .filter {
            it.type == CanonicalChapterType.REGULAR &&
                it.baseNumber != null &&
                it.part == null &&
                it.alphaSuffix == null
        }
        .toSet()
    val observedRows = observedChapters.map { chapter ->
        CanonicalChapterOutlineEntry(
            chapter = chapter,
            inferredFromReportedCount = isInferredChapter(chapter),
        )
    }
    val inferredRows = (1..expectedCount)
        .asSequence()
        .filter { number ->
            CanonicalChapterIdentity(type = CanonicalChapterType.REGULAR, baseNumber = number) !in observedRegular
        }
        .map { number ->
            CanonicalChapterOutlineEntry(
                chapter = CanonicalChapter(
                    id = inferredChapterId(canonicalTitleId, number),
                    canonicalTitleId = canonicalTitleId,
                    displayNumber = number.toString(),
                    type = CanonicalChapterType.REGULAR,
                    baseNumber = number,
                    confidence = 0.0,
                    confirmation = CanonicalChapterConfirmation.PROVISIONAL,
                ),
                inferredFromReportedCount = true,
            )
        }
        .toList()
    return (observedRows + inferredRows)
        .sortedWith(compareBy({ it.chapter.identity }, { it.chapter.id }))
}

private const val MAX_DISPLAY_CHAPTERS = 5_000
