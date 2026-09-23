package tachiyomi.domain.tsuzuki.content

import tachiyomi.domain.tsuzuki.addon.AddonId

enum class ContentBindingAvailability {
    UNKNOWN,
    AVAILABLE,
    UNAVAILABLE,
}

data class ContentBinding(
    val id: String,
    val canonicalTitleId: String,
    val addonId: AddonId,
    val providerTitleKey: String,
    val matchConfidence: Double,
    val verifiedByUser: Boolean,
    val availability: ContentBindingAvailability,
    val runtimePayload: ByteArray,
    val createdAt: Long,
    val updatedAt: Long,
)
