package tachiyomi.domain.tsuzuki.integration.model

import tachiyomi.domain.tsuzuki.model.LibraryStatus

data class TrackingUpdate(
    val externalId: String,
    val chapterProgress: Double?,
    val status: LibraryStatus?,
    val score: Double?,
)
