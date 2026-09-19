package tachiyomi.domain.tsuzuki.chapter.service

import tachiyomi.domain.tsuzuki.chapter.model.SourceChapterInventory
import tachiyomi.domain.tsuzuki.model.SourceTitleMapping

/** Adapter boundary for reading source chapter evidence without mutating Mihon. */
interface ChapterInventoryGateway {
    suspend fun fetch(mapping: SourceTitleMapping): Result<SourceChapterInventory>

    suspend fun getInventory(mapping: SourceTitleMapping): Result<SourceChapterInventory> = fetch(mapping)

    suspend fun load(mapping: SourceTitleMapping): Result<SourceChapterInventory> = fetch(mapping)
}
