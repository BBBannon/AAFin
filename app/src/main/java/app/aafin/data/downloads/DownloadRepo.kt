package app.aafin.data.downloads

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import app.aafin.data.accounts.Account
import app.aafin.data.accounts.AccountStore
import app.aafin.data.jellyfin.ImageKind
import app.aafin.data.jellyfin.JellyfinException
import app.aafin.data.jellyfin.JellyfinUrls
import app.aafin.data.jellyfin.MediaEntry
import app.aafin.data.playback.ExternalSubtitle
import app.aafin.data.playback.OfflineMedia
import app.aafin.data.playback.PlayMethod
import app.aafin.data.playback.PlaybackRequest
import app.aafin.data.playback.PlaybackResolver
import app.aafin.data.playback.QualityTier
import app.aafin.data.playback.StreamTrack
import app.aafin.data.playback.SubtitleDelivery
import app.aafin.data.playback.SubtitleMime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

enum class DownloadStatus { QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED, REMOVING }

/** A download as the UI sees it. */
data class DownloadedItem(
    val metadata: DownloadMetadata,
    val status: DownloadStatus,
    /** 0-100. */
    val percent: Float,
    val bytesDownloaded: Long,
    /** Poster saved with the download, if any. */
    val posterFile: File?,
) {
    val itemId: String get() = metadata.itemId

    /** The item with the locally saved resume position applied. */
    val entry: MediaEntry get() = metadata.entry.copy(resumePositionMs = metadata.resumePositionMs)
}

/**
 * Downloads for offline playback.
 *
 * Layout under `files/downloads/`: `media/` is the Media3 cache holding the video;
 * `items/<itemId>/` holds `metadata.json`, the poster and subtitle files.
 *
 * - Original quality: a progressive download of `/Items/{id}/Download`.
 * - 720p: an HLS download of the transcoding URL Jellyfin issues when direct play is disabled
 *   (h264/aac, at most 1280 wide, 4 Mbps).
 *
 * Call from the main thread (Media3's download manager is main-thread bound); the suspend
 * functions move file and network work to background threads themselves.
 */
@OptIn(UnstableApi::class)
class DownloadRepo(
    context: Context,
    private val environment: DownloadEnvironment,
    private val resolver: PlaybackResolver,
    private val accountStore: AccountStore,
    private val mediaHttpClient: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val metadataStore = DownloadMetadataStore(File(environment.rootFolder, "items")) { itemId, error ->
        Log.w(TAG, "Unreadable download details for $itemId: ${error.javaClass.simpleName}")
    }
    private val downloadManager: DownloadManager get() = environment.downloadManager

    private val _downloads = MutableStateFlow<List<DownloadedItem>>(emptyList())

    /** Every download, oldest first, kept up to date (progress refreshes about once a second). */
    val downloads: StateFlow<List<DownloadedItem>> = _downloads.asStateFlow()

    private var progressTicker: Job? = null

    /** Metadata read from disk, reused between progress refreshes; cleared whenever it changes. */
    @Volatile
    private var cachedMetadata: List<DownloadMetadata>? = null

    init {
        downloadManager.addListener(object : DownloadManager.Listener {
            override fun onInitialized(downloadManager: DownloadManager) = requestRefresh()

            override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
                if (download.state == Download.STATE_FAILED) {
                    // Class name only: the message can contain the (token-bearing) URL.
                    Log.w(TAG, "Download failed for ${download.request.id}: ${finalException?.javaClass?.simpleName}")
                }
                requestRefresh()
            }

            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) = requestRefresh()
        })
        scope.launch {
            reconcile()
            refresh()
        }
    }

    /**
     * Starts downloading [entry] (a film or episode) for the active account.
     *
     * Fetches playback details, saves the poster and text subtitles, writes the metadata and
     * hands the media to the download service. Returns once the download is queued.
     *
     * @throws JellyfinException if signed out, the server can't be reached or offers no suitable stream.
     * @throws IOException if the metadata can't be written to storage.
     */
    suspend fun enqueue(entry: MediaEntry, quality: DownloadQuality) {
        require(entry.kind.isPlayable) { "Only films and episodes can be downloaded" }
        val account = accountStore.active() ?: throw JellyfinException(JellyfinException.Kind.NOT_SIGNED_IN)

        val tier = if (quality == DownloadQuality.ORIGINAL) QualityTier.ORIGINAL else QualityTier.P720_4M
        val plan = resolver.resolveOnline(
            PlaybackRequest(entry.id, tier, forceTranscode = quality == DownloadQuality.P720),
            entry.displayTitle,
        ).plan

        val request = when (quality) {
            DownloadQuality.ORIGINAL ->
                DownloadRequest.Builder(entry.id, Uri.parse(JellyfinUrls.download(account.serverUrl, entry.id)))
                    // A stable key, so playback finds the file whatever the server URL becomes.
                    .setCustomCacheKey("aafin-original-${entry.id}")
                    .build()
            DownloadQuality.P720 -> {
                if (!plan.isHls) throw JellyfinException(JellyfinException.Kind.NO_COMPATIBLE_STREAM)
                DownloadRequest.Builder(entry.id, Uri.parse(plan.url))
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .build()
            }
        }

        val metadata = withContext(Dispatchers.IO) {
            val posterFileName = savePoster(account, entry)
            val subtitles = plan.externalSubtitles.mapNotNull { saveSubtitle(entry.id, it) }
            DownloadMetadata(
                itemId = entry.id,
                accountId = account.id,
                serverName = account.serverName,
                entry = entry,
                quality = quality,
                mediaSourceId = plan.mediaSourceId,
                playableOnThisPhone = quality == DownloadQuality.P720 || plan.playMethod == PlayMethod.DIRECT_PLAY,
                posterFileName = posterFileName,
                subtitles = subtitles,
                audioTracks = if (quality == DownloadQuality.ORIGINAL) plan.audioTracks else emptyList(),
                createdAtMs = System.currentTimeMillis(),
                resumePositionMs = entry.resumePositionMs,
            ).also(metadataStore::save)
        }
        cachedMetadata = null

        DownloadService.sendAddDownload(appContext, AAFinDownloadService::class.java, request, /* foreground = */ false)
        Log.i(TAG, "Queued ${metadata.quality} download of ${metadata.itemId}")
        refresh()
    }

    /** Cancels (if running) and deletes the download and all its files. */
    suspend fun delete(itemId: String) {
        downloadManager.removeDownload(itemId)
        withContext(Dispatchers.IO) { metadataStore.delete(itemId) }
        cachedMetadata = null
        refresh()
    }

    suspend fun find(itemId: String): DownloadedItem? {
        refresh()
        return downloads.value.firstOrNull { it.itemId == itemId }
    }

    /** What the player needs to play [itemId] offline, or null unless its download has completed. */
    suspend fun offlineMedia(itemId: String): OfflineMedia? = withContext(Dispatchers.IO) {
        val metadata = metadataStore.load(itemId) ?: return@withContext null
        val download = downloadManager.downloadIndex.getDownload(itemId)
            ?.takeIf { it.state == Download.STATE_COMPLETED }
            ?: return@withContext null
        OfflineMedia(
            itemId = itemId,
            accountId = metadata.accountId,
            mediaSourceId = metadata.mediaSourceId,
            title = metadata.entry.displayTitle,
            runtimeMs = metadata.entry.runtimeMs,
            downloadRequest = download.request,
            subtitles = metadata.subtitles.map {
                ExternalSubtitle(it.index, Uri.fromFile(metadataStore.file(itemId, it.fileName)).toString(), it.mimeType, it.language, it.label)
            },
            audioTracks = metadata.audioTracks,
            subtitleTracks = metadata.subtitles.map {
                StreamTrack(
                    index = it.index,
                    label = it.label ?: it.language ?: "Subtitles",
                    language = it.language,
                    delivery = SubtitleDelivery.SIDE_LOADED,
                )
            },
        )
    }

    /** Remembers where the user got to in a downloaded item, so resume works with no network. */
    suspend fun saveResumePosition(itemId: String, positionMs: Long) {
        withContext(Dispatchers.IO) {
            val metadata = metadataStore.load(itemId) ?: return@withContext
            metadataStore.save(metadata.copy(resumePositionMs = positionMs))
            cachedMetadata = null
        }
        refresh()
    }

    /** Bytes used by downloads (video, posters, subtitles). */
    suspend fun storageUsedBytes(): Long = withContext(Dispatchers.IO) {
        environment.cache.cacheSpace + metadataStore.sizeBytes()
    }

    /**
     * Restarts the download service if there are unfinished downloads. Call when the phone UI
     * comes to the foreground (starting a foreground service is only allowed then).
     */
    fun resumePending() {
        if (downloadManager.currentDownloads.isNotEmpty()) {
            DownloadService.start(appContext, AAFinDownloadService::class.java)
        }
    }

    private fun requestRefresh() {
        scope.launch { refresh() }
    }

    private suspend fun refresh() {
        // In-flight downloads carry live progress; completed/failed ones are only in the index.
        val live = downloadManager.currentDownloads.associateBy { it.request.id }
        val items = withContext(Dispatchers.IO) {
            val recorded = recordedDownloads()
            val metadata = cachedMetadata ?: metadataStore.all().also { cachedMetadata = it }
            metadata.map { toItem(it, live[it.itemId] ?: recorded[it.itemId]) }
        }
        _downloads.value = items
        updateProgressTicker(items.any { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED })
    }

    private fun updateProgressTicker(active: Boolean) {
        if (active && progressTicker?.isActive != true) {
            progressTicker = scope.launch {
                while (true) {
                    delay(PROGRESS_INTERVAL_MS)
                    refresh()
                }
            }
        } else if (!active) {
            progressTicker?.cancel()
            progressTicker = null
        }
    }

    /**
     * Run once at start-up, when no enqueue can be in flight: drops media with no metadata and
     * metadata with no media, which a crash part-way through [enqueue] or [delete] could leave.
     */
    private suspend fun reconcile() {
        val (recordedIds, metadataIds) = withContext(Dispatchers.IO) {
            recordedDownloads().keys to metadataStore.all().map { it.itemId }.toSet()
        }
        (recordedIds - metadataIds).forEach { orphan ->
            Log.w(TAG, "Removing download $orphan: its details are missing")
            downloadManager.removeDownload(orphan)
        }
        withContext(Dispatchers.IO) {
            (metadataIds - recordedIds).forEach { orphan ->
                Log.w(TAG, "Removing details of $orphan: its media is missing")
                metadataStore.delete(orphan)
            }
        }
        cachedMetadata = null
    }

    private fun recordedDownloads(): Map<String, Download> = buildMap {
        downloadManager.downloadIndex.getDownloads().use { cursor ->
            while (cursor.moveToNext()) put(cursor.download.request.id, cursor.download)
        }
    }

    private fun toItem(metadata: DownloadMetadata, download: Download?): DownloadedItem = DownloadedItem(
        metadata = metadata,
        status = when (download?.state) {
            // Not recorded yet: the service is still picking the request up.
            null, Download.STATE_QUEUED, Download.STATE_RESTARTING -> DownloadStatus.QUEUED
            Download.STATE_DOWNLOADING -> DownloadStatus.DOWNLOADING
            Download.STATE_STOPPED -> DownloadStatus.PAUSED
            Download.STATE_COMPLETED -> DownloadStatus.COMPLETED
            Download.STATE_REMOVING -> DownloadStatus.REMOVING
            else -> DownloadStatus.FAILED
        },
        percent = download?.percentDownloaded?.coerceIn(0f, 100f) ?: 0f,
        bytesDownloaded = download?.bytesDownloaded ?: 0,
        posterFile = metadata.posterFileName?.let { metadataStore.file(metadata.itemId, it) },
    )

    /** Saves the item's poster (the series poster for an episode without its own). Null if there is none. */
    private fun savePoster(account: Account, entry: MediaEntry): String? {
        val url = when {
            entry.primaryImageTag != null ->
                JellyfinUrls.image(account.serverUrl, entry.id, ImageKind.PRIMARY, POSTER_WIDTH, entry.primaryImageTag)
            entry.seriesId != null && entry.seriesPrimaryImageTag != null ->
                JellyfinUrls.image(account.serverUrl, entry.seriesId, ImageKind.PRIMARY, POSTER_WIDTH, entry.seriesPrimaryImageTag)
            else -> return null
        }
        return fetchToFile(url, metadataStore.file(entry.id, "poster.jpg"), "poster")
    }

    private fun saveSubtitle(itemId: String, subtitle: ExternalSubtitle): DownloadedSubtitle? {
        val fileName = "subtitle-${subtitle.index}.${SubtitleMime.extensionFor(subtitle.mimeType)}"
        fetchToFile(subtitle.url, metadataStore.file(itemId, fileName), "subtitle ${subtitle.index}") ?: return null
        return DownloadedSubtitle(subtitle.index, fileName, subtitle.mimeType, subtitle.language, subtitle.label)
    }

    /**
     * Downloads a small companion file. A failure here is logged and the download carries on
     * without that poster or subtitle rather than failing altogether.
     *
     * @return the file's name, or null if it could not be fetched.
     */
    private fun fetchToFile(url: String, target: File, what: String): String? = try {
        target.parentFile?.mkdirs()
        mediaHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            target.outputStream().use { output -> response.body.byteStream().copyTo(output) }
        }
        target.name
    } catch (e: IOException) {
        // Never log the URL: it can carry an access token.
        Log.w(TAG, "Could not save $what for download: ${e.javaClass.simpleName}")
        target.delete()
        null
    }

    private companion object {
        const val TAG = "AAFin.Downloads"
        const val PROGRESS_INTERVAL_MS = 1_000L
        const val POSTER_WIDTH = 600
    }
}
