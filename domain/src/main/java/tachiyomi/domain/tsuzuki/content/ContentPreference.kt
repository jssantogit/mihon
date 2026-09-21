package tachiyomi.domain.tsuzuki.content

import tachiyomi.domain.tsuzuki.addon.AddonId

data class ContentPreference(
    val canonicalTitleId: String,
    val preferredAddonId: AddonId?,
    val updatedAt: Long,
)
