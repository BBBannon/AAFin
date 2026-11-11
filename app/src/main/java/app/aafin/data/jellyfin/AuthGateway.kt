package app.aafin.data.jellyfin

import org.jellyfin.sdk.api.client.extensions.authenticateUserByName
import org.jellyfin.sdk.api.client.extensions.authenticateWithQuickConnect
import org.jellyfin.sdk.api.client.extensions.quickConnectApi
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.model.api.AuthenticationResult

/** What `/System/Info/Public` told us about a server. */
data class ServerInfo(
    /** Normalised base URL that answered. */
    val url: String,
    val name: String,
    val id: String,
    val version: String?,
) {
    val compatibility: ServerCompatibility get() = ServerCompatibility.of(version)

    /** False when the connection is plain HTTP (only possible for local-network addresses). */
    val isSecure: Boolean get() = url.startsWith("https://", ignoreCase = true)
}

/**
 * How well the bundled Jellyfin SDK (1.8.x, generated for server 10.11) matches a server version.
 *
 * Every endpoint AAFin calls is the same on the wire from 10.10 to 12.4 (audited against the
 * 10.11 and 12.4 OpenAPI specs and server source), so that whole range counts as supported.
 */
enum class ServerCompatibility {
    /** Jellyfin 10.10 to 12.x. */
    SUPPORTED,

    /** Older than 10.10: may work but is not supported. */
    TOO_OLD,

    /** A newer major line than any that has been checked (13+): expected to work, not guaranteed. */
    NEWER_UNTESTED,

    /** The server did not report a parseable version. */
    UNKNOWN;

    companion object {
        fun of(version: String?): ServerCompatibility {
            val parts = version?.split('.')?.map { it.toIntOrNull() } ?: return UNKNOWN
            val major = parts.getOrNull(0) ?: return UNKNOWN
            val minor = parts.getOrNull(1) ?: return UNKNOWN
            return when {
                major < 10 || (major == 10 && minor < 10) -> TOO_OLD
                major <= 12 -> SUPPORTED
                else -> NEWER_UNTESTED
            }
        }
    }
}

/** A pending Quick Connect request: [code] is shown to the user, [secret] is used to poll. */
data class QuickConnectTicket(val secret: String, val code: String)

/** A successful sign-in. */
data class AuthResult(
    val userId: String,
    val userName: String,
    val accessToken: String,
    val serverId: String,
)

/**
 * The handful of unauthenticated Jellyfin calls needed to sign in.
 * Implementations throw [JellyfinException] on failure.
 */
interface AuthGateway {
    /** `GET /System/Info/Public` */
    suspend fun publicInfo(baseUrl: String): ServerInfo

    /** `GET /QuickConnect/Enabled` */
    suspend fun quickConnectEnabled(baseUrl: String): Boolean

    /** `POST /QuickConnect/Initiate` */
    suspend fun quickConnectInitiate(baseUrl: String): QuickConnectTicket

    /** `GET /QuickConnect/Connect?secret=` - true once the user has approved the code. */
    suspend fun quickConnectApproved(baseUrl: String, secret: String): Boolean

    /** `POST /Users/AuthenticateWithQuickConnect` */
    suspend fun authenticateWithQuickConnect(baseUrl: String, secret: String): AuthResult

    /** `POST /Users/AuthenticateByName` */
    suspend fun authenticateByName(baseUrl: String, username: String, password: String): AuthResult
}

/** [AuthGateway] backed by the Jellyfin Kotlin SDK. */
class SdkAuthGateway(private val clients: JellyfinClients) : AuthGateway {

    override suspend fun publicInfo(baseUrl: String): ServerInfo {
        val info = try {
            jellyfinCall { clients.anonymous(baseUrl).systemApi.getPublicSystemInfo().content }
        } catch (e: JellyfinException) {
            // Something answered, but not with Jellyfin's public info.
            throw when (e.kind) {
                JellyfinException.Kind.NOT_FOUND,
                JellyfinException.Kind.INVALID_RESPONSE,
                JellyfinException.Kind.UNAUTHORISED,
                JellyfinException.Kind.FORBIDDEN,
                -> JellyfinException(JellyfinException.Kind.NOT_JELLYFIN, e)
                else -> e
            }
        }
        val id = info.id ?: throw JellyfinException(JellyfinException.Kind.NOT_JELLYFIN)
        return ServerInfo(
            url = baseUrl,
            name = info.serverName?.takeIf { it.isNotBlank() } ?: "Jellyfin",
            id = id,
            version = info.version,
        )
    }

    override suspend fun quickConnectEnabled(baseUrl: String): Boolean =
        jellyfinCall { clients.anonymous(baseUrl).quickConnectApi.getQuickConnectEnabled().content }

    override suspend fun quickConnectInitiate(baseUrl: String): QuickConnectTicket {
        val result = jellyfinCall { clients.anonymous(baseUrl).quickConnectApi.initiateQuickConnect().content }
        return QuickConnectTicket(secret = result.secret, code = result.code)
    }

    override suspend fun quickConnectApproved(baseUrl: String, secret: String): Boolean =
        jellyfinCall { clients.anonymous(baseUrl).quickConnectApi.getQuickConnectState(secret).content.authenticated }

    override suspend fun authenticateWithQuickConnect(baseUrl: String, secret: String): AuthResult =
        jellyfinCall { clients.anonymous(baseUrl).userApi.authenticateWithQuickConnect(secret).content }.toAuthResult()

    override suspend fun authenticateByName(baseUrl: String, username: String, password: String): AuthResult =
        jellyfinCall { clients.anonymous(baseUrl).userApi.authenticateUserByName(username, password).content }.toAuthResult()

    private fun AuthenticationResult.toAuthResult(): AuthResult {
        val user = user ?: throw JellyfinException(JellyfinException.Kind.INVALID_RESPONSE)
        return AuthResult(
            userId = user.id.toString(),
            userName = user.name ?: "",
            accessToken = accessToken ?: throw JellyfinException(JellyfinException.Kind.INVALID_RESPONSE),
            serverId = serverId ?: user.serverId ?: "",
        )
    }
}
