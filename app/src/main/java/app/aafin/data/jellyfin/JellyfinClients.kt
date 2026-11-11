package app.aafin.data.jellyfin

import app.aafin.data.accounts.Account
import app.aafin.data.accounts.AccountStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo

/** How AAFin identifies itself to Jellyfin. */
data class ClientIdentity(
    val clientName: String,
    val clientVersion: String,
    val deviceName: String,
)

/**
 * Creates Jellyfin SDK [ApiClient]s that identify as AAFin with the per-install device id.
 *
 * This and the classes in `data.jellyfin` / `data.playback` are the only places the SDK is
 * touched; UI code works with app-level models.
 */
class JellyfinClients(
    private val identity: ClientIdentity,
    private val accountStore: AccountStore,
    httpClient: OkHttpClient,
) {
    private val factory = OkHttpFactory(httpClient)
    private val clientInfo = ClientInfo(name = identity.clientName, version = identity.clientVersion)

    private val mutex = Mutex()
    private var cached: Pair<Account, ApiClient>? = null

    suspend fun deviceInfo(): DeviceInfo = DeviceInfo(id = accountStore.deviceId(), name = identity.deviceName)

    /** A client with no access token, for public endpoints and signing in. */
    suspend fun anonymous(baseUrl: String): ApiClient = create(baseUrl, accessToken = null)

    /** A client authenticated as [account]. The most recent one is cached. */
    suspend fun forAccount(account: Account): ApiClient = mutex.withLock {
        cached?.takeIf { it.first == account }?.second
            ?: create(account.serverUrl, account.accessToken).also { cached = account to it }
    }

    /**
     * The `Authorization: MediaBrowser …` header value for [account], for requests made outside
     * the SDK (video streams, downloads). Contains the access token: never log it.
     */
    suspend fun authorizationHeader(account: Account): String = AuthorizationHeaderBuilder.buildHeader(
        clientName = identity.clientName,
        clientVersion = identity.clientVersion,
        deviceId = accountStore.deviceId(),
        deviceName = identity.deviceName,
        accessToken = account.accessToken,
    )

    private suspend fun create(baseUrl: String, accessToken: String?): ApiClient = factory.create(
        baseUrl = baseUrl,
        accessToken = accessToken,
        clientInfo = clientInfo,
        deviceInfo = deviceInfo(),
        httpClientOptions = HttpClientOptions(),
        socketConnectionFactory = factory,
    )
}
