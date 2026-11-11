package app.aafin.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** A published AAFin release on GitHub. */
data class Release(
    /** "1.8" — the tag without its leading "v". */
    val version: String,
    /** Direct link to the APK file. */
    val apkUrl: String,
    /** The release page, with its notes. */
    val pageUrl: String,
)

/** Where the latest release comes from. Faked in tests. */
fun interface UpdateSource {
    /** The latest release with an APK attached, or null if there is none. Throws [IOException]. */
    suspend fun latest(): Release?
}

/**
 * True if [latest] is a higher version than [current]. Compares the numbers part by part, so
 * 1.10 is newer than 1.9. Anything after the numbers ("1.8-debug") is ignored.
 */
fun isNewerVersion(latest: String, current: String): Boolean {
    val a = versionParts(latest) ?: return false
    val b = versionParts(current) ?: return true
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

private fun versionParts(version: String): List<Int>? =
    Regex("""^v?(\d+(?:\.\d+)*)""").find(version.trim())?.groupValues?.get(1)?.split('.')?.map { it.toInt() }

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("html_url") val htmlUrl: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
private data class GitHubAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
)

private val ReleaseJson = Json { ignoreUnknownKeys = true }

/** Reads GitHub's "latest release" reply. Null if it is a draft, a pre-release or has no APK. */
fun parseLatestRelease(json: String): Release? {
    val release = try {
        ReleaseJson.decodeFromString(GitHubRelease.serializer(), json)
    } catch (e: IllegalArgumentException) {
        return null
    }
    if (release.draft || release.prerelease) return null
    val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) } ?: return null
    return Release(release.tagName.removePrefix("v"), apk.downloadUrl, release.htmlUrl)
}

/** Asks GitHub for the latest AAFin release. Nothing is sent but the request itself. */
class GitHubUpdateSource(
    private val http: OkHttpClient,
    private val repo: String = REPO,
) : UpdateSource {
    override suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(request).execute().use { response ->
            when {
                response.code == 404 -> null // No releases published yet.
                !response.isSuccessful -> throw IOException("GitHub replied ${response.code}")
                else -> parseLatestRelease(response.body.string())
            }
        }
    }

    companion object {
        const val REPO = "BBBannon/AAFin"
    }
}
