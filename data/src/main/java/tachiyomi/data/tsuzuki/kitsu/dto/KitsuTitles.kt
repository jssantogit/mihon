package tachiyomi.data.tsuzuki.kitsu.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class KitsuTitles(
    val en: String? = null,
    @SerialName("en_jp") val enJp: String? = null,
    @SerialName("ja_jp") val jaJp: String? = null,
    @SerialName("en_us") val enUs: String? = null,
)
