package tachiyomi.domain.tsuzuki.content.model

import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.content.ContentOption

sealed interface ContentResolution {
    data class Direct(
        val option: ContentOption,
        val usedFallback: Boolean,
    ) : ContentResolution

    data class NeedsSelection(
        val options: List<ContentOption>,
        val preferredAddonId: AddonId?,
        val preferredUnavailable: Boolean,
    ) : ContentResolution

    data object Unavailable : ContentResolution
}
