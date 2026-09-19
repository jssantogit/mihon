package eu.kanade.tachiyomi.data.tsuzuki.googleauth

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.googleauth.model.GoogleAccountIdentity

class GoogleAccountPickerTest {

    @Test
    fun `case 1 - Google account data returns neutral identity`() {
        resolveGoogleAccountSelection(
            accountName = "reader@example.com",
            accountType = "com.google",
        ) shouldBe GoogleAccountIdentity("reader@example.com")
    }

    @Test
    fun `case 2 - blank account name fails closed`() {
        resolveGoogleAccountSelection(
            accountName = "   ",
            accountType = "com.google",
        ).shouldBeNull()
    }

    @Test
    fun `case 3 - non Google account data fails closed`() {
        resolveGoogleAccountSelection(
            accountName = "reader@example.com",
            accountType = "example.account",
        ).shouldBeNull()
    }

    @Test
    fun `case 4 - missing account type is accepted when Android returns a valid name`() {
        resolveGoogleAccountSelection(
            accountName = " reader@example.com ",
            accountType = null,
        ) shouldBe GoogleAccountIdentity("reader@example.com")
    }
}
