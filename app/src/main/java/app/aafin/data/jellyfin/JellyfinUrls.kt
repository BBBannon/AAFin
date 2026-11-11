package app.aafin.data.jellyfin

import org.jellyfin.sdk.api.client.util.UrlBuilder

/**
 * Builds Jellyfin URLs that are fetched outside the SDK (images, video, subtitles, downloads).
 *
 * None of these add the access token: image URLs are public, and everything else is
 * authenticated by the `Authorization` header added in `AuthHeaderInterceptor`.
 * Server-issued URLs (transcoding, subtitle delivery) may already carry a token as a query
 * parameter, so treat every media URL as a secret and never log one.
 */
object JellyfinUrls {

    /**
     * Image for an item, scaled by the server so its width is at most [maxWidth] pixels.
     * Pass the item's image [tag] when known so the URL changes when the artwork does (cache-friendly).
     */
    fun image(
        serverUrl: String,
        itemId: String,
        kind: ImageKind = ImageKind.PRIMARY,
        maxWidth: Int,
        tag: String? = null,
        quality: Int = 90,
    ): String = UrlBuilder.buildUrl(
        baseUrl = serverUrl,
        pathTemplate = "/Items/{itemId}/Images/{imageType}",
        pathParameters = mapOf("itemId" to itemId, "imageType" to kind.apiName),
        queryParameters = mapOf("maxWidth" to maxWidth, "quality" to quality, "tag" to tag),
    )

    /** The original file, streamed untouched (`static=true`). Used for direct play. */
    fun directStream(serverUrl: String, itemId: String, mediaSourceId: String?, tag: String?): String =
        UrlBuilder.buildUrl(
            baseUrl = serverUrl,
            pathTemplate = "/Videos/{itemId}/stream",
            pathParameters = mapOf("itemId" to itemId),
            queryParameters = mapOf("static" to true, "mediaSourceId" to mediaSourceId, "tag" to tag),
        )

    /** The original file as a download (`/Items/{id}/Download`). */
    fun download(serverUrl: String, itemId: String): String = UrlBuilder.buildUrl(
        baseUrl = serverUrl,
        pathTemplate = "/Items/{itemId}/Download",
        pathParameters = mapOf("itemId" to itemId),
    )

    /** A subtitle stream converted by the server to [format] (e.g. `srt`). */
    fun subtitle(serverUrl: String, itemId: String, mediaSourceId: String, index: Int, format: String): String =
        UrlBuilder.buildUrl(
            baseUrl = serverUrl,
            pathTemplate = "/Videos/{routeItemId}/{routeMediaSourceId}/Subtitles/{routeIndex}/Stream.{routeFormat}",
            pathParameters = mapOf(
                "routeItemId" to itemId,
                "routeMediaSourceId" to mediaSourceId,
                "routeIndex" to index,
                "routeFormat" to format,
            ),
        )

    /**
     * Resolves a URL returned by the server (`TranscodingUrl`, subtitle `DeliveryUrl`).
     * These are relative to the server's base URL unless already absolute.
     */
    fun resolve(serverUrl: String, serverPath: String): String =
        if (serverPath.startsWith("http://", ignoreCase = true) || serverPath.startsWith("https://", ignoreCase = true)) {
            serverPath
        } else {
            serverUrl.trimEnd('/') + "/" + serverPath.trimStart('/')
        }
}
