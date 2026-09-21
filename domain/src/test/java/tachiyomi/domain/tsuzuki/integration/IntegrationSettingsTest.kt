package tachiyomi.domain.tsuzuki.integration

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.integration.model.IntegrationSettings

class IntegrationSettingsTest {

    @Test
    fun `new integration setting is disabled by default`() {
        val settings = IntegrationSettings(integrationId = IntegrationId("kitsu"))

        settings.enabled shouldBe false
    }

    @Test
    fun `integration setting preserves configuration and update timestamp`() {
        val settings = IntegrationSettings(
            integrationId = IntegrationId("mal"),
            enabled = true,
            configJson = "{\"clientId\":\"configured\"}",
            updatedAt = 42L,
        )

        settings.integrationId shouldBe IntegrationId("mal")
        settings.configJson shouldBe "{\"clientId\":\"configured\"}"
        settings.updatedAt shouldBe 42L
    }
}
