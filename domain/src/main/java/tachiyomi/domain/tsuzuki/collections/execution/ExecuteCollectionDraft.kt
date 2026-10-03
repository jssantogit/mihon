package tachiyomi.domain.tsuzuki.collections.execution

import dev.zacsweers.metro.Inject

@Inject
class ExecuteCollectionDraft(
    private val executeCollectionList: ExecuteCollectionList,
) {
    suspend fun execute(
        request: ExecuteCollectionDraftRequest,
    ): ExecuteCollectionDraftResult = executeCollectionList.executeDraft(request)

    suspend operator fun invoke(
        request: ExecuteCollectionDraftRequest,
    ): ExecuteCollectionDraftResult = execute(request)
}
