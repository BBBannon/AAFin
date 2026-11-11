package app.aafin.car

import android.content.Context
import android.graphics.Bitmap
import androidx.car.app.model.CarIcon
import androidx.core.graphics.drawable.IconCompat
import app.aafin.data.jellyfin.MediaEntry
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.bitmapConfig
import coil3.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

/**
 * Loads posters with Coil (memory + disk cached) as small software bitmaps.
 *
 * Every image in a template is copied to the head unit, so they are kept small: at most
 * [WIDTH_PX] × [HEIGHT_PX] in RGB 565 (about 60 KB each).
 */
class CoilPosterSource(context: Context, private val backend: CarBackend) : PosterSource {
    private val appContext = context.applicationContext
    private val loader = SingletonImageLoader.get(appContext)

    override suspend fun poster(entry: MediaEntry): Bitmap? = load(backend.posterUrl(entry, WIDTH_PX))

    override suspend fun file(file: File): Bitmap? = load(file.takeIf { it.isFile })

    /** Failures (no network, missing image) simply mean "no poster": the row keeps its icon. */
    private suspend fun load(data: Any?): Bitmap? {
        if (data == null) return null
        val request = ImageRequest.Builder(appContext)
            .data(data)
            .size(WIDTH_PX, HEIGHT_PX)
            .allowHardware(false)
            .bitmapConfig(Bitmap.Config.RGB_565)
            .build()
        return (loader.execute(request) as? SuccessResult)?.image?.toBitmap()
    }

    companion object {
        const val WIDTH_PX = 144
        const val HEIGHT_PX = 216
    }
}

/**
 * The posters of one screen. Loads a few at a time and tells the screen to redraw at most once
 * every [REDRAW_INTERVAL_MS], however many arrive - Android Auto limits how often a template may
 * be refreshed.
 */
class PosterBoard(
    private val scope: CoroutineScope,
    private val source: PosterSource,
    private val onChanged: () -> Unit,
) {
    private val icons = HashMap<String, CarIcon>()
    private val requested = HashSet<String>()
    private val gate = Semaphore(PARALLEL_LOADS)
    private var redraw: Job? = null

    fun icon(itemId: String): CarIcon? = icons[itemId]

    /** Starts loading posters for [entries] that have not been asked for yet. */
    fun load(entries: List<MediaEntry>) = entries.forEach { entry -> request(entry.id) { source.poster(entry) } }

    /** Starts loading a poster saved on the phone (downloads). */
    fun load(itemId: String, file: File?) {
        if (file != null) request(itemId) { source.file(file) }
    }

    private fun request(itemId: String, fetch: suspend () -> Bitmap?) {
        if (!requested.add(itemId)) return
        scope.launch {
            val bitmap = gate.withPermit { fetch() } ?: return@launch
            icons[itemId] = CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()
            scheduleRedraw()
        }
    }

    private fun scheduleRedraw() {
        if (redraw?.isActive == true) return
        redraw = scope.launch {
            delay(REDRAW_INTERVAL_MS)
            onChanged()
        }
    }

    companion object {
        const val REDRAW_INTERVAL_MS = 750L
        private const val PARALLEL_LOADS = 4
    }
}
