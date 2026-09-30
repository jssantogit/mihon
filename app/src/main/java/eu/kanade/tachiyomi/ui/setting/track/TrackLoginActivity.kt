package eu.kanade.tachiyomi.ui.setting.track

import android.net.Uri
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR

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
            try {
                when (uri.host) {
                    "myanimelist-auth" -> handleMyAnimeList(data["code"])
                    "shikimori-auth" -> handleShikimori(data["code"])
                    "hikka-auth" -> handleHikka(data["reference"])
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                toast(MR.strings.tsuzuki_tracking_login_failed)
            } finally {
                returnToSettings()
            }
        }
    }

    private suspend fun handleMyAnimeList(code: String?) {
        if (code != null) {
            trackerManager.myAnimeList.login(code)
            refreshUserLibraries.refreshForLegacyTracker(trackerManager.myAnimeList.id)
        } else {
            trackerManager.myAnimeList.logout()
            refreshUserLibraries.clearForLegacyTracker(trackerManager.myAnimeList.id)
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
