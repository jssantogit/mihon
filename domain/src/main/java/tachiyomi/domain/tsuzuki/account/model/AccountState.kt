package tachiyomi.domain.tsuzuki.account.model

sealed interface AccountState {
    data object LoggedOut : AccountState

    data class Authenticated(
        val account: TsuzukiAccount,
    ) : AccountState

    data class EmailConfirmationRequired(
        val email: String,
    ) : AccountState
}
