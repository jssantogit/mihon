package eu.kanade.tachiyomi.ui.home

import eu.kanade.tachiyomi.ui.browse.BrowseTab
import eu.kanade.tachiyomi.ui.history.HistoryTab
import eu.kanade.tachiyomi.ui.library.LibraryTab
import eu.kanade.tachiyomi.ui.tsuzuki.home.TsuzukiHomeTab
import eu.kanade.tachiyomi.ui.tsuzuki.search.TsuzukiSearchTab
import eu.kanade.tachiyomi.ui.tsuzuki.settings.TsuzukiSettingsTab
import eu.kanade.tachiyomi.ui.updates.UpdatesTab
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
    fun `legacy Updates History Browse are not primary tabs`() {
        HomeScreen.tabs.contains(UpdatesTab) shouldBe false
        HomeScreen.tabs.contains(HistoryTab) shouldBe false
        HomeScreen.tabs.contains(BrowseTab) shouldBe false
    }
}
