package app.aafin.data.jellyfin

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Turns whatever the user typed into candidate Jellyfin base URLs.
 *
 * Accepted input: with or without a scheme, trailing slashes, a base path (`/jellyfin`), or a URL
 * copied from the browser (`https://host/web/#/home.html`).
 */
object ServerUrl {

    /**
     * Returns base URLs to try, most preferred first. Each has no trailing slash, query or fragment.
     *
     * - An explicit `http://` or `https://` is respected as typed.
     * - With no scheme, HTTPS is tried first. Plain HTTP is offered as a fallback only for
     *   local-network hosts (IP addresses, `localhost`, `.local`, single-label names), so a
     *   public hostname is never silently downgraded to an unencrypted connection.
     *
     * @throws JellyfinException with kind `INVALID_URL` if nothing usable was entered.
     */
    fun candidates(input: String): List<String> {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) throw invalid()

        val schemeMatch = SCHEME.find(trimmed)
        if (schemeMatch != null) {
            val scheme = schemeMatch.groupValues[1].lowercase()
            if (scheme != "http" && scheme != "https") throw invalid()
            return listOf(clean(trimmed) ?: throw invalid())
        }

        val secure = clean("https://$trimmed") ?: throw invalid()
        val host = secure.toHttpUrlOrNull()?.host ?: throw invalid()
        return if (isLocalHost(host)) {
            listOf(secure, clean("http://$trimmed") ?: throw invalid())
        } else {
            listOf(secure)
        }
    }

    /** The preferred candidate for [input]; see [candidates]. */
    fun normalise(input: String): String = candidates(input).first()

    private fun clean(url: String): String? {
        val parsed: HttpUrl = url.toHttpUrlOrNull() ?: return null
        if (parsed.host.isEmpty()) return null

        // Drop empty segments (trailing or doubled slashes) and anything from the web client's path onwards.
        val segments = parsed.pathSegments.filter { it.isNotEmpty() }
        val webIndex = segments.indexOfFirst { it.equals("web", ignoreCase = true) }
        val baseSegments = if (webIndex >= 0) segments.take(webIndex) else segments

        val builder = HttpUrl.Builder().scheme(parsed.scheme).host(parsed.host).port(parsed.port)
        baseSegments.forEach { builder.addPathSegment(it) }
        return builder.build().toString().trimEnd('/')
    }

    private fun isLocalHost(host: String): Boolean =
        host == "localhost" ||
            host.endsWith(".local") ||
            host.endsWith(".lan") ||
            host.endsWith(".home.arpa") ||
            !host.contains('.') ||
            IPV4.matches(host) ||
            host.contains(':') // IPv6 literal

    private fun invalid() = JellyfinException(JellyfinException.Kind.INVALID_URL)

    private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://")
    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
}
