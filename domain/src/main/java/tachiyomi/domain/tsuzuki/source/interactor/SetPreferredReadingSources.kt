package tachiyomi.domain.tsuzuki.source.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.source.repository.ReadingSourcePreferenceRepository

@Inject
class SetPreferredReadingSources(
    private val repository: ReadingSourcePreferenceRepository,
) {
    suspend fun execute(language: String, orderedSourceIds: List<Long>) {
        repository.replaceForLanguage(language, orderedSourceIds)
    }
}
