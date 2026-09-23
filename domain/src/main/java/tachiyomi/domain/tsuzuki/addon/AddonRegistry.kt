package tachiyomi.domain.tsuzuki.addon

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

interface AddonRegistry {
    suspend fun awaitReady() = Unit

    fun observeChanges(): Flow<Unit> = emptyFlow()

    fun contentProviders(): List<ContentProvider>

    fun chapterProbeProviders(): List<ChapterProbeProvider>
}
