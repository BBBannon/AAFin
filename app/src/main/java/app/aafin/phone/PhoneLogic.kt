package app.aafin.phone

import app.aafin.data.downloads.DownloadQuality
import app.aafin.data.downloads.DownloadStatus
import app.aafin.data.downloads.DownloadedItem
import app.aafin.data.jellyfin.JellyfinException
import app.aafin.data.jellyfin.MediaEntry
import app.aafin.data.jellyfin.ServerCompatibility
import app.aafin.data.jellyfin.ServerInfo
import app.aafin.data.jellyfin.SourceInfo
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

/*
 * The phone UI's pure logic: no Android, no coroutines. Unit-tested in PhoneLogicTest.
 */

/** Something being fetched for a screen. */
sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>
    data class Failed(val message: String) : Loadable<Nothing>
    data class Ready<T>(val value: T) : Loadable<T>
}

const val STORAGE_ERROR_MESSAGE = "Couldn't save to this phone's storage. Check there is enough free space."

/** Runs [block]; returns what to tell the user if it failed in an expected way, else null. */
suspend fun failureMessage(block: suspend () -> Unit): String? = try {
    block()
    null
} catch (e: JellyfinException) {
    e.userMessage
} catch (e: IOException) {
    STORAGE_ERROR_MESSAGE
}

object Format {
    /** "640 KB", "310 MB", "1.2 GB" (1 GB = 1000 MB, as Android's own storage screens count). */
    fun bytes(bytes: Long): String = when {
        bytes < 1_000_000 -> "${(bytes / 1_000.0).roundToLong()} KB"
        bytes < 1_000_000_000 -> "${(bytes / 1_000_000.0).roundToLong()} MB"
        else -> String.format(Locale.UK, "%.1f GB", bytes / 1_000_000_000.0)
    }

    /** "42 min", "2 h", "1 h 42 min". */
    fun duration(ms: Long): String {
        val minutes = (ms / 60_000.0).roundToLong().coerceAtLeast(1)
        return when {
            minutes < 60 -> "$minutes min"
            minutes % 60 == 0L -> "${minutes / 60} h"
            else -> "${minutes / 60} h ${minutes % 60} min"
        }
    }

    /** "11 Nov 2026, 14:05" in the phone's time zone. */
    fun dateTime(epochMs: Long): String = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.UK).format(Date(epochMs))
}

/** One of the two choices in the download dialog. */
data class DownloadOption(
    val quality: DownloadQuality,
    /** False when this account may not use it; [description] then says why. */
    val enabled: Boolean,
    /** Approximate download size, or null if it cannot be worked out. */
    val sizeBytes: Long?,
    val description: String,
)

/** The Original / 720p choice: which is offered, which is pre-selected, and roughly how big each is. */
object DownloadChoice {
    /** 720p is always pre-selected. */
    val DEFAULT = DownloadQuality.P720

    /** The cap the server transcodes 720p downloads to (picture and sound together). */
    const val P720_BITRATE = 4_000_000L

    const val ORIGINAL_ALLOWED = "The file exactly as it is on the server. Biggest, and may not play if this phone can't decode it."
    const val ORIGINAL_NOT_ALLOWED =
        "Your Jellyfin account isn't allowed to download original files. Ask whoever runs the server to switch on " +
            "“Allow media downloads” for you, or use 720p."
    const val P720_DESCRIPTION = "Converted by the server to fit the car screen. Smaller, and plays on any phone."

    /**
     * Rough size of a 720p download: the 4 Mbps cap (or the file's own bitrate if that is lower)
     * for the whole running time. Null if the running time is unknown.
     */
    fun estimate720pBytes(runtimeMs: Long?, sourceBitrate: Int?): Long? {
        if (runtimeMs == null || runtimeMs <= 0) return null
        val bitrate = minOf(P720_BITRATE, sourceBitrate?.toLong() ?: P720_BITRATE)
        return bitrate * runtimeMs / 8_000
    }

    /** Size of the original file: what the server reports, else its bitrate for the running time. */
    fun estimateOriginalBytes(runtimeMs: Long?, source: SourceInfo?): Long? {
        source?.sizeBytes?.let { return it }
        val bitrate = source?.bitrate ?: return null
        if (runtimeMs == null || runtimeMs <= 0) return null
        return bitrate.toLong() * runtimeMs / 8_000
    }

    /**
     * The two options for downloading [entries] together (one film or episode, or a season).
     * A size is given only when it is known for every entry.
     *
     * @param sources what the server said about each entry's file, by item id; may be incomplete.
     */
    fun options(entries: List<MediaEntry>, sources: Map<String, SourceInfo>): List<DownloadOption> {
        val originalAllowed = entries.isNotEmpty() && entries.all { it.canDownload }
        return listOf(
            DownloadOption(
                quality = DownloadQuality.ORIGINAL,
                enabled = originalAllowed,
                sizeBytes = total(entries) { estimateOriginalBytes(it.runtimeMs, sources[it.id]) },
                description = if (originalAllowed) ORIGINAL_ALLOWED else ORIGINAL_NOT_ALLOWED,
            ),
            DownloadOption(
                quality = DownloadQuality.P720,
                enabled = true,
                sizeBytes = total(entries) { estimate720pBytes(it.runtimeMs, sources[it.id]?.bitrate) },
                description = P720_DESCRIPTION,
            ),
        )
    }

    /** "About 1.2 GB", "Checking size…" while the server is being asked, else "Size unknown". */
    fun sizeLabel(option: DownloadOption, checking: Boolean): String = when {
        option.sizeBytes != null -> "About ${Format.bytes(option.sizeBytes)}"
        checking -> "Checking size…"
        else -> "Size unknown"
    }

    private fun total(entries: List<MediaEntry>, size: (MediaEntry) -> Long?): Long? {
        if (entries.isEmpty()) return null
        return entries.sumOf { size(it) ?: return null }
    }
}

/** One line describing where a download has got to. */
fun downloadStatusText(item: DownloadedItem): String {
    val percent = "${item.percent.toInt()}%"
    return when (item.status) {
        DownloadStatus.QUEUED -> "Waiting to start"
        DownloadStatus.DOWNLOADING -> "Downloading · $percent · ${Format.bytes(item.bytesDownloaded)}"
        DownloadStatus.PAUSED -> "Paused · $percent"
        DownloadStatus.COMPLETED -> "Downloaded · ${Format.bytes(item.bytesDownloaded)}"
        DownloadStatus.FAILED -> "Failed · tap Retry to start again"
        DownloadStatus.REMOVING -> "Deleting…"
    }
}

/** Short status for a film or episode in Browse that is already in the downloads. */
fun downloadBadgeText(item: DownloadedItem): String = when (item.status) {
    DownloadStatus.QUEUED -> "Queued"
    DownloadStatus.DOWNLOADING -> "Downloading ${item.percent.toInt()}%"
    DownloadStatus.PAUSED -> "Paused"
    DownloadStatus.COMPLETED -> "Downloaded"
    DownloadStatus.FAILED -> "Download failed"
    DownloadStatus.REMOVING -> "Deleting…"
}

/** Things to warn about before signing in to [server]; empty when all is well. */
fun serverWarnings(server: ServerInfo): List<String> = buildList {
    when (server.compatibility) {
        ServerCompatibility.SUPPORTED -> Unit
        ServerCompatibility.TOO_OLD ->
            add("This server runs Jellyfin ${server.version}. AAFin is made for 10.10 or newer, so some things may not work.")
        ServerCompatibility.NEWER_UNTESTED ->
            add("This server runs Jellyfin ${server.version}, which is newer than AAFin has been tested with. It should still work.")
        ServerCompatibility.UNKNOWN ->
            add("The server didn't say which version of Jellyfin it runs. It may still work.")
    }
    if (!server.isSecure) add("This connection is not encrypted (HTTP). Only use it on your home network.")
}

/** How a car was recognised, in words: from its profile key (`model:…` or `screen:…`). */
fun describeCarKey(key: String): String = when {
    key.startsWith("model:") -> "Recognised by model: " + key.removePrefix("model:").replace('|', ' ')
    key.startsWith("screen:") -> {
        val match = SCREEN_KEY.matchEntire(key.removePrefix("screen:"))
        if (match == null) {
            "The car didn't identify itself"
        } else {
            val (width, height, dpi) = match.destructured
            "Recognised by screen: $width × $height, $dpi dpi"
        }
    }
    else -> key
}

private val SCREEN_KEY = Regex("(\\d+)x(\\d+)@(\\d+)")

/** "S1:E2 · 2019 · 1 h 42 min" style line under a title; skips what is unknown. */
fun entrySubtitle(entry: MediaEntry): String = listOfNotNull(
    entry.episodeLabel,
    entry.productionYear?.toString(),
    entry.runtimeMs?.takeIf { it > 0 }?.let(Format::duration),
).joinToString(" · ")
