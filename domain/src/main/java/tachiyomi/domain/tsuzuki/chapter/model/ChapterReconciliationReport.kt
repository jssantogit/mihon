package tachiyomi.domain.tsuzuki.chapter.model

/** The non-destructive result of reconciling one or more source inventories. */
data class ChapterReconciliationReport(
    val canonicalTitleId: String,
    val canonicalChapters: List<CanonicalChapter> = emptyList(),
    val variants: List<ChapterVariant> = emptyList(),
    val sourceMappingIds: Set<String> = variants.map { it.sourceMappingId }.toSet(),
    val createdCanonicalChapterIds: Set<String> = emptySet(),
) {
    val chapters: List<CanonicalChapter>
        get() = canonicalChapters

    val canonicalChapterIds: Set<String>
        get() = canonicalChapters.map { it.id }.toSet()

    val variantIds: Set<String>
        get() = variants.map { it.id }.toSet()
}
