package tachiyomi.domain.tsuzuki.reader.service

import tachiyomi.domain.tsuzuki.reader.model.CanonicalReadingStart

interface CanonicalReadingStartResolver {
    suspend fun execute(canonicalTitleId: String): CanonicalReadingStart
}
