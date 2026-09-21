package tachiyomi.domain.tsuzuki.content.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.content.ContentDelivery
import tachiyomi.domain.tsuzuki.content.ContentOption

@Inject
class RankContentOptions {

    fun execute(
        options: List<ContentOption>,
        preferredAddonId: AddonId?,
        preferredLanguages: List<String>,
    ): List<ContentOption> {
        return options.sortedWith(
            compareBy<ContentOption> { option ->
                if (preferredAddonId != null && option.addonId == preferredAddonId) 0 else 1
            }
                .thenBy { option -> languageRank(option.language, preferredLanguages) }
                .thenBy { option -> deliveryRank(option.delivery) }
                .thenByDescending { option -> option.releaseDate ?: Long.MIN_VALUE }
                .thenBy { option -> option.addonId.value }
                .thenBy { option -> option.key },
        )
    }

    private fun languageRank(language: String?, preferredLanguages: List<String>): Int {
        if (language == null) return preferredLanguages.size + 1
        val index = preferredLanguages.indexOfFirst { preferred ->
            preferred.equals(language, ignoreCase = true)
        }
        return if (index >= 0) index else preferredLanguages.size
    }

    private fun deliveryRank(delivery: ContentDelivery): Int = when (delivery) {
        is ContentDelivery.LocalArchive,
        is ContentDelivery.LocalDirectory -> 0
        is ContentDelivery.Mihon -> 1
        is ContentDelivery.Torrent -> 2
    }
}
