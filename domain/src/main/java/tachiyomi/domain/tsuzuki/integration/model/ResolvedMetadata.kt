package tachiyomi.domain.tsuzuki.integration.model

import tachiyomi.domain.tsuzuki.integration.IntegrationId

/**
 * A metadata value together with the integration that supplied it.
 *
 * Provenance is intentionally field-level: different providers may supply artwork, synopsis,
 * ratings or editorial facts for the same canonical title. Reading inventory is never represented
 * by this model.
 */
data class ProvenancedMetadata<T>(
    val value: T,
    val providerId: IntegrationId,
    val externalId: String? = null,
    val attribution: String? = null,
)

data class ResolvedMetadata(
    val title: ProvenancedMetadata<String>? = null,
    val synopsis: ProvenancedMetadata<String>? = null,
    val artworkUrl: ProvenancedMetadata<String>? = null,
    val status: ProvenancedMetadata<String>? = null,
    val format: ProvenancedMetadata<String>? = null,
    val editorialChapterCount: ProvenancedMetadata<Int>? = null,
    val rating: ProvenancedMetadata<Double>? = null,
    val authors: ProvenancedMetadata<List<String>>? = null,
    val genres: ProvenancedMetadata<List<String>>? = null,
    val externalIds: Map<IntegrationId, String> = emptyMap(),
)
