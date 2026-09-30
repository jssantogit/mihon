package eu.kanade.tachiyomi.ui.setting.track

import android.net.Uri
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import tachiyomi.domain.tsuzuki.integration.IntegrationId

class TrackLoginActivity : BaseOAuthLoginActivity() {

    override fun handleResult(uri: Uri) {
        val data = when {
            !uri.encodedQuery.isNullOrBlank() -> uri.encodedQuery
            !uri.encodedFragment.isNullOrBlank() -> uri.encodedFragment
            else -> null
        }
            ?.split("&")
            ?.filter { it.isNotBlank() }
            ?.associate {
                val parts = it.split("=", limit = 2).map<String, String>(Uri::decode)
                parts[0] to parts.getOrNull(1)
            }
            .orEmpty()

        lifecycleScope.launch {
            when (uri.host) {
                "myanimelist-auth" -> handleMyAnimeList(data["code"])
                "shikimori-auth" -> handleShikimori(data["code"])
                "hikka-auth" -> handleHikka(data["reference"])
            }
            returnToSettings()
        }
    }

    private suspend fun handleMyAnimeList(code: String?) {
        val integrationId = IntegrationId("mal")
        if (code != null) {
            trackerManager.myAnimeList.login(code)
            refreshUserLibraries.refreshProvider(integrationId)
        } else {
            trackerManager.myAnimeList.logout()
            refreshUserLibraries.clearProvider(integrationId)
        }
    }

    private suspend fun handleShikimori(code: String?) {
        if (code != null) {
            trackerManager.shikimori.login(code)
        } else {
            trackerManager.shikimori.logout()
        }
    }

    private suspend fun handleHikka(reference: String?) {
        if (reference != null) {
            trackerManager.hikka.login(reference)
        } else {
            trackerManager.hikka.logout()
        }
    }
}
