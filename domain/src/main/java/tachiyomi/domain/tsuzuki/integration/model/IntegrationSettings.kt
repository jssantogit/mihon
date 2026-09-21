package tachiyomi.domain.tsuzuki.integration.model

import tachiyomi.domain.tsuzuki.integration.IntegrationId

data class IntegrationSettings(
    val integrationId: IntegrationId,
    val enabled: Boolean = false,
    val configJson: String = "{}",
    val updatedAt: Long = 0L,
)
