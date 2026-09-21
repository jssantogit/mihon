package tachiyomi.domain.tsuzuki.reader.service

import tachiyomi.domain.tsuzuki.content.ContentOption
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress
import tachiyomi.domain.tsuzuki.reader.model.PreparedChapterContent

interface ChapterContentPreparer {
    suspend fun prepare(
        option: ContentOption,
        progress: CanonicalChapterProgress?,
    ): Result<PreparedChapterContent>
}
