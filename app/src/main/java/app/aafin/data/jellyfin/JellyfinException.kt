package app.aafin.data.jellyfin

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidContentException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.exception.SecureConnectionException
import org.jellyfin.sdk.api.client.exception.TimeoutException

/**
 * The single error type the data layer throws for anything Jellyfin-related.
 *
 * UI code should show [userMessage] (UK English, safe to display) and may branch on [kind].
 * Messages never contain URLs or tokens.
 */
class JellyfinException(
    val kind: Kind,
    cause: Throwable? = null,
) : Exception(kind.userMessage, cause) {

    val userMessage: String get() = kind.userMessage

    enum class Kind(val userMessage: String) {
        INVALID_URL("That doesn't look like a valid server address."),
        NETWORK("Can't reach the server. Check your connection and try again."),
        TLS("Couldn't make a secure connection to the server."),
        NOT_JELLYFIN("No Jellyfin server was found at that address."),
        UNAUTHORISED("Sign-in was rejected. Check your details, or sign in again."),
        FORBIDDEN("This account isn't allowed to do that."),
        NOT_FOUND("The server couldn't find that item."),
        SERVER("The server reported an error. Try again in a moment."),
        INVALID_RESPONSE("The server sent a reply AAFin couldn't understand."),
        NOT_SIGNED_IN("Sign in on your phone first."),
        QUICK_CONNECT_DISABLED("Quick Connect is switched off on this server. Use your password instead."),
        NO_COMPATIBLE_STREAM("The server couldn't provide a version of this video that this phone can play."),
        PLAYBACK_NOT_ALLOWED("This account isn't allowed to play that item."),
        TOO_MANY_STREAMS("The server is busy with too many streams. Try again shortly."),
    }
}

/** Maps Jellyfin SDK failures onto [JellyfinException]. Exposed for testing. */
fun Throwable.toJellyfinException(): JellyfinException = when (this) {
    is JellyfinException -> this
    is TimeoutException -> JellyfinException(JellyfinException.Kind.NETWORK, this)
    is SecureConnectionException -> JellyfinException(JellyfinException.Kind.TLS, this)
    is InvalidContentException -> JellyfinException(JellyfinException.Kind.INVALID_RESPONSE, this)
    is InvalidStatusException -> JellyfinException(
        when (status) {
            401 -> JellyfinException.Kind.UNAUTHORISED
            403 -> JellyfinException.Kind.FORBIDDEN
            404 -> JellyfinException.Kind.NOT_FOUND
            else -> JellyfinException.Kind.SERVER
        },
        this,
    )
    is ApiClientException -> JellyfinException(JellyfinException.Kind.NETWORK, this)
    is SerializationException -> JellyfinException(JellyfinException.Kind.INVALID_RESPONSE, this)
    else -> throw this
}

/**
 * Runs an SDK call on a background thread, converting its exceptions into [JellyfinException].
 * Cancellation and programming errors pass through untouched.
 *
 * The switch to [Dispatchers.IO] matters: the SDK waits for the response headers without
 * blocking, but then reads the body on whichever thread the coroutine resumes on. Called from
 * the main thread (as the car and phone screens do) that is a `NetworkOnMainThreadException`
 * whenever the body has not already arrived - always possible over HTTP/1.1.
 */
suspend fun <T> jellyfinCall(block: suspend () -> T): T = withContext(Dispatchers.IO) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiClientException) {
        throw e.toJellyfinException()
    } catch (e: SerializationException) {
        throw e.toJellyfinException()
    }
}
