package eu.kanade.presentation.more.settings.screen

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SettingsReaderPreferredLanguagesTest {

    @Test
    fun `preferred languages preserve user order and remove duplicates`() {
        parsePreferredLanguages("pt-BR, en, pt-BR, ja") shouldBe listOf(
            "pt-BR",
            "en",
            "ja",
        )
    }

    @Test
    fun `preferred languages ignore blank entries`() {
        parsePreferredLanguages(" en, , pt-BR ,, ") shouldBe listOf(
            "en",
            "pt-BR",
        )
    }
}
