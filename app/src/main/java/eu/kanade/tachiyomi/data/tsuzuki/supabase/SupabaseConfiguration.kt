package eu.kanade.tachiyomi.data.tsuzuki.supabase

import eu.kanade.tachiyomi.BuildConfig

data class SupabaseConfiguration(
    val url: String,
    val publishableKey: String,
) {
    val isConfigured: Boolean
        get() = url.isNotBlank() && publishableKey.isNotBlank()

    val callbackUrl: String
        get() = "$CALLBACK_SCHEME://$CALLBACK_HOST"

    fun endpoint(path: String): String = url.trimEnd('/') + "/" + path.trimStart('/')

    companion object {
        const val CALLBACK_SCHEME = "tsuzuki"
        const val CALLBACK_HOST = "auth"

        fun fromBuildConfig(): SupabaseConfiguration = SupabaseConfiguration(
            url = BuildConfig.TSUZUKI_SUPABASE_URL,
            publishableKey = BuildConfig.TSUZUKI_SUPABASE_PUBLISHABLE_KEY,
        )
    }
}
