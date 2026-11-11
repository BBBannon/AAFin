package app.aafin.data.downloads

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.DownloadManager
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.Executors

/**
 * The Media3 download machinery: one cache, one download index, one [DownloadManager].
 *
 * Must be created on the main thread (Media3 binds the manager to the creating thread's looper)
 * and exactly once per process (a cache folder may only be opened by one [SimpleCache]).
 *
 * @param mediaHttpClient OkHttp client that adds the Jellyfin `Authorization` header.
 */
@OptIn(UnstableApi::class)
class DownloadEnvironment(context: Context, mediaHttpClient: OkHttpClient) {
    private val appContext = context.applicationContext
    private val databaseProvider = StandaloneDatabaseProvider(appContext)
    private val httpDataSourceFactory = OkHttpDataSource.Factory(mediaHttpClient)

    /** Root of everything downloads store, inside app-private storage. */
    val rootFolder: File = File(appContext.filesDir, "downloads")

    /** Downloaded media. Nothing is ever evicted: content stays until the user deletes it. */
    val cache: Cache = SimpleCache(File(rootFolder, "media"), NoOpCacheEvictor(), databaseProvider)

    val downloadManager: DownloadManager = DownloadManager(
        appContext,
        databaseProvider,
        cache,
        httpDataSourceFactory,
        Executors.newFixedThreadPool(DOWNLOAD_THREADS),
    ).apply {
        maxParallelDownloads = MAX_PARALLEL_DOWNLOADS
    }

    /**
     * Data source for the player: reads downloaded content from the cache (never writes to it)
     * and falls through to the network - or local files, for saved subtitles - for everything else.
     */
    val playbackDataSourceFactory: DataSource.Factory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(DefaultDataSource.Factory(appContext, httpDataSourceFactory))
        .setCacheWriteDataSinkFactory(null)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    private companion object {
        const val DOWNLOAD_THREADS = 3
        const val MAX_PARALLEL_DOWNLOADS = 2
    }
}
