package mihon.data.extension.service

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ExtensionStoreUrlTest {

    @Test
    fun `raw GitHub repo branch shorthand expands to legacy index`() {
        normalizeExtensionStoreIndexUrl(
            "https://raw.githubusercontent.com/keiyoushi/extensions/repo",
        ) shouldBe "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json"
    }

    @Test
    fun `explicit store index remains unchanged`() {
        normalizeExtensionStoreIndexUrl(
            "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json",
        ) shouldBe "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json"
    }

    @Test
    fun `generic store urls remain unchanged`() {
        normalizeExtensionStoreIndexUrl("https://extensions.example.org/store.pb") shouldBe
            "https://extensions.example.org/store.pb"
    }
}
