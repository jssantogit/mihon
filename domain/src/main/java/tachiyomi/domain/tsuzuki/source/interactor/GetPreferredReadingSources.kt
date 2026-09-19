package tachiyomi.domain.tsuzuki.source.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.tsuzuki.source.model.ReadingSourcePreference
import tachiyomi.domain.tsuzuki.source.repository.ReadingSourcePreferenceRepository

@Inject
class GetPreferredReadingSources(
    private val repository: ReadingSourcePreferenceRepository,
) {
    suspend fun await(language: String): List<ReadingSourcePreference> {
        return repository.getForLanguage(language)
    }

    fun subscribe(language: String): Flow<List<ReadingSourcePreference>> {
        return repository.observeForLanguage(language)
    }

    suspend fun getConfiguredLanguages(): List<String> {
        return repository.getConfiguredLanguages()
    }
}
