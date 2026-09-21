package eu.kanade.tachiyomi.ui.home

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldNotContain
import org.junit.jupiter.api.Test
import eu.kanade.tachiyomi.ui.browse.BrowseTab
import eu.kanade.tachiyomi.ui.history.HistoryTab
import eu.kanade.tachiyomi.ui.library.LibraryTab
import eu.kanade.tachiyomi.ui.tsuzuki.home.TsuzukiHomeTab
import eu.kanade.tachiyomi.ui.tsuzuki.search.TsuzukiSearchTab
import eu.kanade.tachiyomi.ui.tsuzuki.settings.TsuzukiSettingsTab
import eu.kanade.tachiyomi.ui.updates.UpdatesTab

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
        HomeScreen.tabs shouldNotContain UpdatesTab
        HomeScreen.tabs shouldNotContain HistoryTab
        HomeScreen.tabs shouldNotContain BrowseTab
    }
}
