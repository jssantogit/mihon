package eu.kanade.tachiyomi.data.track.bangumi

import eu.kanade.tachiyomi.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response

class BangumiInterceptor(private val bangumi: Bangumi) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header(
                "User-Agent",
                "jssantogit/Tsuzuki/v${BuildConfig.VERSION_NAME} (Android) (https://github.com/jssantogit/tsuzuki)",
            )
            .build()

        val accessToken = bangumi.getPersonalAccessToken()
        val authorizedRequest = if (accessToken.isBlank()) {
            request
        } else {
            BangumiApi.authorizeRequest(request, accessToken)
        }

        return chain.proceed(authorizedRequest)
    }
}
