package tachiyomi.domain.tsuzuki.reader.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.reader.model.CanonicalReaderPreferences

@Inject
class SetCanonicalAutomaticFallback(
    private val preferences: CanonicalReaderPreferences,
) {

    suspend fun execute(enabled: Boolean) {
        preferences.automaticFallback.set(enabled)
    }

    @Deprecated(
        message = "Automatic fallback is global in runtime-v2",
        replaceWith = ReplaceWith("execute(enabled)"),
    )
    suspend fun execute(
        @Suppress("UNUSED_PARAMETER") canonicalTitleId: String,
        enabled: Boolean,
    ) {
        execute(enabled)
    }
}
