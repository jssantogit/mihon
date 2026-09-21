package eu.kanade.tachiyomi.data.tsuzuki.addon

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.addon.model.AddonSyncIntent

class PreferenceAddonSyncIntentRepositoryTest {

    @Test
    fun `enabled intent is always also desired and can be removed explicitly`() = runTest {
        val repository = PreferenceAddonSyncIntentRepository(
            InMemoryPreferenceStore(),
        )

        repository.recordEnabled(AddonId("pkg.reader"), enabled = true)

        repository.get() shouldBe AddonSyncIntent(
            desiredPackageIds = setOf("pkg.reader"),
            enabledPackageIds = setOf("pkg.reader"),
        )
        repository.observe().first() shouldBe repository.get()

        repository.recordEnabled(AddonId("pkg.reader"), enabled = false)
        repository.get() shouldBe AddonSyncIntent(
            desiredPackageIds = setOf("pkg.reader"),
            enabledPackageIds = emptySet(),
        )

        repository.removeDesired(AddonId("pkg.reader"))
        repository.get() shouldBe AddonSyncIntent()
    }

    @Test
    fun `remote intent normalization cannot enable an undesired package`() = runTest {
        val repository = PreferenceAddonSyncIntentRepository(
            InMemoryPreferenceStore(),
        )

        repository.set(
            AddonSyncIntent(
                desiredPackageIds = setOf("pkg.one", "pkg.two"),
                enabledPackageIds = setOf("pkg.two"),
            ),
        )

        repository.get() shouldBe AddonSyncIntent(
            desiredPackageIds = setOf("pkg.one", "pkg.two"),
            enabledPackageIds = setOf("pkg.two"),
        )
    }
}
