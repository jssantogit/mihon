package tachiyomi.domain.tsuzuki.home.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.tsuzuki.home.model.HomeContinueReadingSeed

interface HomeContinueReadingSource {
    fun observe(): Flow<List<HomeContinueReadingSeed>>
}
