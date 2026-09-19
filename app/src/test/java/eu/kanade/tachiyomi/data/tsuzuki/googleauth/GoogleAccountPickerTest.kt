package eu.kanade.tachiyomi.data.tsuzuki.googleauth

import android.accounts.AccountManager
import android.app.Activity
import android.content.Intent
import androidx.activity.result.ActivityResult
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.googleauth.model.GoogleAccountIdentity

class GoogleAccountPickerTest {

    @Test
    fun `case 1 - successful Google account selection returns neutral identity`() {
        val intent = Intent()
            .putExtra(AccountManager.KEY_ACCOUNT_NAME, "reader@example.com")
            .putExtra(AccountManager.KEY_ACCOUNT_TYPE, "com.google")

        resolveGoogleAccountSelection(ActivityResult(Activity.RESULT_OK, intent)) shouldBe
            GoogleAccountIdentity("reader@example.com")
    }

    @Test
    fun `case 2 - cancelled account selection returns no identity`() {
        resolveGoogleAccountSelection(ActivityResult(Activity.RESULT_CANCELED, null)).shouldBeNull()
    }

    @Test
    fun `case 3 - non Google account selection fails closed`() {
        val intent = Intent()
            .putExtra(AccountManager.KEY_ACCOUNT_NAME, "reader@example.com")
            .putExtra(AccountManager.KEY_ACCOUNT_TYPE, "example.account")

        resolveGoogleAccountSelection(ActivityResult(Activity.RESULT_OK, intent)).shouldBeNull()
    }
}
