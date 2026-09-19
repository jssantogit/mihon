package eu.kanade.tachiyomi.data.tsuzuki.googleauth

import com.google.android.gms.common.api.CommonStatusCodes
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.googleauth.model.GoogleAccountIdentity
import tachiyomi.domain.tsuzuki.googleauth.model.GoogleAuthFailureReason

class GooglePlayAuthorizationPlatformTest {

    private val account = GoogleAccountIdentity("reader@example.com")

    @Test
    fun `case 1 - canceled status maps to cancellation`() {
        mapAuthorizationApiStatus(CommonStatusCodes.CANCELED, account) shouldBe
            GoogleAuthorizationPlatformResult.Cancelled
    }

    @Test
    fun `case 2 - developer error is fatal configuration failure`() {
        mapAuthorizationApiStatus(CommonStatusCodes.DEVELOPER_ERROR, account) shouldBe
            GoogleAuthorizationPlatformResult.Failure(
                failureForStatus(CommonStatusCodes.DEVELOPER_ERROR),
            )
        failureForStatus(CommonStatusCodes.DEVELOPER_ERROR).reason shouldBe
            GoogleAuthFailureReason.CONFIGURATION_ERROR
    }

    @Test
    fun `case 3 - network error is recoverable and keeps account hint`() {
        val result = mapAuthorizationApiStatus(CommonStatusCodes.NETWORK_ERROR, account)

        result shouldBe GoogleAuthorizationPlatformResult.RecoverableFailure(
            failure = failureForStatus(CommonStatusCodes.NETWORK_ERROR),
            account = account,
        )
        failureForStatus(CommonStatusCodes.NETWORK_ERROR).reason shouldBe
            GoogleAuthFailureReason.NETWORK_UNAVAILABLE
    }

    @Test
    fun `case 4 - unavailable Google services are recoverable`() {
        failureForStatus(CommonStatusCodes.API_NOT_CONNECTED).reason shouldBe
            GoogleAuthFailureReason.GOOGLE_SERVICES_UNAVAILABLE
    }

    @Test
    fun `case 5 - sign in required maps to unavailable account`() {
        failureForStatus(CommonStatusCodes.SIGN_IN_REQUIRED).reason shouldBe
            GoogleAuthFailureReason.ACCOUNT_UNAVAILABLE
    }
}
