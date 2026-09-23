package tachiyomi.domain.tsuzuki.collections.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.tsuzuki.collections.model.CollectionFolder
import tachiyomi.domain.tsuzuki.collections.model.CollectionList
import tachiyomi.domain.tsuzuki.collections.model.TsuzukiCollection

interface CollectionStore {
    suspend fun getCollection(id: String): TsuzukiCollection?
    suspend fun getCollections(includeDeleted: Boolean = false): List<TsuzukiCollection>
    fun observeCollections(): Flow<List<TsuzukiCollection>>
    suspend fun upsertCollection(collection: TsuzukiCollection)

    suspend fun getFolder(id: String): CollectionFolder?
    suspend fun getFolders(collectionId: String, includeDeleted: Boolean = false): List<CollectionFolder>
    fun observeFolders(collectionId: String): Flow<List<CollectionFolder>>
    suspend fun upsertFolder(folder: CollectionFolder)

    suspend fun getList(id: String): CollectionList?
    suspend fun getLists(folderId: String, includeDeleted: Boolean = false): List<CollectionList>
    fun observeLists(folderId: String): Flow<List<CollectionList>>
    suspend fun upsertList(list: CollectionList)
}
