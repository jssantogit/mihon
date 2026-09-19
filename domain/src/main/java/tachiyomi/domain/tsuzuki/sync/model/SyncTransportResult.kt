package tachiyomi.domain.tsuzuki.sync.model

sealed interface SyncTransportResult<out T> {

    data class Success<T>(
        val value: T,
    ) : SyncTransportResult<T>

    data class Failure(
        val failure: SyncFailure,
    ) : SyncTransportResult<Nothing>
}
