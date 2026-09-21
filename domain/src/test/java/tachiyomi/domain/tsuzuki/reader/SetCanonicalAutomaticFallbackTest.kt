package tachiyomi.domain.tsuzuki.reader

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.tsuzuki.reader.interactor.SetCanonicalAutomaticFallback
import tachiyomi.domain.tsuzuki.reader.model.CanonicalReaderPreferences

class SetCanonicalAutomaticFallbackTest {

    @Test
    fun `automatic fallback is one global preference not title scoped`() = runTest {
        val preferences = CanonicalReaderPreferences(InMemoryPreferenceStore())
        val setFallback = SetCanonicalAutomaticFallback(preferences)

        setFallback.execute(enabled = true)

        preferences.automaticFallback.get() shouldBe true
    }
}
