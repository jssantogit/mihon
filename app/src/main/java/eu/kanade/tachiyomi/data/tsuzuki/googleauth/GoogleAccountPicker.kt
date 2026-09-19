package eu.kanade.tachiyomi.data.tsuzuki.googleauth

import android.accounts.AccountManager
import android.app.Activity
import android.content.Intent
import androidx.activity.result.ActivityResult
import com.google.android.gms.common.AccountPicker
import tachiyomi.domain.tsuzuki.googleauth.model.GoogleAccountIdentity

internal fun createGoogleAccountPickerIntent(): Intent {
    val options = AccountPicker.AccountChooserOptions.Builder()
        .setAllowableAccountsTypes(listOf(GOOGLE_ACCOUNT_TYPE))
        .setAlwaysShowAccountPicker(true)
        .build()

    return AccountPicker.newChooseAccountIntent(options)
}

internal fun resolveGoogleAccountSelection(
    activityResult: ActivityResult,
): GoogleAccountIdentity? {
    if (activityResult.resultCode != Activity.RESULT_OK) {
        return null
    }

    return resolveGoogleAccountSelection(
        accountName = activityResult.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME),
        accountType = activityResult.data?.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE),
    )
}

internal fun resolveGoogleAccountSelection(
    accountName: String?,
    accountType: String?,
): GoogleAccountIdentity? {
    val normalizedName = accountName
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: return null

    if (accountType != null && accountType != GOOGLE_ACCOUNT_TYPE) {
        return null
    }

    return GoogleAccountIdentity(normalizedName)
}

private const val GOOGLE_ACCOUNT_TYPE = "com.google"
