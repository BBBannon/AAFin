package app.aafin.data.playback

import app.aafin.data.accounts.AccountStore
import app.aafin.data.accounts.AccountsState
import app.aafin.data.jellyfin.JellyfinClients
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds Jellyfin's `Authorization: MediaBrowser …` header to media requests (video, subtitles,
 * downloads) made by Media3 through OkHttp.
 *
 * The header is added only when the request goes to a stored account's server (same scheme, host,
 * port and base path), so tokens are never sent anywhere else. If several accounts share a
 * server, the active account's token is used.
 */
class AuthHeaderInterceptor(
    private val accountStore: AccountStore,
    private val clients: JellyfinClients,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header(HEADER) != null) return chain.proceed(request)

        // Runs on an OkHttp worker thread, so blocking for the (in-memory) account list is fine.
        val header = runBlocking { headerFor(request.url, accountStore.state.first()) }
            ?: return chain.proceed(request)
        return chain.proceed(request.newBuilder().header(HEADER, header).build())
    }

    private suspend fun headerFor(url: HttpUrl, state: AccountsState): String? {
        val candidates = listOfNotNull(state.activeAccount) + state.accounts
        val account = candidates.firstOrNull { isOnServer(url, it.serverUrl) } ?: return null
        return clients.authorizationHeader(account)
    }

    companion object {
        private const val HEADER = "Authorization"

        /** True if [url] points at the Jellyfin server whose base URL is [serverUrl]. */
        fun isOnServer(url: HttpUrl, serverUrl: String): Boolean {
            val base = serverUrl.toHttpUrlOrNull() ?: return false
            if (url.scheme != base.scheme || url.host != base.host || url.port != base.port) return false
            val basePath = base.encodedPath.trimEnd('/')
            return basePath.isEmpty() || url.encodedPath == basePath || url.encodedPath.startsWith("$basePath/")
        }
    }
}
