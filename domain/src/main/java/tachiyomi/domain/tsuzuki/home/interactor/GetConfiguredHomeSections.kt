package tachiyomi.domain.tsuzuki.home.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import tachiyomi.domain.tsuzuki.collections.model.CollectionFolder
import tachiyomi.domain.tsuzuki.collections.model.CollectionOrigin
import tachiyomi.domain.tsuzuki.collections.model.TsuzukiCollection
import tachiyomi.domain.tsuzuki.collections.repository.CollectionStore
import tachiyomi.domain.tsuzuki.home.model.HomeCollectionBrowse
import tachiyomi.domain.tsuzuki.home.model.HomeFolderBrowse
import tachiyomi.domain.tsuzuki.home.model.HomeFolderTile
import tachiyomi.domain.tsuzuki.home.model.HomeSection

@Inject
class GetConfiguredHomeSections(
    private val store: CollectionStore,
) {

    suspend fun execute(): List<HomeSection> {
        return store.getCollections()
            .filter { it.origin == CollectionOrigin.USER }
            .sortedWith(compareBy({ it.sortOrder }, { it.id }))
            .map { collection ->
                HomeSection.CollectionSection(
                    collectionId = collection.id,
                    title = collection.title,
                    folders = store.getFolders(collection.id)
                        .rootFolders()
                        .map { it.asHomeTile() },
                )
            }
            .toList()
    }

    fun subscribe(): Flow<List<HomeSection>> {
        return store.observeCollections().flatMapLatest { collections ->
            val userCollections = collections
                .filter { it.origin == CollectionOrigin.USER }
                .sortedWith(compareBy({ it.sortOrder }, { it.id }))

            if (userCollections.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(
                    userCollections.map(::observeCollection),
                ) { sections ->
                    sections.toList()
                }
            }
        }
    }

    fun subscribeCollection(
        collectionId: String,
    ): Flow<HomeCollectionBrowse?> {
        return store.observeCollections().flatMapLatest { collections ->
            val collection = collections.firstOrNull {
                it.id == collectionId && it.origin == CollectionOrigin.USER
            } ?: return@flatMapLatest flowOf(null)

            observeCollectionBrowse(collection)
        }
    }

    fun subscribeFolder(
        collectionId: String,
        folderId: String,
    ): Flow<HomeFolderBrowse?> {
        return combine(
            store.observeFolders(collectionId),
            store.observeLists(folderId),
        ) { folders, lists ->
            val folder = folders.firstOrNull { it.id == folderId }
                ?: return@combine null

            HomeFolderBrowse(
                collectionId = collectionId,
                folderId = folder.id,
                title = folder.title,
                childFolders = folders
                    .filter { it.parentFolderId == folderId }
                    .sortedWith(compareBy({ it.sortOrder }, { it.id }))
                    .map { it.asHomeTile() },
                lists = lists.sortedWith(compareBy({ it.sortOrder }, { it.id })),
            )
        }
    }

    private fun observeCollection(
        collection: TsuzukiCollection,
    ): Flow<HomeSection.CollectionSection> {
        return store.observeFolders(collection.id).map { folders ->
            HomeSection.CollectionSection(
                collectionId = collection.id,
                title = collection.title,
                folders = folders
                    .rootFolders()
                    .map { it.asHomeTile() },
            )
        }
    }

    private fun observeCollectionBrowse(
        collection: TsuzukiCollection,
    ): Flow<HomeCollectionBrowse> {
        return store.observeFolders(collection.id).map { folders ->
            HomeCollectionBrowse(
                collectionId = collection.id,
                title = collection.title,
                folders = folders
                    .rootFolders()
                    .map { it.asHomeTile() },
            )
        }
    }

    private fun List<CollectionFolder>.rootFolders(): List<CollectionFolder> {
        return filter { it.parentFolderId == null }
            .sortedWith(compareBy({ it.sortOrder }, { it.id }))
    }

    private fun CollectionFolder.asHomeTile() = HomeFolderTile(
        folderId = id,
        title = title,
    )
}
