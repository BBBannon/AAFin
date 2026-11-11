package app.aafin.data.downloads

import app.aafin.data.AppJson
import app.aafin.data.jellyfin.MediaEntry
import app.aafin.data.playback.StreamTrack
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.io.File
import java.io.IOException

/** The two download qualities offered. 720p is the pre-selected default in the UI. */
@Serializable
enum class DownloadQuality(val label: String) {
    /** The untouched file from the server. Biggest; may need a codec this phone lacks. */
    ORIGINAL("Original"),

    /** Transcoded by the server to h264/aac, at most 1280 wide and 4 Mbps. */
    P720("720p"),
}

/** A subtitle file saved beside a download. [fileName] is relative to the item's folder. */
@Serializable
data class DownloadedSubtitle(
    val index: Int,
    val fileName: String,
    val mimeType: String,
    val language: String? = null,
    val label: String? = null,
)

/**
 * Everything needed to list and play a download with no network: stored as
 * `metadata.json` in the item's folder, next to its poster and subtitle files.
 */
@Serializable
data class DownloadMetadata(
    val itemId: String,
    /** The account that downloaded it; watch progress is reported as this user. */
    val accountId: String,
    val serverName: String,
    /** Snapshot of the item when it was downloaded (name, series, numbering, runtime…). */
    val entry: MediaEntry,
    val quality: DownloadQuality,
    val mediaSourceId: String,
    /**
     * False when an Original download uses a format the server said this phone cannot play
     * directly - the UI should warn that it may not play.
     */
    val playableOnThisPhone: Boolean = true,
    /** Poster image file name in the item's folder, or null if there was none. */
    val posterFileName: String? = null,
    val subtitles: List<DownloadedSubtitle> = emptyList(),
    val audioTracks: List<StreamTrack> = emptyList(),
    val createdAtMs: Long,
    /** Last position watched on this phone; kept locally so resume works offline. */
    val resumePositionMs: Long = 0,
)

/**
 * Reads and writes [DownloadMetadata] under [root]: one folder per item id.
 * Plain files and JSON - no Android dependencies.
 *
 * @param onUnreadable told about folders whose metadata cannot be read (they are skipped by [all]).
 */
class DownloadMetadataStore(
    private val root: File,
    private val onUnreadable: (itemId: String, error: Exception) -> Unit = { _, _ -> },
) {

    /** The folder holding [itemId]'s metadata, poster and subtitles. */
    fun folder(itemId: String): File {
        require(ITEM_ID.matches(itemId)) { "Not a Jellyfin item id" }
        return File(root, itemId)
    }

    fun file(itemId: String, fileName: String): File = File(folder(itemId), fileName)

    @Throws(IOException::class)
    fun save(metadata: DownloadMetadata) {
        val folder = folder(metadata.itemId)
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Could not create the download folder")
        // Write-then-rename so a crash never leaves half a file behind.
        val temp = File(folder, "$FILE_NAME.tmp")
        temp.writeText(AppJson.encodeToString(DownloadMetadata.serializer(), metadata))
        val target = File(folder, FILE_NAME)
        if (target.exists() && !target.delete()) throw IOException("Could not replace the download details")
        if (!temp.renameTo(target)) throw IOException("Could not save the download details")
    }

    /** The metadata for [itemId], or null if there is none (or it is unreadable). */
    fun load(itemId: String): DownloadMetadata? {
        val file = File(folder(itemId), FILE_NAME)
        if (!file.isFile) return null
        return try {
            AppJson.decodeFromString(DownloadMetadata.serializer(), file.readText())
        } catch (e: SerializationException) {
            onUnreadable(itemId, e)
            null
        } catch (e: IOException) {
            onUnreadable(itemId, e)
            null
        }
    }

    /** All readable downloads, oldest first. */
    fun all(): List<DownloadMetadata> =
        root.listFiles { file -> file.isDirectory && ITEM_ID.matches(file.name) }
            .orEmpty()
            .mapNotNull { load(it.name) }
            .sortedBy { it.createdAtMs }

    /** Removes [itemId]'s folder and everything in it. */
    @Throws(IOException::class)
    fun delete(itemId: String) {
        val folder = folder(itemId)
        if (folder.exists() && !folder.deleteRecursively()) throw IOException("Could not delete the download files")
    }

    /** Bytes used by metadata, posters and subtitle files (the video itself lives in the Media3 cache). */
    fun sizeBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private companion object {
        const val FILE_NAME = "metadata.json"
        val ITEM_ID = Regex("[0-9a-fA-F-]{32,36}")
    }
}
