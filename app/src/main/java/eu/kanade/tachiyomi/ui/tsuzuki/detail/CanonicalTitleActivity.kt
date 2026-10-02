package eu.kanade.tachiyomi.ui.tsuzuki.detail

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.presentation.util.DefaultNavigatorScreenTransition
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.util.view.setComposeContent

class CanonicalTitleActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val canonicalTitleId = intent.getStringExtra(EXTRA_CANONICAL_TITLE_ID)
            ?.takeIf(String::isNotBlank)
            ?: run {
                finish()
                return
            }

        registerSecureActivity(this)
        enableEdgeToEdge()

        setComposeContent {
            Navigator(
                screen = CanonicalTitleScreen(
                    canonicalTitleId = canonicalTitleId,
                    finishOnNavigateUp = true,
                ),
            ) { navigator ->
                DefaultNavigatorScreenTransition(navigator = navigator)
            }
        }
    }

    companion object {
        private const val EXTRA_CANONICAL_TITLE_ID =
            "eu.kanade.tachiyomi.internal.CANONICAL_TITLE_ACTIVITY_ID"

        fun newIntent(
            context: Context,
            canonicalTitleId: String,
        ): Intent? {
            val normalizedId = canonicalTitleId.takeIf(String::isNotBlank) ?: return null
            return Intent(context, CanonicalTitleActivity::class.java)
                .putExtra(EXTRA_CANONICAL_TITLE_ID, normalizedId)
        }
    }
}
