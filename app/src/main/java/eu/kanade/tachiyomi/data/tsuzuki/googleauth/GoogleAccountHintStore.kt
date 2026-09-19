package eu.kanade.tachiyomi.data.tsuzuki.googleauth

import tachiyomi.domain.tsuzuki.googleauth.model.GoogleAccountIdentity

interface GoogleAccountHintStore {
    fun read(): GoogleAccountIdentity?

    fun write(account: GoogleAccountIdentity)

    fun clear()
}
