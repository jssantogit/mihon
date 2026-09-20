package tachiyomi.domain.tsuzuki.sync.model

sealed interface SyncCodecResult<out T> {

    data class Success<T>(
        val value: T,
    ) : SyncCodecResult<T>

    data class Failure(
        val failure: SyncFailure,
    ) : SyncCodecResult<Nothing>
}
