package eu.kanade.tachiyomi.ui.home

import eu.kanade.tachiyomi.ui.library.LibraryTab
import eu.kanade.tachiyomi.ui.tsuzuki.home.TsuzukiHomeTab
import eu.kanade.tachiyomi.ui.tsuzuki.search.TsuzukiSearchTab
import eu.kanade.tachiyomi.ui.tsuzuki.settings.TsuzukiSettingsTab
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TsuzukiNavigationContractTest {

    @Test
    fun `primary shell contains exactly Home Search Library Settings`() {
        HomeScreen.tabs shouldContainExactly listOf(
            TsuzukiHomeTab,
            TsuzukiSearchTab,
            LibraryTab,
            TsuzukiSettingsTab,
        )
    }

    @Test
    fun `legacy Mihon tabs are absent from primary shell`() {
        val legacyTabNames = setOf("UpdatesTab", "HistoryTab", "BrowseTab", "MoreTab")
        HomeScreen.tabs.any { it::class.simpleName in legacyTabNames } shouldBe false
    }
}
